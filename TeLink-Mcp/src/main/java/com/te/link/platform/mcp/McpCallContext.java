package com.te.link.platform.mcp;

import com.te.link.platform.log.LogContext;
import com.te.link.platform.utils.JsonUtil;

/** 调用元信息仅用于追踪，不依赖任何 Agent 的会话对象，也不作为身份认证依据。 */
public record McpCallContext(String requestId, String traceId, String callerAgentId) {
    public LogContext logContext() {
        return LogContext.create().traceId(traceId)
                .put("mcpRequestId", requestId).put("callerAgentId", callerAgentId);
    }

    public LogContext putPayload(LogContext context, String key, Object value) {
        String text = value instanceof String ? (String) value : JsonUtil.toJson(value);
        if (text != null && text.length() > 32000) text = text.substring(0, 32000) + "...[truncated]";
        return context.put(key, text);
    }
}
