package com.te.link.platform.mcp;

import com.google.gson.JsonObject;

/** 所有工具共享执行接口；业务参数结构由各自的 inputSchema 描述。 */
public interface McpTool {
    McpToolDefinition definition();
    String execute(JsonObject arguments, McpCallContext context);
}
