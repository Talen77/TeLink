package com.te.link.platform.NettyServer.Agent;

import com.google.gson.JsonObject;

/**
 * 将远端 MCP 工具包装成本地 AgentTool。
 * 主服务保留协议工具名；附加服务使用 mcp_{server}_ 前缀避免重名。
 */
public class McpAgentTool implements AgentTool {
    private final McpRemoteTool remoteTool;
    private final McpJsonRpcClient client;

    public McpAgentTool(McpRemoteTool remoteTool, McpJsonRpcClient client) {
        this.remoteTool = remoteTool;
        this.client = client;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                remoteTool.exposedName(),
                "[MCP:" + remoteTool.serverConfig().name() + "] " + remoteTool.description(),
                remoteTool.parametersJson()
        );
    }

    @Override
    public String execute(JsonObject arguments, AgentToolRequest request) {
        if (AgentToolNames.QUERY_NODE.equals(remoteTool.remoteName()) && request != null && request.machineId() != null) {
            if (arguments == null || !arguments.has("machineId")
                    || !arguments.get("machineId").isJsonPrimitive()
                    || !arguments.get("machineId").getAsJsonPrimitive().isNumber()
                    || arguments.get("machineId").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(request.machineId())) != 0) {
                throw new IllegalArgumentException("tool machineId does not match assigned node");
            }
        }
        if (AgentToolNames.QUERY_LOGS.equals(remoteTool.remoteName()) && request != null
                && request.requestedLogId() != null && !request.requestedLogId().isBlank()) {
            if (arguments == null || !arguments.has("logId") || !arguments.get("logId").isJsonPrimitive()
                    || !arguments.get("logId").getAsJsonPrimitive().isString()
                    || !request.requestedLogId().equals(arguments.get("logId").getAsString())) {
                throw new IllegalArgumentException("tool logId does not match requested logId");
            }
        }
        String result;
        try { result = client.callTool(
                remoteTool,
                arguments,
                request == null ? null : request.traceContext()
        ); } catch (RuntimeException e) {
            if (!AgentToolNames.SEARCH_KNOWLEDGE.equals(remoteTool.remoteName())) throw e;
            // 知识检索是可选证据；网络超时也应回填观察结果，让模型继续判断。
            return "{\"status\":\"unavailable\",\"matches\":[],\"message\":\"知识检索失败，请说明缺少知识依据，并基于真实数据继续分析。\"}";
        }
        if (AgentToolNames.SEARCH_KNOWLEDGE.equals(remoteTool.remoteName()) && request != null) request.knowledge().record(result);
        if (request != null) request.operations().observe(remoteTool.remoteName(), result);
        return result;
    }
}
