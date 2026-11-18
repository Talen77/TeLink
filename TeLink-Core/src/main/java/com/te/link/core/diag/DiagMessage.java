package com.te.link.core.diag;

public record DiagMessage(String role, String content, String toolName) {
    public static DiagMessage user(String content) {
        return new DiagMessage("user", content, null);
    }

    public static DiagMessage tool(String toolName, String content) {
        return new DiagMessage("tool", content, toolName);
    }
}
