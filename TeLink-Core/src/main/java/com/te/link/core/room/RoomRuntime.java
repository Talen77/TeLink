package com.te.link.core.room;

import com.te.link.core.config.LinkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生产环境的房间链路。验收测试不走这里，而是直接组装内存实现。
 */
public final class RoomRuntime {
    private static final Logger log = LoggerFactory.getLogger(RoomRuntime.class);
    private static RoomNode node;
    private static RedisRoomDirectory directory;
    private static KafkaMessageBus bus;
    private static GrpcRoomFanout fanout;

    private RoomRuntime() {
    }

    public static synchronized RoomNode startWebSocket(int machineId) {
        if (node != null) {
            return node;
        }
        JdbcMessageTrace trace = JdbcMessageTrace.fromSystemProperties();
        trace.ensureTable();
        directory = new RedisRoomDirectory();
        bus = KafkaMessageBus.fromSystemProperties();
        fanout = GrpcRoomFanout.nacos();
        node = new RoomNode(machineId, directory, trace, bus, fanout);
        node.startWebSocket(RoomFeature.webSocketPort());
        LinkConfig.MACHINE_ID = machineId;
        log.info("room runtime started, machineId:{}", machineId);
        return node;
    }

    public static synchronized void close() {
        if (node != null) {
            node.close();
            node = null;
        }
        if (fanout != null) {
            fanout.close();
            fanout = null;
        }
        if (bus != null) {
            bus.close();
            bus = null;
        }
        if (directory != null) {
            directory.close();
            directory = null;
        }
    }
}
