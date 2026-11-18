package com.te.link.core.diag;

public record NodeSnapshot(
        int machineId,
        boolean online,
        int connectionCount,
        long heapUsed,
        long sampledAtMillis,
        String detail
) {
    public boolean fresh(long nowMillis) {
        return online && nowMillis - sampledAtMillis <= 30_000;
    }
}
