package com.te.link.core.room;

import com.te.link.core.diag.LinkLog;
import com.te.link.core.diag.LinkLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 消费 message_out 的节点负责路由。远程节点只做本机广播。
 */
public class RoomMessageRouter {
    private static final Logger log = LoggerFactory.getLogger(RoomMessageRouter.class);

    private final int machineId;
    private final RoomDirectory directory;
    private final MessageTrace trace;
    private final RoomFanout fanout;
    private final LocalRoomBroadcaster localBroadcaster;
    private LinkLogStore logs = LinkLogStore.noop();

    public RoomMessageRouter(
            int machineId,
            RoomDirectory directory,
            MessageTrace trace,
            RoomFanout fanout,
            LocalRoomBroadcaster localBroadcaster
    ) {
        this.machineId = machineId;
        this.directory = directory;
        this.trace = trace;
        this.fanout = fanout;
        this.localBroadcaster = localBroadcaster;
    }

    public void attachLogs(LinkLogStore logs) {
        this.logs = logs == null ? LinkLogStore.noop() : logs;
    }

    public void onDownstream(String key, String payload) {
        RoomRecord record = com.te.link.common.utils.JsonUtil.fromJson(payload, RoomRecord.class);
        Set<Integer> nodes = directory.findNodes(record.getRoomId());
        if (nodes.isEmpty()) {
            trace.advance(record, RoomMessageStatus.FAILED, machineId, "no-route");
            fail(record, "no-route");
            log.info("room route missing, roomId:{}, logId:{}", record.getRoomId(), record.getLogId());
            return;
        }
        trace.advance(record, RoomMessageStatus.ROUTING, machineId, "routing");
        boolean delivered = false;
        for (Integer target : nodes) {
            if (target == machineId) {
                delivered |= localBroadcaster.broadcast(record) > 0;
            } else {
                delivered |= fanout.deliver(target, payload);
            }
        }
        if (delivered) {
            trace.advance(record, RoomMessageStatus.DELIVERED, machineId, "delivered");
        } else {
            trace.advance(record, RoomMessageStatus.FAILED, machineId, "fanout-failed");
            fail(record, "fanout-failed");
        }
    }

    private void fail(RoomRecord record, String reason) {
        logs.append(new LinkLog(record.getLogId(), "ERROR", machineId, "room route failed", reason));
    }

    public interface LocalRoomBroadcaster {
        int broadcast(RoomRecord record);
    }
}
