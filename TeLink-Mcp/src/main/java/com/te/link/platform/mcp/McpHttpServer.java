package com.te.link.platform.mcp;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 独立、无会话的 MCP HTTP 服务。REST 和 JSON-RPC 共用发现与执行逻辑。 */
public final class McpHttpServer implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-11-25";
    private static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", PROTOCOL_VERSION);
    private final HttpServer server;
    private final ExecutorService executor;
    private final McpToolRegistry registry = new McpToolRegistry();

    public McpHttpServer(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 128);
        executor = Executors.newFixedThreadPool(McpServiceConfig.WORKERS);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public void stop() { close(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !McpServiceConfig.ALLOWED_ORIGINS.contains(origin)) {
                send(exchange, 403, errorBody("origin is not allowed")); return;
            }
            if (!McpServiceConfig.API_KEY.isEmpty() && !authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, errorBody("unauthorized")); return;
            }
            if (!Set.of("/health", "/tools", "/tools/call", "/mcp").contains(path)) {
                send(exchange, 404, errorBody("not found")); return;
            }
            String expectedMethod = path.equals("/health") || path.equals("/tools") ? "GET" : "POST";
            if (!expectedMethod.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", expectedMethod);
                send(exchange, 405, errorBody("method not allowed")); return;
            }
            if (path.equals("/health")) {
                JsonObject health = new JsonObject(); health.addProperty("status", "ok");
                send(exchange, 200, health); return;
            }
            if (path.equals("/tools")) { send(exchange, 200, registry.listTools()); return; }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                send(exchange, 415, errorBody("Content-Type must be application/json")); return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(McpServiceConfig.MAX_REQUEST_BYTES + 1);
            if (bytes.length > McpServiceConfig.MAX_REQUEST_BYTES) {
                send(exchange, 413, errorBody("request too large")); return;
            }
            JsonElement parsed;
            try { parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)); }
            catch (JsonParseException e) {
                send(exchange, 400, path.equals("/mcp") ? rpcError(JsonNull.INSTANCE, -32700, "parse error") : errorBody("parse error")); return;
            }
            if (!parsed.isJsonObject()) {
                send(exchange, 400, path.equals("/mcp") ? rpcError(JsonNull.INSTANCE, -32600, "invalid request") : errorBody("body must be an object")); return;
            }
            if (path.equals("/mcp")) handleRpc(exchange, parsed.getAsJsonObject());
            else {
                try { send(exchange, 200, call(exchange, parsed.getAsJsonObject())); }
                catch (IllegalArgumentException e) { send(exchange, 400, errorBody(e.getMessage())); }
            }
        } finally { exchange.close(); }
    }

    private void handleRpc(HttpExchange exchange, JsonObject request) throws IOException {
        JsonElement id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
        boolean notification = !request.has("id");
        String method = string(request, "method");
        if (!"2.0".equals(string(request, "jsonrpc")) || method == null ||
                (!notification && (id.isJsonNull() || !id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean()))) {
            send(exchange, 400, rpcError(JsonNull.INSTANCE, -32600, "invalid request")); return;
        }
        String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (version != null && !VERSIONS.contains(version)) {
            send(exchange, 400, rpcError(id, -32600, "unsupported protocol version")); return;
        }
        if (notification) { send(exchange, 202, null); return; }
        try {
            JsonObject params = object(request, "params");
            JsonObject result;
            switch (method) {
                case "initialize" -> {
                    String requestedVersion = string(params, "protocolVersion");
                    if (requestedVersion == null || !params.has("capabilities") || !params.get("capabilities").isJsonObject()
                            || !params.has("clientInfo") || !params.get("clientInfo").isJsonObject()) {
                        throw new IllegalArgumentException("initialize requires protocolVersion, capabilities and clientInfo");
                    }
                    result = new JsonObject();
                    result.addProperty("protocolVersion", VERSIONS.contains(requestedVersion) ? requestedVersion : PROTOCOL_VERSION);
                    JsonObject capabilities = new JsonObject(); capabilities.add("tools", new JsonObject());
                    result.add("capabilities", capabilities);
                    JsonObject info = new JsonObject(); info.addProperty("name", "BulletPushMcp"); info.addProperty("version", "1.0.0");
                    result.add("serverInfo", info);
                }
                case "ping" -> result = new JsonObject();
                case "tools/list" -> {
                    if (params.has("cursor")) throw new IllegalArgumentException("pagination is not supported");
                    result = registry.listTools();
                }
                case "tools/call" -> result = call(exchange, params);
                default -> { send(exchange, 200, rpcError(id, -32601, "method not found")); return; }
            }
            JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0"); response.add("id", id); response.add("result", result);
            send(exchange, 200, response);
        } catch (IllegalArgumentException e) { send(exchange, 200, rpcError(id, -32602, e.getMessage())); }
        catch (Exception e) { send(exchange, 200, rpcError(id, -32603, "internal error")); }
    }

    private JsonObject call(HttpExchange exchange, JsonObject params) {
        String name = string(params, "name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must be a nonempty string");
        if (!registry.contains(name)) throw new IllegalArgumentException("unknown or disabled tool: " + name);
        JsonObject arguments = object(params, "arguments");
        JsonObject metadata = object(params, "_meta");
        String traceId = string(metadata, "traceId");
        if (traceId == null || traceId.isBlank()) traceId = exchange.getRequestHeaders().getFirst("X-Agent-Trace-Id");
        if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();
        String caller = string(metadata, "callerAgentId");
        if (traceId.length() > 128 || (caller != null && caller.length() > 128)) throw new IllegalArgumentException("metadata exceeds 128 characters");
        return registry.call(name, arguments, new McpCallContext(UUID.randomUUID().toString(), traceId, caller));
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + McpServiceConfig.API_KEY).getBytes(StandardCharsets.UTF_8));
    }

    private static String string(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString() ? field.getAsString() : null;
    }

    private static JsonObject object(JsonObject value, String key) {
        if (!value.has(key)) return new JsonObject();
        if (!value.get(key).isJsonObject()) throw new IllegalArgumentException(key + " must be an object");
        return value.getAsJsonObject(key);
    }

    private static JsonObject errorBody(String message) {
        JsonObject result = new JsonObject(); result.addProperty("error", message); return result;
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0"); response.add("id", id);
        JsonObject error = new JsonObject(); error.addProperty("code", code); error.addProperty("message", message); response.add("error", error);
        return response;
    }

    private static void send(HttpExchange exchange, int status, JsonObject response) throws IOException {
        if (response == null) { exchange.sendResponseHeaders(status, -1); return; }
        byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
