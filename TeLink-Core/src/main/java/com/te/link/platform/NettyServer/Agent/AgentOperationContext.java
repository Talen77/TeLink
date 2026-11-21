package com.te.link.platform.NettyServer.Agent;

import com.google.gson.*;
import java.util.*;

/** 由真实工具返回值建立范围，不信任模型提供的节点范围或引用。每轮分析独享。 */
public final class AgentOperationContext {
    private final Set<Integer> observedNodes = new HashSet<>();
    private final Map<Integer, JsonArray> logs = new HashMap<>();
    private final JsonArray proposals = new JsonArray();
    public synchronized void observe(String tool, String response) {
        try {
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            if (AgentToolNames.QUERY_NODE.equals(tool) && result.has("machineId")) observedNodes.add(result.get("machineId").getAsInt());
            if (AgentToolNames.QUERY_LOGS.equals(tool) && result.has("logs")) {
                for (JsonElement item : result.getAsJsonArray("logs")) {
                    JsonObject row = item.getAsJsonObject();
                    if (!row.has("machineId") || row.get("machineId").isJsonNull()) continue;
                    int id = row.get("machineId").getAsInt();
                    observedNodes.add(id);
                    JsonArray evidence = logs.computeIfAbsent(id, ignored -> new JsonArray());
                    if (evidence.size() < 10) evidence.add(row.deepCopy());
                }
            }
        } catch (RuntimeException ignored) { /* 错误观察值不能扩大操作范围。 */ }
    }
    public synchronized boolean allows(int id) { return observedNodes.contains(id); }
    public synchronized JsonArray logs(int id) { return logs.getOrDefault(id, new JsonArray()).deepCopy(); }
    public synchronized void proposed(JsonObject operation) {
        JsonObject summary = new JsonObject();
        for (String key : List.of("id","machineId","action","state","reason","expiresAt")) summary.add(key,operation.get(key));
        proposals.add(summary);
    }
    public synchronized JsonArray proposals() { return proposals.deepCopy(); }
}
