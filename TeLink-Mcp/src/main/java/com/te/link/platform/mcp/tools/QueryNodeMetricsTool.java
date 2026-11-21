package com.te.link.platform.mcp.tools;

import com.te.link.platform.mcp.*;
import com.te.link.platform.monitor.ClusterNodeRepository;
import com.google.gson.JsonObject;

public class QueryNodeMetricsTool implements McpTool {
    public static final String NAME = "query_node_metrics";
    public McpToolDefinition definition() {
        return new McpToolDefinition(NAME, "读取指定节点自身上报的 CPU、服务器内存、JVM 堆、最近一分钟业务消息数及采样时间；fresh=false 不可作为当前状态或运维依据。",
                "{\"type\":\"object\",\"properties\":{\"machineId\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647}},\"required\":[\"machineId\"]}");
    }
    public String execute(JsonObject arguments, McpCallContext context) {
        return new ClusterNodeRepository().get(arguments.get("machineId").getAsInt()).toString();
    }
}
