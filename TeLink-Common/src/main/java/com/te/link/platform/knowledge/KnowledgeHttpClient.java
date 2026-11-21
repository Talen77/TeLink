package com.te.link.platform.knowledge;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

/** 管理 API 与 MCP 访问知识服务，服务地址及密钥只来自服务端配置。 */
public class KnowledgeHttpClient {
    public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public HttpResponse<byte[]> request(String method, String path, byte[] body, String contentType) throws Exception {
        String endpoint = KnowledgeSettings.value("telink.knowledge.endpoint", "TELINK_KNOWLEDGE_ENDPOINT", "http://127.0.0.1:9093").replaceAll("/+$", "");
        String key = KnowledgeSettings.value("telink.knowledge.apiKey", "TELINK_KNOWLEDGE_API_KEY", "");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(path.equals("/knowledge/search") ? 20 : 60)).header("Content-Type", contentType)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (!key.isEmpty()) request.header("Authorization", "Bearer " + key);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    public JsonObject search(JsonObject arguments) {
        try {
            var result = request("POST", "/knowledge/search", arguments.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json");
            if (result.statusCode() != 200) throw new IllegalStateException("knowledge HTTP status " + result.statusCode());
            return JsonParser.parseString(new String(result.body(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("knowledge retrieval unavailable", e);
        }
    }
}
