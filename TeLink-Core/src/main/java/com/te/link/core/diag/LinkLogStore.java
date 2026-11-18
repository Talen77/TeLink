package com.te.link.core.diag;

import java.util.List;

public interface LinkLogStore {
    void append(LinkLog log);

    List<LinkLog> query(String logId);

    static LinkLogStore noop() {
        return new LinkLogStore() {
            @Override
            public void append(LinkLog log) {
            }

            @Override
            public List<LinkLog> query(String logId) {
                return List.of();
            }
        };
    }
}
