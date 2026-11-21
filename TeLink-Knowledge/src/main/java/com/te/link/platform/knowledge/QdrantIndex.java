package com.te.link.platform.knowledge;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public class QdrantIndex {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile boolean ready;
    public synchronized void ensureCollection()throws Exception{
        if(ready)return;
        String collection=collectionPath(RagConfig.COLLECTION);var response=request("GET",collection,null);
        if(response.statusCode()==404){
            JsonObject body=JsonParser.parseString("{\"vectors\":{\"dense\":{\"size\":"+RagConfig.DIMENSIONS+",\"distance\":\"Cosine\"}},\"sparse_vectors\":{\"lexical\":{\"modifier\":\"idf\"}}}").getAsJsonObject();
            request("PUT",collection,body);
            // 多消费者可能同时创建，最终以实际集合结构为准。
            response=request("GET",collection,null);
        }
        JsonObject result=successful(response).getAsJsonObject("result").getAsJsonObject("config").getAsJsonObject("params");
        JsonObject dense=result.getAsJsonObject("vectors").getAsJsonObject("dense");
        if(dense==null || dense.get("size").getAsInt()!=RagConfig.DIMENSIONS || !"Cosine".equals(dense.get("distance").getAsString()) || !result.has("sparse_vectors") || !result.getAsJsonObject("sparse_vectors").has("lexical") || !"idf".equals(RagRepository.text(result.getAsJsonObject("sparse_vectors").getAsJsonObject("lexical"),"modifier")))throw new IllegalStateException("Qdrant collection schema mismatch; use a new collection and reindex");
        JsonObject index=JsonParser.parseString("{\"field_name\":\"versionKey\",\"field_schema\":\"keyword\"}").getAsJsonObject();
        successful(request("PUT",collection+"/index?wait=true",index));
        index.addProperty("field_name","documentId");successful(request("PUT",collection+"/index?wait=true",index));ready=true;
    }
    public void upsert(String doc,int version,List<TextChunker.Chunk> chunks,List<List<Double>> vectors)throws Exception{
        ensureCollection();JsonArray points=new JsonArray();
        for(int i=0;i<chunks.size();i++){
            var chunk=chunks.get(i);JsonObject point=new JsonObject();point.addProperty("id",chunk.id());
            JsonObject vector=new JsonObject();JsonArray dense=new JsonArray();vectors.get(i).forEach(dense::add);vector.add("dense",dense);vector.add("lexical",LexicalVector.of(chunk.content()));point.add("vector",vector);
            JsonObject payload=new JsonObject();payload.addProperty("documentId",doc);payload.addProperty("versionKey",doc+":"+version);point.add("payload",payload);points.add(point);
        }
        JsonObject body=new JsonObject();body.add("points",points);successful(request("PUT",collectionPath(RagConfig.COLLECTION)+"/points?wait=true",body));
    }
    public JsonArray search(String query,List<Double> dense,List<String> activeVersions,int topK)throws Exception{
        ensureCollection();JsonArray versions=new JsonArray();activeVersions.forEach(versions::add);
        JsonObject match=new JsonObject();match.add("any",versions);JsonObject condition=new JsonObject();condition.addProperty("key","versionKey");condition.add("match",match);JsonArray must=new JsonArray();must.add(condition);JsonObject filter=new JsonObject();filter.add("must",must);
        JsonArray prefetch=new JsonArray();JsonObject semantic=new JsonObject();JsonArray vector=new JsonArray();dense.forEach(vector::add);semantic.add("query",vector);semantic.addProperty("using","dense");semantic.addProperty("limit",Math.max(20,topK*4));semantic.add("filter",filter);prefetch.add(semantic);
        JsonObject sparse=LexicalVector.of(query);
        if(!sparse.getAsJsonArray("indices").isEmpty()){JsonObject lexical=new JsonObject();lexical.add("query",sparse);lexical.addProperty("using","lexical");lexical.addProperty("limit",Math.max(20,topK*4));lexical.add("filter",filter);prefetch.add(lexical);}
        JsonObject body=new JsonObject();body.add("prefetch",prefetch);body.add("query",JsonParser.parseString("{\"fusion\":\"rrf\"}"));body.addProperty("limit",topK*3);body.addProperty("with_payload",true);
        return successful(request("POST",collectionPath(RagConfig.COLLECTION)+"/points/query",body)).getAsJsonObject("result").getAsJsonArray("points");
    }
    public void deleteDocument(String collection,String doc)throws Exception{
        JsonObject body=JsonParser.parseString("{\"filter\":{\"must\":[{\"key\":\"documentId\",\"match\":{\"value\":\""+doc+"\"}}]}}").getAsJsonObject();
        var response=request("POST",collectionPath(collection)+"/points/delete?wait=true",body);if(response.statusCode()!=404)successful(response);
    }
    private String collectionPath(String name){if(name==null || !name.matches("[a-zA-Z0-9_-]{1,100}"))throw new IllegalArgumentException("invalid collection name");return "/collections/"+name;}
    private HttpResponse<String> request(String method,String path,JsonObject body)throws Exception{
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(RagConfig.QDRANT_URL+path)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString()));
        if(!RagConfig.QDRANT_KEY.isBlank())request.header("api-key",RagConfig.QDRANT_KEY);return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonObject successful(HttpResponse<String> response){if(response.statusCode()<200 || response.statusCode()>=300)throw new IllegalStateException("Qdrant HTTP status "+response.statusCode());return JsonParser.parseString(response.body()).getAsJsonObject();}
}
