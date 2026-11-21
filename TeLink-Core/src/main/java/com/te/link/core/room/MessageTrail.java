package com.te.link.core.room;

import com.te.link.platform.log.LogContext;
import com.te.link.platform.message.PushMessageStatus;
import com.te.link.platform.message.PushMessageWriter;

/**
 * 把房间消息同步写进 push_message / push_message_node。库没配时写入器自己吞掉异常。
 *
 * @Author: Talen
 */
final class MessageTrail {
    private MessageTrail() {
    }

    static LogContext context(RoomRecord record) {
        return LogContext.create()
                .traceId(record.getLogId())
                .msgId(record.getMessageId())
                .uid(record.getUserId())
                .roomId(record.getRoomId());
    }

    static void status(RoomRecord record, PushMessageStatus status) {
        PushMessageWriter.upsert(
                record.getLogId(),
                record.getMessageId(),
                record.getUserId(),
                record.getRoomId(),
                record.getContent(),
                status
        );
    }

    static void node(RoomRecord record, int machineId, String stage) {
        if (machineId > 0 && record.getLogId() != null) {
            PushMessageWriter.recordNode(record.getLogId(), machineId, stage);
        }
    }
}
