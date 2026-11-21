package com.te.link.platform.mcp.tools;

import com.te.link.platform.mcp.*;
import com.te.link.platform.monitor.ClusterNodeRepository;
import com.google.gson.JsonObject;

public class ListPushNodesTool implements McpTool {
    public static final String NAME = "list_push_nodes";
    public McpToolDefinition definition() {
        return new McpToolDefinition(NAME, "发现注册节点及在线节点个数；只含节点标识和状态，详细指标使用 query_node_metrics。",
                "{\"type\":\"object\",\"properties\":{}}");
    }
    public String execute(JsonObject arguments, McpCallContext context) {
        return new ClusterNodeRepository().list().toString();
    }
}
