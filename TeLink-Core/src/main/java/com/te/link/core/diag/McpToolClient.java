package com.te.link.core.diag;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 只读工具的 HTTP 调用。连接或响应失败抛出运行时异常，由分析循环收成分析失败。 */
public final class McpToolClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String endpoint;

    public McpToolClient(String endpoint) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    public DiagTool tool(String name) {
        return new DiagTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String call(JsonObject arguments) {
                JsonObject body = new JsonObject();
                body.addProperty("name", name);
                body.add("arguments", arguments == null ? new JsonObject() : arguments);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/tools/call"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                try {
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("mcp status " + response.statusCode());
                    }
                    return response.body();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("mcp interrupted", exception);
                } catch (Exception exception) {
                    throw new IllegalStateException("mcp call failed", exception);
                }
            }
        };
    }

    public static JsonObject arguments(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }
}
