package com.te.link.core.room;

import java.util.Set;

/**
 * 房间在哪些节点上。成员带租约，过期后不再被查到。
 */
public interface RoomDirectory {
    boolean createIfAbsent(long roomId, int machineId, int leaseSeconds);

    void join(long roomId, int machineId, int leaseSeconds);

    void unregister(long roomId, int machineId);

    void refresh(long roomId, int machineId, int leaseSeconds);

    Set<Integer> findNodes(long roomId);
}
