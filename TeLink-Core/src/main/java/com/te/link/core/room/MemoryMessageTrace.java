package com.te.link.core.room;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MemoryMessageTrace implements MessageTrace {
    private final ConcurrentMap<String, RoomMessageStatus> statuses = new ConcurrentHashMap<>();

    @Override
    public void advance(RoomRecord record, RoomMessageStatus status, int machineId, String detail) {
        statuses.compute(record.getLogId(), (logId, current) -> {
            if (current != null && status.rank() < current.rank()) {
                return current;
            }
            return status;
        });
    }

    @Override
    public RoomMessageStatus status(String logId) {
        return statuses.get(logId);
    }
}
