package com.te.link.platform.redis;

import com.te.link.platform.constant.LinkConfigConstant;
import redis.clients.jedis.*;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * redis操作客户端
 */
public class RedisClient {
    private static final Integer REDIS_TIMEOUT = 2000;
    private static final String ROOM_NODE_SET_PREFIX = LinkConfigConstant.REDISKEY_PERFER + "Room_Nodes:";
    private static final String ROOM_NODE_LEASE_PREFIX = LinkConfigConstant.REDISKEY_PERFER + "Room_Node_Lease:";
    private static final String NODE_DELIVERY_CHANNEL_PREFIX = LinkConfigConstant.REDISKEY_PERFER + "Node_Delivery:";
    private static final String PUSH_NODE_REGISTRY_KEY = LinkConfigConstant.REDISKEY_PERFER + "Push_Node_Registry";
    private static final String PUSH_NODE_SNAPSHOT_PREFIX = LinkConfigConstant.REDISKEY_PERFER + "Push_Node_Snapshot:";
    private static final String PUSH_NODE_ROOM_HASH_KEY = LinkConfigConstant.REDISKEY_PERFER + "Push_Node_Rooms";

    private static volatile JedisPool jedisPool;

    /**
     * 第一次真正访问 Redis 时才建连接，避免测试加载类就连远程实例。
     */
    private static JedisPool pool() {
        JedisPool existing = jedisPool;
        if (existing != null) {
            return existing;
        }
        synchronized (RedisClient.class) {
            if (jedisPool == null) {
                JedisPoolConfig config = new JedisPoolConfig();
                config.setMaxTotal(500);
                config.setMaxIdle(250);
                config.setMinIdle(50);
                config.setMaxWait(Duration.ofMillis(500));
                config.setTestOnBorrow(true);
                String password = RedisConfig.REDIS_PASSWORD;
                JedisPool created = password == null || password.isBlank()
                        ? new JedisPool(config, RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT, REDIS_TIMEOUT)
                        : new JedisPool(config, RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT, REDIS_TIMEOUT, password);
                try (Jedis jedis = created.getResource()) {
                    jedis.setnx(getServerIDKey(), "0");
                }
                jedisPool = created;
            }
            return jedisPool;
        }
    }

    /**
     * 获取当前机器的serverid
     * @return
     */
    public static Integer initRedisServerId(){
        try(Jedis jedis = pool().getResource()){
            Long incr = jedis.incr(getServerIDKey());
            return incr.intValue();
        }
    }

    /**
     * 获取redis的key
     * @return
     */
    public static String getServerIDKey(){
        return LinkConfigConstant.REDISKEY_SERVERID;
    }

    /**
     * 执行 Redis Lua 脚本。
     * Agent 模型路由通过该接口原子地完成租约回收、熔断校验和并发槽位抢占。
     */
    public static Object eval(String script, List<String> keys, List<String> args) {
        try (Jedis jedis = pool().getResource()) {
            return jedis.eval(script, keys, args);
        }
    }

    /**
     * 读取 Hash 全量字段。
     * 用于读取模型实时并发、熔断状态以及分钟桶统计。
     */
    public static Map<String, String> hgetAll(String key) {
        try (Jedis jedis = pool().getResource()) {
            return jedis.hgetAll(key);
        }
    }

    /**
     * 写入 Hash 字段集合。
     * Agent 会话元信息使用 Hash 保存，避免每轮对话重写整个会话 JSON。
     */
    public static void hmset(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool().getResource()) {
            jedis.hmset(key, values);
        }
    }

    /**
     * 删除 Hash 中的指定字段。
     * 机器退出房间后需要把机器 -> 房间列表映射同步删掉，避免前端读取到过期房间。
     */
    public static void hdel(String key, String field) {
        if (field == null || field.isBlank()) {
            return;
        }
        try (Jedis jedis = pool().getResource()) {
            jedis.hdel(key, field);
        }
    }

    /**
     * 读取普通字符串值。
     * Agent scope 索引用于从固定分析范围快速定位当前会话。
     */
    public static String get(String key) {
        try (Jedis jedis = pool().getResource()) {
            return jedis.get(key);
        }
    }

    /**
     * 写入带过期时间的普通字符串值。
     */
    public static void setex(String key, int seconds, String value) {
        try (Jedis jedis = pool().getResource()) {
            jedis.setex(key, (long) seconds, value);
        }
    }

    /**
     * 向 List 尾部追加元素，保证会话轮次按时间正序读取。
     */
    public static void rpush(String key, String value) {
        try (Jedis jedis = pool().getResource()) {
            jedis.rpush(key, value);
        }
    }

    /**
     * 读取 List 指定区间。
     */
    public static List<String> lrange(String key, long start, long end) {
        try (Jedis jedis = pool().getResource()) {
            return jedis.lrange(key, start, end);
        }
    }

    /**
     * 获取 List 当前长度。
     */
    public static long llen(String key) {
        try (Jedis jedis = pool().getResource()) {
            return jedis.llen(key);
        }
    }

    /**
     * 裁剪 List，Agent 会话仅保留最近固定轮数的原始对话。
     */
    public static void ltrim(String key, long start, long end) {
        try (Jedis jedis = pool().getResource()) {
            jedis.ltrim(key, start, end);
        }
    }

    /**
     * 刷新 key 的存活时间。
     */
    public static void expire(String key, int seconds) {
        try (Jedis jedis = pool().getResource()) {
            jedis.expire(key, (long) seconds);
        }
    }

    /**
     * 尝试抢占带过期时间的分布式锁。
     * 返回 true 表示当前调用方取得锁，锁值必须用于后续安全释放。
     */
    public static boolean tryLock(String key, String lockValue, long ttlMillis) {
        try (Jedis jedis = pool().getResource()) {
            String result = jedis.set(
                    key,
                    lockValue,
                    SetParams.setParams().nx().px(Math.max(ttlMillis, 1L))
            );
            return "OK".equals(result);
        }
    }

    /**
     * 仅释放当前调用方持有的锁，避免锁超时后误删其他请求的新锁。
     */
    public static void unlock(String key, String lockValue) {
        String script = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                end
                return 0
                """;
        eval(script, List.of(key), List.of(lockValue));
    }

    /**
     * 为本机持有连接的房间注册节点成员并刷新租约。
     * Set 保存候选节点，单独 lease key 用于过滤异常退出节点遗留的成员。
     */
    public static void registerRoomNode(long roomId, int machineId, int leaseSeconds) {
        String roomNodeSetKey = roomNodeSetKey(roomId);
        String member = String.valueOf(machineId);
        try (Jedis jedis = pool().getResource()) {
            jedis.sadd(roomNodeSetKey, member);
            jedis.expire(roomNodeSetKey, (long) Math.max(leaseSeconds * 2, leaseSeconds));
            jedis.setex(roomNodeLeaseKey(roomId, machineId), (long) leaseSeconds, "1");
        }
    }

    /**
     * 查询持有指定房间连接的存活节点，同时清理已过期租约对应的集合成员。
     */
    public static Set<Integer> findActiveRoomNodes(long roomId) {
        try (Jedis jedis = pool().getResource()) {
            Set<Integer> activeNodes = new HashSet<>();
            for (String member : jedis.smembers(roomNodeSetKey(roomId))) {
                try {
                    int machineId = Integer.parseInt(member);
                    if (jedis.exists(roomNodeLeaseKey(roomId, machineId))) {
                        activeNodes.add(machineId);
                    } else {
                        jedis.srem(roomNodeSetKey(roomId), member);
                    }
                } catch (NumberFormatException e) {
                    jedis.srem(roomNodeSetKey(roomId), member);
                }
            }
            return activeNodes;
        }
    }

    /**
     * 本机不再持有该房间连接时删除本机成员与租约，不影响其他节点的房间成员。
     */
    public static void unregisterRoomNode(long roomId, int machineId) {
        try (Jedis jedis = pool().getResource()) {
            jedis.srem(roomNodeSetKey(roomId), String.valueOf(machineId));
            jedis.del(roomNodeLeaseKey(roomId, machineId));
            if (jedis.scard(roomNodeSetKey(roomId)) == 0) {
                jedis.del(roomNodeSetKey(roomId));
            }
        }
    }

    /**
     * 将已由 message_out 消费节点判定目标机器的下行消息发布给指定推送节点。
     */
    public static long publishNodeDelivery(int machineId, String payload) {
        try (Jedis jedis = pool().getResource()) {
            return jedis.publish(nodeDeliveryChannel(machineId), payload);
        }
    }

    /**
     * 阻塞订阅本机专属投递频道。调用方应在线程中执行，并通过 JedisPubSub#unsubscribe 停止。
     */
    public static void subscribeNodeDelivery(int machineId, JedisPubSub subscriber) {
        try (Jedis jedis = pool().getResource()) {
            jedis.subscribe(subscriber, nodeDeliveryChannel(machineId));
        }
    }

    /**
     * 注册或刷新推送节点快照。
     * registry 保存出现过的 machineId，snapshot 使用 TTL 表达节点存活租约。
     */
    public static void refreshPushNodeSnapshot(int machineId, String snapshotJson, int ttlSeconds) {
        String member = String.valueOf(machineId);
        try (Jedis jedis = pool().getResource()) {
            jedis.sadd(PUSH_NODE_REGISTRY_KEY, member);
            jedis.setex(pushNodeSnapshotKey(machineId), (long) ttlSeconds, snapshotJson);
        }
    }

    /**
     * 查询所有注册过的推送节点快照。
     * 返回 Map 的 key 是 machineId 字符串；value 为空表示快照租约已过期，节点应视为离线。
     */
    public static Map<String, String> listPushNodeSnapshots() {
        try (Jedis jedis = pool().getResource()) {
            Set<String> members = jedis.smembers(PUSH_NODE_REGISTRY_KEY);
            if (members == null || members.isEmpty()) {
                return Map.of();
            }
            Map<String, String> snapshots = new java.util.LinkedHashMap<>();
            for (String member : members) {
                snapshots.put(member, jedis.get(pushNodeSnapshotKey(member)));
            }
            return snapshots;
        }
    }

    /** 快照租约已过期时，从注册集合去掉这个 machineId，避免页面和诊断一直把它当成离线节点。 */
    public static void forgetPushNode(int machineId) {
        try (Jedis jedis = pool().getResource()) {
            jedis.srem(PUSH_NODE_REGISTRY_KEY, String.valueOf(machineId));
        }
    }

    /**
     * 使用 Hash 保存当前活跃推送节点持有的房间列表。
     * field 为 machineId，value 为 JSON 数组字符串，便于前端按活跃节点聚合可加入房间。
     */
    public static void refreshPushNodeRooms(int machineId, Set<Long> roomIds) {
        String field = String.valueOf(machineId);
        if (roomIds == null || roomIds.isEmpty()) {
            hdel(PUSH_NODE_ROOM_HASH_KEY, field);
            return;
        }
        Map<String, String> mapping = new java.util.HashMap<>();
        mapping.put(field, com.te.link.platform.utils.JsonUtil.toJson(roomIds));
        hmset(PUSH_NODE_ROOM_HASH_KEY, mapping);
    }

    /**
     * 读取所有推送节点持有的房间映射。
     * 管理后台会结合活跃节点快照过滤掉离线节点的房间信息。
     */
    public static Map<String, String> listPushNodeRooms() {
        return hgetAll(PUSH_NODE_ROOM_HASH_KEY);
    }

    /** 查询指定节点仍持有有效路由的房间；批量检查租约，避免逐房间网络往返。 */
    public static List<String> listActivePushNodeRooms(int machineId) {
        try (Jedis jedis = pool().getResource()) {
            String raw = jedis.hget(PUSH_NODE_ROOM_HASH_KEY, String.valueOf(machineId));
            if (raw == null || raw.isBlank()) return List.of();
            Set<Long> roomIds = new java.util.TreeSet<>();
            for (com.google.gson.JsonElement item : com.google.gson.JsonParser.parseString(raw).getAsJsonArray()) {
                if (item == null || item.isJsonNull()) continue;
                long id = item.getAsBigDecimal().longValueExact();
                if (id > 0) roomIds.add(id);
            }
            List<Long> ordered = List.copyOf(roomIds);
            List<Response<Boolean>> members = new java.util.ArrayList<>();
            List<Response<Boolean>> leases = new java.util.ArrayList<>();
            try (Pipeline pipeline = jedis.pipelined()) {
                for (Long roomId : ordered) {
                    members.add(pipeline.sismember(roomNodeSetKey(roomId), String.valueOf(machineId)));
                    leases.add(pipeline.exists(roomNodeLeaseKey(roomId, machineId)));
                }
                pipeline.sync();
            }
            List<String> active = new java.util.ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
                // 用字符串传输 Long，避免浏览器对大房间 ID 的整数精度丢失。
                if (members.get(i).get() && leases.get(i).get()) active.add(ordered.get(i).toString());
            }
            return active;
        }
    }

    /**
     * 节点优雅停止时删除存活租约，但保留 registry，便于管理后台展示离线节点。
     */
    public static void unregisterPushNode(int machineId) {
        try (Jedis jedis = pool().getResource()) {
            jedis.del(pushNodeSnapshotKey(machineId));
            jedis.hdel(PUSH_NODE_ROOM_HASH_KEY, String.valueOf(machineId));
        }
    }

    private static String roomNodeSetKey(long roomId) {
        return ROOM_NODE_SET_PREFIX + roomId;
    }

    private static String roomNodeLeaseKey(long roomId, int machineId) {
        return ROOM_NODE_LEASE_PREFIX + roomId + ":" + machineId;
    }

    private static String nodeDeliveryChannel(int machineId) {
        return NODE_DELIVERY_CHANNEL_PREFIX + machineId;
    }

    private static String pushNodeSnapshotKey(int machineId) {
        return pushNodeSnapshotKey(String.valueOf(machineId));
    }

    private static String pushNodeSnapshotKey(String machineId) {
        return PUSH_NODE_SNAPSHOT_PREFIX + machineId;
    }
}
