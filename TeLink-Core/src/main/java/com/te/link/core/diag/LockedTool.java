package com.te.link.core.diag;

import com.google.gson.JsonObject;

/** 在访问事实之前拦住不属于本子 Agent 的 machineId。 */
public final class LockedTool implements DiagTool {
    private final DiagTool delegate;
    private final int machineId;

    public LockedTool(DiagTool delegate, int machineId) {
        this.delegate = delegate;
        this.machineId = machineId;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String call(JsonObject arguments) {
        if (arguments != null && arguments.has("machineId") && arguments.get("machineId").getAsInt() != machineId) {
            throw new ToolRejectedException("machineId is outside this agent");
        }
        return delegate.call(arguments);
    }
}
