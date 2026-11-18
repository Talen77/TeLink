package com.te.link.mcp;

import com.te.link.core.diag.LinkLogStore;
import com.te.link.core.diag.NodeSnapshotStore;
import com.te.link.core.room.RoomDirectory;

public record McpFacts(LinkLogStore logs, RoomDirectory rooms, NodeSnapshotStore nodes) {
}
