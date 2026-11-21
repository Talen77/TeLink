package com.te.link.platform.mcp;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** 独立工具服务配置：JVM 参数 > 环境变量 > 本地配置 > 默认值。 */
public final class McpServiceConfig {
    private static final Properties LOCAL = load();
    public static final String HOST = read("telink.mcp.host", "TELINK_MCP_HOST", "127.0.0.1");
    public static final int PORT = number("telink.mcp.port", "TELINK_MCP_PORT", 9091);
    public static final String API_KEY = read("telink.mcp.apiKey", "TELINK_MCP_API_KEY", "");
    public static final String PROJECT_ROOT = read("telink.mcp.projectRoot", "TELINK_MCP_PROJECT_ROOT", "");
    public static final int WORKERS = number("telink.mcp.workers", "TELINK_MCP_WORKERS", 8);
    public static final int MAX_REQUEST_BYTES = number("telink.mcp.maxRequestBytes", "TELINK_MCP_MAX_REQUEST_BYTES", 262144);
    public static final int RESULT_MAX_LENGTH = number("telink.mcp.resultMaxLength", "TELINK_MCP_RESULT_MAX_LENGTH", 16000);
    public static final Set<String> ENABLED_TOOLS = values(read("telink.mcp.tools", "TELINK_MCP_TOOLS",
            "query_push_logs_by_log_id,query_project_code,list_push_nodes,query_node_metrics,search_knowledge"));
    public static final Set<String> ALLOWED_ORIGINS = values(read("telink.mcp.allowedOrigins", "TELINK_MCP_ALLOWED_ORIGINS",
            "http://localhost:" + PORT + ",http://127.0.0.1:" + PORT));

    private McpServiceConfig() { }

    private static String read(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null) value = System.getenv(environment);
        if (value == null) value = LOCAL.getProperty(property);
        return value == null ? fallback : value.trim();
    }

    private static int number(String property, String environment, int fallback) {
        int value = Integer.parseInt(read(property, environment, String.valueOf(fallback)));
        if (value <= 0) throw new IllegalArgumentException(property + " must be positive");
        return value;
    }

    private static Set<String> values(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).collect(Collectors.toSet());
    }

    private static Properties load() {
        Properties properties = new Properties();
        Path path = Path.of(System.getProperty("telink.local.config", "config/local.properties"));
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (Exception e) {
                throw new IllegalStateException("load MCP service config failed", e);
            }
        }
        return properties;
    }
}
