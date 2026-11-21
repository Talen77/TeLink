package com.te.link.platform;

import com.te.link.platform.mcp.McpHttpServer;
import com.te.link.platform.mcp.McpServiceConfig;

/** 独立 MCP 微服务入口，不启动推送节点或模型。 */
public class McpServiceStart {
    public static void main(String[] args) throws Exception {
        McpHttpServer server = new McpHttpServer(McpServiceConfig.HOST, McpServiceConfig.PORT);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "mcp-service-shutdown"));
        server.start();
        System.out.println("MCP tool service listening on " + McpServiceConfig.HOST + ":" + server.port());
    }
}
