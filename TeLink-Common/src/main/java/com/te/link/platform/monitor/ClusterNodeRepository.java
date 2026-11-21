package com.te.link.platform.monitor;

import com.te.link.platform.redis.RedisClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.function.Supplier;

/** 读取各节点自己上报的快照，不以管理节点本机数据替代远端节点。 */
public class ClusterNodeRepository {
    private final Supplier<Map<String, String>> source;
    public ClusterNodeRepository() { this(RedisClient::listPushNodeSnapshots); }
    public ClusterNodeRepository(Supplier<Map<String, String>> source) { this.source = source; }

    public JsonObject list() {
        Map<String, String> snapshots = source.get();
        JsonArray nodes = new JsonArray();
        int online = 0;
        for (String key : snapshots.keySet().stream().sorted().toList()) {
            int id;
            try { id = Integer.parseInt(key); } catch (NumberFormatException ignored) { continue; }
            if (id < 0) continue;
            String raw = snapshots.get(key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            JsonObject snapshot = decode(id, raw);
            if (!snapshot.get("fresh").getAsBoolean() || !"RUNNING".equals(snapshot.get("status").getAsString())) {
                continue;
            }
            JsonObject node = new JsonObject();
            node.addProperty("machineId", id);
            node.add("status", snapshot.get("status"));
            node.add("fresh", snapshot.get("fresh"));
            online++;
            nodes.add(node);
        }
        JsonObject result = new JsonObject();
        result.addProperty("registeredCount", nodes.size());
        result.addProperty("onlineCount", online);
        result.add("nodes", nodes);
        return result;
    }

    public JsonObject get(int machineId) {
        Map<String, String> snapshots = source.get();
        if (!snapshots.containsKey(String.valueOf(machineId))) throw new IllegalArgumentException("unknown machineId");
        return decode(machineId, snapshots.get(String.valueOf(machineId)));
    }

    public JsonArray snapshots() {
        JsonArray result = new JsonArray();
        source.get().forEach((key, raw) -> {
            try { int id = Integer.parseInt(key); if (id >= 0) result.add(decode(id, raw)); }
            catch (NumberFormatException ignored) { }
        });
        return result;
    }

    private JsonObject decode(int id, String raw) {
        JsonObject node = new JsonObject();
        if (raw != null && !raw.isBlank()) {
            try { node = JsonParser.parseString(raw).getAsJsonObject(); }
            catch (RuntimeException ignored) { }
        }
        boolean identityMatches = number(node, "machineId", -1) == id;
        // 身份不匹配时不允许把错误节点的指标用于诊断。
        node.addProperty("machineId", id);
        long now = System.currentTimeMillis();
        long sampled = number(node, "sampledAtMillis", 0);
        boolean fresh = identityMatches && sampled > 0 && sampled <= now + 5000 && now - sampled <= 30000;
        node.addProperty("fresh", fresh);
        if (!node.has("status") || !node.get("status").isJsonPrimitive()
                || !node.get("status").getAsJsonPrimitive().isString()) node.addProperty("status", "OFFLINE");
        if (!fresh) node.addProperty("status", sampled == 0 && node.size() <= 3 ? "OFFLINE" : "STALE");
        node.addProperty("metricScope", "JDK operating-system metrics: host or container scope depends on runtime; heap/cpuUsage refer to this JVM");
        return node;
    }

    private static long number(JsonObject value, String name, long fallback) {
        try { return value.get(name).getAsLong(); } catch (RuntimeException e) { return fallback; }
    }
}
