package com.te.link.core.room;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MemoryRoomDirectory implements RoomDirectory {
    private final Map<Long, Map<Integer, Long>> roomNodes = new HashMap<>();
    private long nowMillis = System.currentTimeMillis();

    public synchronized void setNowMillis(long nowMillis) {
        this.nowMillis = nowMillis;
    }

    @Override
    public synchronized boolean createIfAbsent(long roomId, int machineId, int leaseSeconds) {
        purge(roomId);
        Map<Integer, Long> nodes = roomNodes.get(roomId);
        if (nodes != null && !nodes.isEmpty()) {
            return false;
        }
        put(roomId, machineId, leaseSeconds);
        return true;
    }

    @Override
    public synchronized void join(long roomId, int machineId, int leaseSeconds) {
        purge(roomId);
        put(roomId, machineId, leaseSeconds);
    }

    @Override
    public synchronized void unregister(long roomId, int machineId) {
        Map<Integer, Long> nodes = roomNodes.get(roomId);
        if (nodes == null) {
            return;
        }
        nodes.remove(machineId);
        if (nodes.isEmpty()) {
            roomNodes.remove(roomId);
        }
    }

    @Override
    public synchronized void refresh(long roomId, int machineId, int leaseSeconds) {
        Map<Integer, Long> nodes = roomNodes.get(roomId);
        if (nodes == null || !nodes.containsKey(machineId)) {
            return;
        }
        nodes.put(machineId, nowMillis + leaseSeconds * 1000L);
    }

    @Override
    public synchronized Set<Integer> findNodes(long roomId) {
        purge(roomId);
        Map<Integer, Long> nodes = roomNodes.get(roomId);
        if (nodes == null) {
            return Set.of();
        }
        return Set.copyOf(nodes.keySet());
    }

    private void put(long roomId, int machineId, int leaseSeconds) {
        roomNodes.computeIfAbsent(roomId, ignored -> new HashMap<>())
                .put(machineId, nowMillis + leaseSeconds * 1000L);
    }

    private void purge(long roomId) {
        Map<Integer, Long> nodes = roomNodes.get(roomId);
        if (nodes == null) {
            return;
        }
        nodes.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
        if (nodes.isEmpty()) {
            roomNodes.remove(roomId);
        }
    }

    synchronized Set<Long> rooms() {
        Set<Long> ids = new HashSet<>();
        for (Long roomId : Set.copyOf(roomNodes.keySet())) {
            purge(roomId);
            if (roomNodes.containsKey(roomId)) {
                ids.add(roomId);
            }
        }
        return ids;
    }
}
