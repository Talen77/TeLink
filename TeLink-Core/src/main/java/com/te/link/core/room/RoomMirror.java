package com.te.link.core.room;

import com.te.link.platform.redis.RedisClient;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把房间成员同步到管理端使用的 Redis 键。失败只影响管理端视图，不能挡住收发。
 *
 * @Author: Talen
 */
final class RoomMirror {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telink-room-mirror");
        thread.setDaemon(true);
        return thread;
    });

    private RoomMirror() {
    }

    static void remember(int machineId, long roomId, Set<Long> rooms) {
        Set<Long> copy = Set.copyOf(rooms);
        EXECUTOR.execute(() -> {
            try {
                RedisClient.registerRoomNode(roomId, machineId, RoomFeature.LEASE_SECONDS);
                RedisClient.refreshPushNodeRooms(machineId, copy);
            } catch (RuntimeException ignored) {
                // 管理端房间列表稍后重试，本机广播不依赖这次写入。
            }
        });
    }

    static void forget(int machineId, long roomId, Set<Long> rooms) {
        Set<Long> copy = Set.copyOf(rooms);
        EXECUTOR.execute(() -> {
            try {
                RedisClient.unregisterRoomNode(roomId, machineId);
                RedisClient.refreshPushNodeRooms(machineId, copy);
            } catch (RuntimeException ignored) {
                // 同上。
            }
        });
    }

    static void refresh(int machineId, Set<Long> rooms) {
        Set<Long> copy = Set.copyOf(rooms);
        EXECUTOR.execute(() -> {
            try {
                for (Long roomId : copy) {
                    RedisClient.registerRoomNode(roomId, machineId, RoomFeature.LEASE_SECONDS);
                }
                RedisClient.refreshPushNodeRooms(machineId, copy);
            } catch (RuntimeException ignored) {
                // 租约续期失败留到下一轮。
            }
        });
    }
}
