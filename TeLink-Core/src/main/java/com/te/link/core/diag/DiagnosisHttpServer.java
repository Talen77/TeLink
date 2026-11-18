package com.te.link.core.diag;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** 诊断页面的数据接口。响应体只来自 DiagnosisView。 */
public final class DiagnosisHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final Function<JsonObject, DiagnosisView> handler;

    public DiagnosisHttpServer(int port, Function<JsonObject, DiagnosisView> handler) throws IOException {
        this.handler = handler;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/diagnosis", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] request = exchange.getRequestBody().readAllBytes();
        JsonObject body = request.length == 0
                ? new JsonObject()
                : JsonParser.parseString(new String(request, StandardCharsets.UTF_8)).getAsJsonObject();
        byte[] response = handler.apply(body).toJson().toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
