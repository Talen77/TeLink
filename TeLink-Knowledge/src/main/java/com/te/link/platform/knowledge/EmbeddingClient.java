package com.te.link.platform.knowledge;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 可配置的 embeddings HTTP 接口；不使用伪造向量降级。 */
public class EmbeddingClient {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public List<List<Double>> embed(List<String> texts)throws Exception{
        if(RagConfig.EMBEDDING_URL.isBlank() || RagConfig.EMBEDDING_MODEL.isBlank())throw new IllegalStateException("configure embedding URL and model first");
        JsonObject body=new JsonObject();body.addProperty("model",RagConfig.EMBEDDING_MODEL);JsonArray input=new JsonArray();texts.forEach(input::add);body.add("input",input);body.addProperty("encoding_format","float");
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(RagConfig.EMBEDDING_URL)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if(!RagConfig.EMBEDDING_KEY.isBlank())request.header("Authorization","Bearer "+RagConfig.EMBEDDING_KEY);
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("embedding HTTP status "+response.statusCode());
        JsonArray data=JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data");
        if(data==null || data.size()!=texts.size())throw new IllegalStateException("embedding batch size mismatch");
        List<List<Double>> result=new ArrayList<>(Collections.nCopies(texts.size(),null));
        for(JsonElement item:data){JsonObject row=item.getAsJsonObject();int index=row.get("index").getAsInt();if(index<0 || index>=texts.size() || result.get(index)!=null)throw new IllegalStateException("invalid embedding index");
            JsonArray values=row.getAsJsonArray("embedding");if(values.size()!=RagConfig.DIMENSIONS)throw new IllegalStateException("embedding dimension mismatch");
            List<Double> vector=new ArrayList<>();double norm=0;for(JsonElement value:values){double v=value.getAsDouble();if(!Double.isFinite(v))throw new IllegalStateException("nonfinite embedding");vector.add(v);norm+=v*v;}
            if(norm==0)throw new IllegalStateException("zero embedding");result.set(index,vector);
        }return result;
    }
}
