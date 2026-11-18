package com.te.link.core.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MemoryNodeSnapshotStore implements NodeSnapshotStore {
    private final ConcurrentMap<Integer, NodeSnapshot> snapshots = new ConcurrentHashMap<>();
    private final List<Integer> reads = new CopyOnWriteArrayList<>();

    @Override
    public void save(NodeSnapshot snapshot) {
        snapshots.put(snapshot.machineId(), snapshot);
    }

    @Override
    public NodeSnapshot find(int machineId) {
        reads.add(machineId);
        return snapshots.get(machineId);
    }

    @Override
    public List<NodeSnapshot> list() {
        return new ArrayList<>(snapshots.values());
    }

    public List<Integer> reads() {
        return List.copyOf(reads);
    }
}
