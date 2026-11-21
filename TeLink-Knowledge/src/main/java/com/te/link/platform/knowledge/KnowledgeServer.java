package com.te.link.platform.knowledge;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class KnowledgeServer implements AutoCloseable {
    private final RagRepository repository;private final KnowledgeFiles files;private final RagEngine engine;
    private final HttpServer server;private final ExecutorService executor=Executors.newFixedThreadPool(8);
    public KnowledgeServer(String host,int port,RagRepository repository,KnowledgeFiles files,RagEngine engine)throws IOException{
        this.repository=repository;this.files=files;this.engine=engine;server=HttpServer.create(new InetSocketAddress(host,port),64);server.setExecutor(executor);server.createContext("/",this::handle);
    }
    public int port(){return server.getAddress().getPort();}public void start(){server.start();}
    private void handle(HttpExchange exchange)throws IOException{
        try {
            String auth=exchange.getRequestHeaders().getFirst("Authorization");
            if(!RagConfig.API_KEY.isBlank() && (auth==null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),("Bearer "+RagConfig.API_KEY).getBytes(StandardCharsets.UTF_8)))){send(exchange,401,error("unauthorized"));return;}
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            if(path.equals("/health") && method.equals("GET")){send(exchange,200,json("{\"status\":\"ok\"}"));return;}
            if(path.equals("/knowledge/search") && method.equals("POST")){send(exchange,200,engine.search(body(exchange)));return;}
            if(path.equals("/knowledge/documents")){
                Map<String,String> query=query(exchange);
                if(method.equals("GET")){int page=integer(query.getOrDefault("page","1"),1,100000),size=integer(query.getOrDefault("pageSize","20"),1,100);send(exchange,200,repository.list(page,size));return;}
                if(method.equals("POST")){send(exchange,202,upload(exchange,null,query));return;}
            }
            String[] parts=path.split("/");
            if(parts.length>=4 && parts[1].equals("knowledge") && parts[2].equals("documents") && parts[3].matches("[a-f0-9-]{36}")){
                String id=parts[3];
                if(parts.length==4){
                    if(method.equals("GET")){send(exchange,200,repository.detail(id));return;}
                    if(method.equals("DELETE")){repository.delete(id);send(exchange,202,json("{\"status\":\"DELETE_QUEUED\"}"));return;}
                    if(method.equals("PATCH")){JsonObject data=body(exchange);if(!data.has("enabled") || !data.get("enabled").isJsonPrimitive() || !data.get("enabled").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("enabled must be boolean");repository.configure(id,data.get("enabled").getAsBoolean());send(exchange,200,json("{\"status\":\"updated\"}"));return;}
                }
                if(parts.length==5 && method.equals("POST")){
                    switch(parts[4]){
                        case "versions" -> {send(exchange,202,upload(exchange,id,query(exchange)));return;}
                        case "retry" -> {send(exchange,202,repository.retry(id));return;}
                        case "reindex" -> {send(exchange,202,repository.reindex(id));return;}
                    }
                }
                if(parts.length==6 && parts[4].equals("chunks") && parts[5].matches("[a-f0-9-]{36}") && method.equals("GET")){
                    JsonObject chunk=repository.visibleChunk(parts[5]);if(chunk==null || !id.equals(RagRepository.text(chunk,"document_id"))){send(exchange,404,error("chunk not available"));return;}send(exchange,200,chunk);return;
                }
            }
            send(exchange,404,error("endpoint or method not found"));
        }catch(IllegalArgumentException e){send(exchange,400,error(e.getMessage()));}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();System.err.println("[Knowledge] request failed: "+e.getClass().getSimpleName());send(exchange,503,error("知识服务暂不可用，请检查 MySQL、Embedding 和 Qdrant 配置及服务日志。"));}
        finally{exchange.close();}
    }
    private JsonObject upload(HttpExchange exchange,String id,Map<String,String> query)throws Exception{
        if(RagConfig.EMBEDDING_URL.isBlank() || RagConfig.EMBEDDING_MODEL.isBlank())throw new IllegalArgumentException("请先配置 Embedding URL、模型和向量维度。");
        String filename=required(query,"filename",255);KnowledgeFiles.validateName(filename);
        String title=query.getOrDefault("title",filename);if(title.isBlank() || title.length()>255)throw new IllegalArgumentException("title must contain 1 to 255 characters");
        String component=optional(query,"component",100),service=optional(query,"service",100),applicable=optional(query,"applicableVersion",100);
        byte[] bytes=exchange.getRequestBody().readNBytes(KnowledgeHttpClient.MAX_UPLOAD_BYTES+1);
        String key=files.save(filename,bytes);
        // 文件先落盘，数据库事务提交任务。事务失败时清理本次新文件。
        try{return repository.upload(id,title,component,service,applicable,filename,key,hash(bytes),bytes.length);}
        catch(Exception e){files.delete(key);throw e;}
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static JsonObject body(HttpExchange exchange)throws IOException{byte[] bytes=exchange.getRequestBody().readNBytes(16385);if(bytes.length>16384)throw new IllegalArgumentException("JSON body exceeds limit");return json(new String(bytes,StandardCharsets.UTF_8));}
    private static Map<String,String> query(HttpExchange e){Map<String,String> values=new HashMap<>();String raw=e.getRequestURI().getRawQuery();if(raw!=null)for(String item:raw.split("&")){String[] pair=item.split("=",2);values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}return values;}
    private static String required(Map<String,String> values,String key,int limit){String value=optional(values,key,limit);if(value.isBlank())throw new IllegalArgumentException(key+" is required");return value;}
    private static String optional(Map<String,String> values,String key,int limit){String value=values.getOrDefault(key,"").strip();if(value.length()>limit)throw new IllegalArgumentException(key+" is too long");return value;}
    private static int integer(String value,int min,int max){int n=Integer.parseInt(value);if(n<min || n>max)throw new IllegalArgumentException("page out of range");return n;}
    private static JsonObject json(String value){try{return JsonParser.parseString(value).getAsJsonObject();}catch(RuntimeException e){throw new IllegalArgumentException("expected a JSON object");}}
    private static JsonObject error(String message){JsonObject error=new JsonObject();error.addProperty("message",message);return error;}
    private static void send(HttpExchange e,int status,JsonObject value)throws IOException{byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}
    @Override public void close(){server.stop(0);executor.shutdownNow();}
}
