package com.te.link.core.diag;

import java.util.List;

public interface NodeSnapshotStore {
    void save(NodeSnapshot snapshot);

    NodeSnapshot find(int machineId);

    List<NodeSnapshot> list();

    static NodeSnapshotStore noop() {
        return new NodeSnapshotStore() {
            @Override
            public void save(NodeSnapshot snapshot) {
            }

            @Override
            public NodeSnapshot find(int machineId) {
                return null;
            }

            @Override
            public List<NodeSnapshot> list() {
                return List.of();
            }
        };
    }
}
