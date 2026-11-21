package com.te.link.platform.operations;

import com.te.link.platform.knowledge.KnowledgeSettings;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 审核配置来自服务端；模型和审批请求不能指定执行命令。 */
public final class OperationConfig {
    public static final boolean ENABLED = Boolean.parseBoolean(read("telink.agent.operations.enabled", "false"));
    public static final String REVIEW_KEY = read("telink.agent.operations.reviewKey", "");
    public static final int APPROVAL_SECONDS = number("telink.agent.operations.approvalSeconds", 600, 60, 3600);
    public static final int EXECUTION_SECONDS = number("telink.agent.operations.executionSeconds", 120, 30, 600);
    public static final int COOLDOWN_SECONDS = number("telink.agent.operations.cooldownSeconds", 300, 30, 3600);
    public static final String DEPLOYMENT_ID = read("telink.node.deploymentId", "");
    public static final List<String> RESTART_COMMAND = command();
    public static final String EXECUTOR_FINGERPRINT = hash(ENABLED + "|" + DEPLOYMENT_ID + "|" + new Gson().toJson(RESTART_COMMAND));
    private OperationConfig() { }
    private static String read(String key, String fallback) {
        String env = key.replaceAll("([a-z])([A-Z])", "$1_$2").replace('.', '_').toUpperCase(Locale.ROOT);
        return KnowledgeSettings.value(key, env, fallback);
    }
    private static int number(String key, int fallback, int min, int max) {
        int result = Integer.parseInt(read(key, String.valueOf(fallback)));
        if (result < min || result > max) throw new IllegalArgumentException("invalid " + key);
        return result;
    }
    private static List<String> command() {
        String raw = read("telink.node.operations.restartCommand", "[]");
        JsonArray values = JsonParser.parseString(raw).getAsJsonArray();
        if (values.size() > 32) throw new IllegalArgumentException("restartCommand too long");
        List<String> args = new ArrayList<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank() || value.getAsString().length() > 1000)
                throw new IllegalArgumentException("restartCommand must be a JSON array of fixed arguments");
            args.add(value.getAsString());
        }
        return List.copyOf(args);
    }
    public static boolean restartAvailable() { return ENABLED && !DEPLOYMENT_ID.isBlank() && !RESTART_COMMAND.isEmpty(); }
    public static JsonObject capabilities() {
        JsonObject result = new JsonObject();
        result.addProperty("requestGc", ENABLED);
        result.addProperty("restart", restartAvailable());
        result.addProperty("executorFingerprint", EXECUTOR_FINGERPRINT);
        result.addProperty("restartUnavailableReason", restartAvailable() ? "" : "目标节点尚未配置固定的重启命令和 deploymentId");
        return result;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String confirmation(String action, int machineId) {
        return ("RESTART".equals(action) ? "确认重启节点 " : "确认请求回收节点 ") + machineId;
    }
}
