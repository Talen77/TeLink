package com.te.link.mcp;

import com.te.link.core.diag.JdbcLinkLogStore;
import com.te.link.core.diag.NodeSnapshotStore;
import com.te.link.core.room.RedisRoomDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 独立 MCP 进程。只读查询日志、房间成员和节点快照。 */
public final class McpServiceStart {
    private static final Logger log = LoggerFactory.getLogger(McpServiceStart.class);

    private McpServiceStart() {
    }

    public static void main(String[] args) throws Exception {
        JdbcLinkLogStore logs = JdbcLinkLogStore.fromSystemProperties();
        logs.ensureTable();
        RedisRoomDirectory rooms = new RedisRoomDirectory();
        int port = Integer.getInteger("telink.mcp.port", 9091);
        McpHttpServer server = new McpHttpServer(port, new McpFacts(logs, rooms, NodeSnapshotStore.noop()));
        log.info("telink mcp started, port:{}", server.port());
        Thread.currentThread().join();
    }
}
