package com.te.link.platform.NettyServer.Monitor;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** 最近 60 个秒桶中的业务消息数，使用单调时钟避免系统校时影响窗口。 */
public final class RecentMessageCounter {
    private final long[] seconds = new long[60];
    private final long[] counts = new long[60];
    private final LongSupplier clock;

    public RecentMessageCounter() { this(() -> System.nanoTime() / 1_000_000_000L); }

    RecentMessageCounter(LongSupplier clock) {
        this.clock = clock;
        Arrays.fill(seconds, Long.MIN_VALUE);
    }

    public synchronized void increment() {
        long now = clock.getAsLong();
        int slot = Math.floorMod(now, 60);
        if (seconds[slot] != now) { seconds[slot] = now; counts[slot] = 0; }
        counts[slot]++;
    }

    public synchronized long count() {
        long now = clock.getAsLong();
        long sum = 0;
        for (int i = 0; i < 60; i++) {
            if (seconds[i] != Long.MIN_VALUE && seconds[i] <= now && now - seconds[i] < 60) sum += counts[i];
        }
        return sum;
    }
}
