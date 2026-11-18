package com.te.link.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.te.link.core.diag.LinkLog;
import com.te.link.core.diag.NodeSnapshot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 独立只读工具服务。不启动 Netty，也不调用模型。 */
public final class McpHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final McpFacts facts;

    public McpHttpServer(int port, McpFacts facts) throws IOException {
        this.facts = facts;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/health".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            JsonObject health = new JsonObject();
            health.addProperty("status", "ok");
            send(exchange, 200, health);
            return;
        }
        if ("/tools".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            send(exchange, 200, toolList());
            return;
        }
        if ("/tools/call".equals(path) && "POST".equals(exchange.getRequestMethod())) {
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            send(exchange, 200, call(body.get("name").getAsString(), body.getAsJsonObject("arguments")));
            return;
        }
        send(exchange, 404, new JsonObject());
    }

    public JsonObject call(String name, JsonObject arguments) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        return switch (name) {
            case "query_link_logs_by_log_id" -> logs(requiredText(args, "logId"));
            case "query_room_route" -> route(requiredLong(args, "roomId"));
            case "list_link_nodes" -> nodes();
            case "query_node_metrics" -> metrics(requiredInt(args, "machineId"));
            default -> throw new IllegalArgumentException("unknown tool");
        };
    }

    private JsonObject logs(String logId) {
        JsonArray items = new JsonArray();
        for (LinkLog log : facts.logs().query(logId)) {
            JsonObject item = new JsonObject();
            item.addProperty("logId", log.logId());
            item.addProperty("level", log.level());
            item.addProperty("machineId", log.machineId());
            item.addProperty("message", log.message());
            item.addProperty("exceptionText", log.exceptionText());
            items.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("logs", items);
        return body;
    }

    private JsonObject route(long roomId) {
        List<Integer> members = new ArrayList<>(facts.rooms().findNodes(roomId));
        members.sort(Integer::compareTo);
        JsonArray ids = new JsonArray();
        members.forEach(ids::add);
        JsonObject body = new JsonObject();
        body.addProperty("roomId", roomId);
        body.add("machineIds", ids);
        return body;
    }

    private JsonObject nodes() {
        JsonArray items = new JsonArray();
        for (NodeSnapshot snapshot : facts.nodes().list()) {
            items.add(snapshotJson(snapshot));
        }
        JsonObject body = new JsonObject();
        body.add("nodes", items);
        return body;
    }

    private JsonObject metrics(int machineId) {
        NodeSnapshot snapshot = facts.nodes().find(machineId);
        JsonObject body = new JsonObject();
        body.addProperty("machineId", machineId);
        if (snapshot == null) {
            body.addProperty("found", false);
            return body;
        }
        body.add("snapshot", snapshotJson(snapshot));
        return body;
    }

    private static JsonObject snapshotJson(NodeSnapshot snapshot) {
        JsonObject item = new JsonObject();
        item.addProperty("machineId", snapshot.machineId());
        item.addProperty("online", snapshot.online());
        item.addProperty("connectionCount", snapshot.connectionCount());
        item.addProperty("heapUsed", snapshot.heapUsed());
        item.addProperty("sampledAtMillis", snapshot.sampledAtMillis());
        item.addProperty("detail", snapshot.detail());
        return item;
    }

    private static JsonObject toolList() {
        JsonArray tools = new JsonArray();
        for (String name : List.of("query_link_logs_by_log_id", "query_room_route", "list_link_nodes", "query_node_metrics")) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", name);
            tools.add(tool);
        }
        JsonObject body = new JsonObject();
        body.add("tools", tools);
        return body;
    }

    private static String requiredText(JsonObject args, String field) {
        if (!args.has(field) || args.get(field).getAsString().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return args.get(field).getAsString();
    }

    private static long requiredLong(JsonObject args, String field) {
        if (!args.has(field)) {
            throw new IllegalArgumentException(field);
        }
        return args.get(field).getAsLong();
    }

    private static int requiredInt(JsonObject args, String field) {
        if (!args.has(field)) {
            throw new IllegalArgumentException(field);
        }
        return args.get(field).getAsInt();
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
