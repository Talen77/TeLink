package com.te.link.core.room;

import com.te.link.core.config.RedisConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RedisRoomDirectory implements RoomDirectory, AutoCloseable {
    private static final String CREATE_IF_ABSENT = """
            local members = redis.call('SMEMBERS', KEYS[1])
            for _, member in ipairs(members) do
              if redis.call('EXISTS', ARGV[3] .. member) == 1 then
                return 0
              else
                redis.call('SREM', KEYS[1], member)
              end
            end
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('SET', ARGV[3] .. ARGV[1], '1', 'EX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[4])
            return 1
            """;

    private final JedisPool pool;

    public RedisRoomDirectory() {
        this(RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT, RedisConfig.REDIS_PASSWORD);
    }

    public RedisRoomDirectory(String host, int port, String password) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        config.setMaxWait(Duration.ofMillis(500));
        if (password == null || password.isBlank()) {
            this.pool = new JedisPool(config, host, port, 2000);
        } else {
            this.pool = new JedisPool(config, host, port, 2000, password);
        }
    }

    @Override
    public boolean createIfAbsent(long roomId, int machineId, int leaseSeconds) {
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(
                    CREATE_IF_ABSENT,
                    List.of(nodesKey(roomId), nodeRoomsKey(machineId)),
                    List.of(String.valueOf(machineId), String.valueOf(leaseSeconds), leasePrefix(roomId), String.valueOf(roomId))
            );
            return Long.valueOf(1L).equals(result);
        }
    }

    @Override
    public void join(long roomId, int machineId, int leaseSeconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.sadd(nodesKey(roomId), String.valueOf(machineId));
            jedis.set(leaseKey(roomId, machineId), "1", SetParams.setParams().ex(leaseSeconds));
            jedis.sadd(nodeRoomsKey(machineId), String.valueOf(roomId));
        }
    }

    @Override
    public void unregister(long roomId, int machineId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(leaseKey(roomId, machineId));
            jedis.srem(nodesKey(roomId), String.valueOf(machineId));
            jedis.srem(nodeRoomsKey(machineId), String.valueOf(roomId));
        }
    }

    @Override
    public void refresh(long roomId, int machineId, int leaseSeconds) {
        try (Jedis jedis = pool.getResource()) {
            if (jedis.expire(leaseKey(roomId, machineId), leaseSeconds) == 0) {
                join(roomId, machineId, leaseSeconds);
            }
        }
    }

    @Override
    public Set<Integer> findNodes(long roomId) {
        try (Jedis jedis = pool.getResource()) {
            Set<String> members = jedis.smembers(nodesKey(roomId));
            Set<Integer> alive = new HashSet<>();
            for (String member : members) {
                if (jedis.exists(leasePrefix(roomId) + member)) {
                    alive.add(Integer.valueOf(member));
                } else {
                    jedis.srem(nodesKey(roomId), member);
                }
            }
            return alive;
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    static String nodesKey(long roomId) {
        return "telink:room:" + roomId + ":nodes";
    }

    static String leaseKey(long roomId, int machineId) {
        return leasePrefix(roomId) + machineId;
    }

    static String leasePrefix(long roomId) {
        return "telink:room:" + roomId + ":node:";
    }

    static String nodeRoomsKey(int machineId) {
        return "telink:node:" + machineId + ":rooms";
    }
}
