package com.te.link.platform.mcp;

import com.te.link.platform.log.Log;
import com.te.link.platform.log.LogFactory;
import com.te.link.platform.mcp.tools.QueryProjectCodeTool;
import com.te.link.platform.mcp.tools.QueryPushLogsByLogIdTool;
import com.te.link.platform.mcp.tools.ListPushNodesTool;
import com.te.link.platform.mcp.tools.QueryNodeMetricsTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具执行只发生在独立服务中；发现和调用共享同一启用列表。 */
public class McpToolRegistry {
    private static final Log log = LogFactory.getLog(McpToolRegistry.class);
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpToolRegistry() {
        List<McpTool> available = List.of(new QueryPushLogsByLogIdTool(), new QueryProjectCodeTool(),
                new ListPushNodesTool(), new QueryNodeMetricsTool(), new com.te.link.platform.mcp.tools.SearchKnowledgeTool());
        for (McpTool tool : available) {
            if (McpServiceConfig.ENABLED_TOOLS.contains(tool.definition().name())) tools.put(tool.definition().name(), tool);
        }
        if (!tools.keySet().containsAll(McpServiceConfig.ENABLED_TOOLS)) {
            throw new IllegalArgumentException("telink.mcp.tools contains unknown tool names");
        }
    }

    public JsonObject listTools() {
        JsonArray definitions = new JsonArray();
        for (McpTool tool : tools.values()) {
            McpToolDefinition definition = tool.definition();
            JsonObject json = new JsonObject();
            json.addProperty("name", definition.name());
            json.addProperty("description", definition.description());
            json.add("inputSchema", JsonParser.parseString(definition.parametersJson()));
            definitions.add(json);
        }
        JsonObject result = new JsonObject();
        result.add("tools", definitions);
        return result;
    }

    public boolean contains(String name) { return tools.containsKey(name); }

    public JsonObject call(String name, JsonObject arguments, McpCallContext context) {
        McpTool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("unknown or disabled tool: " + name);
        long start = System.currentTimeMillis();
        log.Info(context.logContext().put("toolName", name), "MCP_TOOL_STARTED");
        try {
            ToolArgumentsValidator.validate(tool.definition(), arguments);
        } catch (IllegalArgumentException e) {
            return result(e.getMessage(), true, context);
        }
        try {
            String value = tool.execute(arguments, context);
            log.Info(context.logContext().put("toolName", name).put("durationMs", System.currentTimeMillis() - start),
                    "MCP_TOOL_COMPLETED");
            return result(value == null ? "" : value, false, context);
        } catch (IllegalArgumentException e) {
            return result(e.getMessage(), true, context);
        } catch (Exception e) {
            log.Error(context.logContext().put("toolName", name), "MCP_TOOL_FAILED", e);
            return result("tool execution failed", true, context);
        }
    }

    private JsonObject result(String text, boolean error, McpCallContext context) {
        boolean truncated = text.length() > McpServiceConfig.RESULT_MAX_LENGTH;
        if (truncated) text = text.substring(0, McpServiceConfig.RESULT_MAX_LENGTH) + "...[truncated]";
        JsonObject result = new JsonObject();
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(item);
        result.add("content", content);
        result.addProperty("isError", error);
        if (!error && !truncated) {
            try {
                if (JsonParser.parseString(text).isJsonObject()) result.add("structuredContent", JsonParser.parseString(text));
            } catch (Exception ignored) { }
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("traceId", context.traceId());
        metadata.addProperty("truncated", truncated);
        result.add("_meta", metadata);
        return result;
    }
}
