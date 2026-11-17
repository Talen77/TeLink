package com.te.link.core.room;

public interface RoomFanout {
    boolean deliver(int machineId, String payloadJson);
}
