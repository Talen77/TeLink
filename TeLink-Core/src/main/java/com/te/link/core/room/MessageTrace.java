package com.te.link.core.room;

/**
 * 房间消息轨迹。advance 在新 rank 低于当前 rank 时必须保持原状态。
 */
public interface MessageTrace {
    void advance(RoomRecord record, RoomMessageStatus status, int machineId, String detail);

    RoomMessageStatus status(String logId);
}
