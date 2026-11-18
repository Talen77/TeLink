package com.te.link.core.diag;

import com.google.gson.JsonObject;

public record ModelTurn(String content, String toolName, JsonObject arguments) {
    public static ModelTurn text(String content) {
        return new ModelTurn(content, null, null);
    }

    public static ModelTurn tool(String toolName, JsonObject arguments) {
        return new ModelTurn(null, toolName, arguments);
    }

    public boolean hasToolCall() {
        return toolName != null;
    }
}
