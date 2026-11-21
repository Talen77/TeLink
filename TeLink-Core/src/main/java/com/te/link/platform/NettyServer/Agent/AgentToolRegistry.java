package com.te.link.platform.NettyServer.Agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具注册表。
 * 通过 MCP 发现并缓存远程定义，实际执行转发至工具服务；入口决定本次请求开放的工具。
 */
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final AgentToolGateway toolGateway = new AgentToolGateway();
    private volatile boolean mcpLoaded;

    public List<AgentToolDefinition> definitions() {
        loadMcpToolsIfNecessary();
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public List<AgentToolDefinition> definitions(String... toolNames) {
        loadMcpToolsIfNecessary();
        if (toolNames == null || toolNames.length == 0) {
            return definitions();
        }
        List<AgentToolDefinition> definitions = new ArrayList<>();
        for (String toolName : toolNames) {
            AgentTool tool = tools.get(toolName);
            if (tool == null) {
                throw new IllegalArgumentException("agent tool is unavailable:" + toolName);
            }
            definitions.add(tool.definition());
        }
        return definitions;
    }

    public String execute(AgentToolCall toolCall, AgentToolRequest request) {
        loadMcpToolsIfNecessary();
        AgentTool tool = tools.get(toolCall.name());
        if (tool == null) {
            throw new IllegalArgumentException("agent tool is unavailable:" + toolCall.name());
        }
        return toolGateway.invoke(tool, toolCall, request);
    }

    private synchronized void loadMcpToolsIfNecessary() {
        if (mcpLoaded) {
            return;
        }
        // 主工具服务发现失败时保留未加载状态，下一次请求可重试。
        tools.putAll(new McpToolProvider().loadTools());
        if (com.te.link.platform.operations.OperationConfig.ENABLED) tools.put(ProposeNodeOperationTool.NAME, new ProposeNodeOperationTool());
        mcpLoaded = true;
    }
}
