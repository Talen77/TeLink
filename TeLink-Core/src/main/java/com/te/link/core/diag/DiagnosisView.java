package com.te.link.core.diag;

import com.google.gson.JsonObject;

/** 页面只渲染这一份 JSON。失败时不能带上日志、指标或其他字段。 */
public record DiagnosisView(String text) {
    public static DiagnosisView failed() {
        return new DiagnosisView(ReactRunner.FAILURE);
    }

    public static DiagnosisView ok(String text) {
        if (text == null || text.isBlank() || ReactRunner.FAILURE.equals(text)) {
            return failed();
        }
        return new DiagnosisView(text);
    }

    public JsonObject toJson() {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        return body;
    }
}
