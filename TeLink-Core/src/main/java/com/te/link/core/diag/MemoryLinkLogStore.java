package com.te.link.core.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MemoryLinkLogStore implements LinkLogStore {
    private final List<LinkLog> logs = new CopyOnWriteArrayList<>();

    @Override
    public void append(LinkLog log) {
        logs.add(log);
    }

    @Override
    public List<LinkLog> query(String logId) {
        List<LinkLog> matched = new ArrayList<>();
        for (LinkLog log : logs) {
            if (log.logId().equals(logId)) {
                matched.add(log);
            }
        }
        return matched;
    }
}
