package com.te.link.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 独立 MCP 进程。只读查询日志、房间成员和节点快照。 */
public final class McpServiceStart {
    private static final Logger log = LoggerFactory.getLogger(McpServiceStart.class);

    private McpServiceStart() {
    }

    public static void main(String[] args) throws Exception {
        log.info("starting telink mcp");
        com.te.link.platform.McpServiceStart.main(args);
    }
}
