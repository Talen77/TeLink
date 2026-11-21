package com.te.link.platform.NettyServer.Agent;
import com.google.gson.*;
import java.util.*;

/** 每次分析独享的实际检索引用，主 Agent 汇总时保留，避免正文截断丢失来源。 */
public final class AgentKnowledgeEvidence {
    private final Map<String,JsonObject> references=new LinkedHashMap<>();
    public synchronized void record(String response){
        try{JsonObject result=JsonParser.parseString(response).getAsJsonObject();if(!result.has("matches"))return;
            for(JsonElement item:result.getAsJsonArray("matches")){JsonObject match=item.getAsJsonObject();if(!match.has("chunkId") || references.size()>=24)continue;JsonObject ref=new JsonObject();for(String key:List.of("documentId","chunkId","title","version","heading","sourceUri","content","applicableVersion","service","component"))if(match.has(key))ref.add(key,match.get(key));references.put(match.get("chunkId").getAsString(),ref);}
        }catch(RuntimeException ignored){}
    }
    public synchronized JsonArray snapshot(){JsonArray result=reviewSnapshot();for(JsonElement item:result)item.getAsJsonObject().remove("content");return result;}
    public synchronized JsonArray reviewSnapshot(){JsonArray result=new JsonArray();references.values().forEach(value->result.add(value.deepCopy()));return result;}
}
