package com.te.link.platform.knowledge;

import com.google.gson.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

/** 解析、向量化与激活版本；只有完整索引成功的版本才对检索可见。 */
public class RagEngine implements AutoCloseable {
    private final RagRepository repository;
    private final KnowledgeFiles files;
    private final DocumentParser parser;
    private final TextChunker chunker;
    private final EmbeddingClient embeddings;
    private final QdrantIndex index;
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"knowledge-task-heartbeat");t.setDaemon(true);return t;});
    public RagEngine(RagRepository repository,KnowledgeFiles files){this(repository,files,new DocumentParser(),new TextChunker(),new EmbeddingClient(),new QdrantIndex());}
    public RagEngine(RagRepository repository,KnowledgeFiles files,DocumentParser parser,TextChunker chunker,EmbeddingClient embeddings,QdrantIndex index){this.repository=repository;this.files=files;this.parser=parser;this.chunker=chunker;this.embeddings=embeddings;this.index=index;}
    public void process(String taskId)throws Exception{
        String owner=UUID.randomUUID().toString();
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(20);
        if(!repository.claim(taskId,owner))return;
        ScheduledFuture<?> heartbeat=heartbeats.scheduleAtFixedRate(()->{try{repository.heartbeat(taskId,owner);}catch(Exception ignored){}},20,20,TimeUnit.SECONDS);
        try {
            JsonObject task=repository.task(taskId);String doc=RagRepository.text(task,"document_id");
            try(Connection lock=repository.documentLock(doc)){
                repository.assertCurrent(taskId,owner);
                if("DELETE".equals(RagRepository.text(task,"kind"))){
                    JsonArray versions=repository.versions(doc);Set<String> collections=new HashSet<>(),paths=new HashSet<>();
                    for(JsonElement item:versions){JsonObject version=item.getAsJsonObject();collections.add(RagRepository.text(version,"index_collection"));paths.add(RagRepository.text(version,"storage_path"));}
                    for(String collection:collections){repository.assertCurrent(taskId,owner);index.deleteDocument(collection,doc);}
                    for(String path:paths)files.delete(path);
                    repository.completeDelete(taskId,owner);return;
                }
                if(!RagConfig.PROFILE.equals(RagRepository.text(task,"embedding_profile")) || !RagConfig.COLLECTION.equals(RagRepository.text(task,"index_collection")))throw new IllegalArgumentException("embedding settings changed; create a new version with reindex");
                int version=task.get("version").getAsInt();
                repository.stage(taskId,owner,"PARSING");
                var blocks=parser.parse(files.resolve(RagRepository.text(task,"storage_path")),RagRepository.text(task,"file_name"));
                var chunks=chunker.split(doc,version,blocks);
                repository.chunks(taskId,owner,chunks);
                repository.stage(taskId,owner,"INDEXING");
                for(int start=0;start<chunks.size();start+=16){
                    if(System.nanoTime()>deadline)throw new IllegalStateException("indexing exceeded 20 minutes; split the document or check model latency");
                    repository.assertCurrent(taskId,owner);
                    var batch=chunks.subList(start,Math.min(chunks.size(),start+16));
                    var vectors=embeddings.embed(batch.stream().map(TextChunker.Chunk::content).toList());
                    repository.assertCurrent(taskId,owner);
                    index.upsert(doc,version,batch,vectors);
                }
                repository.completeIndex(taskId,owner,chunks.size());
            }
        }catch(RagRepository.Superseded e){repository.cancel(taskId,owner);}
        catch(RagRepository.DocumentBusy e){repository.defer(taskId,owner);}
        catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            repository.failed(taskId,owner,e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),"processing failed"));
        }finally{heartbeat.cancel(false);}
    }
    public JsonObject search(JsonObject request)throws Exception{
        String query=string(request,"query").strip();if(query.isBlank() || query.length()>4000)throw new IllegalArgumentException("query must contain 1 to 4000 characters");
        int topK=5;
        if(request.has("topK")){
            JsonElement value=request.get("topK");
            try{if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();topK=value.getAsBigDecimal().intValueExact();}
            catch(ArithmeticException | NumberFormatException e){throw new IllegalArgumentException("topK must be an integer between 1 and 8");}
        }
        if(topK<1 || topK>8)throw new IllegalArgumentException("topK must be between 1 and 8");
        JsonArray active=repository.activeVersions(filter(request,"component"),filter(request,"service"),filter(request,"applicableVersion"));
        JsonObject response=new JsonObject();JsonArray matches=new JsonArray();response.add("matches",matches);response.addProperty("status","ok");
        if(active.isEmpty()){response.addProperty("status","no_active_knowledge");return response;}
        if(active.size()>5000)throw new IllegalArgumentException("narrow the component or service filter before searching");
        List<String> keys=new ArrayList<>();for(JsonElement row:active){JsonObject d=row.getAsJsonObject();keys.add(d.get("id").getAsString()+":"+d.get("active_version").getAsInt());}
        var candidates=index.search(query,embeddings.embed(List.of(query)).get(0),keys,topK);
        int budget=0;
        for(JsonElement candidate:candidates){JsonObject hit=candidate.getAsJsonObject();JsonObject chunk=repository.visibleChunk(hit.get("id").getAsString());if(chunk==null)continue;
            // 再次核对当前版本，过滤检索期间发生的版本切换、禁用和删除。
            String versionKey=chunk.get("document_id").getAsString()+":"+chunk.get("version").getAsInt();if(!keys.contains(versionKey))continue;
            String content=RagRepository.text(chunk,"content");
            String heading=RagRepository.text(chunk,"heading");
            String title=RagRepository.text(chunk,"title");
            if(!LexicalVector.overlaps(query, title+"\n"+heading+"\n"+content)) continue;
            if(budget+content.length()>9000)break;budget+=content.length();
            JsonObject result=new JsonObject();result.addProperty("documentId",RagRepository.text(chunk,"document_id"));result.addProperty("chunkId",RagRepository.text(chunk,"id"));result.addProperty("title",RagRepository.text(chunk,"title"));result.add("version",chunk.get("version"));result.addProperty("heading",RagRepository.text(chunk,"heading"));result.addProperty("content",content);result.addProperty("fileName",RagRepository.text(chunk,"file_name"));result.addProperty("component",RagRepository.text(chunk,"component"));result.addProperty("service",RagRepository.text(chunk,"service_name"));result.addProperty("applicableVersion",RagRepository.text(chunk,"applicable_version"));result.addProperty("updatedAt",RagRepository.text(chunk,"updated_at"));result.addProperty("sourceUri","/admin/api/knowledge/documents/"+RagRepository.text(chunk,"document_id")+"/chunks/"+RagRepository.text(chunk,"id"));result.add("score",hit.get("score"));matches.add(result);if(matches.size()==topK)break;
        }
        if(matches.isEmpty())response.addProperty("status","no_matches");return response;
    }
    private String filter(JsonObject request,String key){String value=string(request,key);if(value.length()>100)throw new IllegalArgumentException("filter too long");return value;}
    private String string(JsonObject request,String key){if(!request.has(key))return "";JsonElement value=request.get(key);if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException(key+" must be a string");return value.getAsString();}
    @Override public void close(){heartbeats.shutdownNow();}
}
