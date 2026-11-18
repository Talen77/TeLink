package com.te.link.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.te.link.core.diag.DiagnosisHttpServer;
import com.te.link.core.diag.DiagnosisService;
import com.te.link.core.diag.DiagnosisView;
import com.te.link.core.diag.LinkLog;
import com.te.link.core.diag.McpToolClient;
import com.te.link.core.diag.MemoryLinkLogStore;
import com.te.link.core.diag.MemoryNodeSnapshotStore;
import com.te.link.core.diag.ModelClient;
import com.te.link.core.diag.ModelTurn;
import com.te.link.core.diag.NodeSnapshot;
import com.te.link.core.diag.ReactRunner;
import com.te.link.core.room.MemoryRoomDirectory;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Step2DiagnosisAcceptanceTest {
    @Test
    public void logQueryMatchesDirectStore() throws Exception {
        MemoryLinkLogStore logs = new MemoryLinkLogStore();
        logs.append(new LinkLog("log-1", "ERROR", 2, "room route failed", "fanout-failed"));
        try (McpHttpServer server = new McpHttpServer(0, new McpFacts(logs, new MemoryRoomDirectory(), new MemoryNodeSnapshotStore()))) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("logId", "log-1");
            JsonObject viaTool = server.call("query_link_logs_by_log_id", arguments);
            LinkLog direct = logs.query("log-1").get(0);
            JsonObject toolLog = viaTool.getAsJsonArray("logs").get(0).getAsJsonObject();
            assertEquals(direct.level(), toolLog.get("level").getAsString());
            assertEquals(direct.machineId(), toolLog.get("machineId").getAsInt());
            assertEquals(direct.exceptionText(), toolLog.get("exceptionText").getAsString());
        }
    }

    @Test
    public void roomRouteMatchesDirectory() throws Exception {
        MemoryRoomDirectory rooms = new MemoryRoomDirectory();
        rooms.createIfAbsent(1001, 1, 90);
        rooms.join(1001, 3, 90);
        try (McpHttpServer server = new McpHttpServer(0, new McpFacts(new MemoryLinkLogStore(), rooms, new MemoryNodeSnapshotStore()))) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("roomId", 1001);
            JsonArray viaTool = server.call("query_room_route", arguments).getAsJsonArray("machineIds");
            List<Integer> direct = new ArrayList<>(rooms.findNodes(1001));
            direct.sort(Integer::compareTo);
            assertEquals(direct.size(), viaTool.size());
            for (int index = 0; index < direct.size(); index++) {
                assertEquals(direct.get(index).intValue(), viaTool.get(index).getAsInt());
            }
        }
    }

    @Test
    public void childAgentRejectsForeignMachineId() throws Exception {
        MemoryNodeSnapshotStore nodes = new MemoryNodeSnapshotStore();
        nodes.save(new NodeSnapshot(1, true, 1, 10, System.currentTimeMillis(), "local"));
        nodes.save(new NodeSnapshot(2, true, 1, 10, System.currentTimeMillis(), "secret-2"));
        try (McpHttpServer server = new McpHttpServer(0, new McpFacts(new MemoryLinkLogStore(), new MemoryRoomDirectory(), nodes))) {
            DiagnosisService service = new DiagnosisService(new McpToolClient("http://127.0.0.1:" + server.port()), nodes);
            AtomicBoolean sawRejection = new AtomicBoolean();
            ModelClient model = history -> {
                boolean answered = history.stream().anyMatch(message -> "tool".equals(message.role()));
                if (!answered) {
                    JsonObject arguments = new JsonObject();
                    arguments.addProperty("machineId", 2);
                    return ModelTurn.tool("query_node_metrics", arguments);
                }
                String observation = history.get(history.size() - 1).content();
                sawRejection.set(observation.contains("\"rejected\":true"));
                assertFalse(observation.contains("secret-2"));
                return ModelTurn.text("node 1 ok");
            };
            DiagnosisView view = service.diagnoseNode(1, model);
            assertTrue(sawRejection.get());
            assertEquals("node 1 ok", view.text());
            assertFalse(nodes.reads().contains(2));
        }
    }

    @Test
    public void modelOrMcpFailureShowsOnlyFailureText() throws Exception {
        MemoryNodeSnapshotStore nodes = new MemoryNodeSnapshotStore();
        try (McpHttpServer mcp = new McpHttpServer(0, new McpFacts(new MemoryLinkLogStore(), new MemoryRoomDirectory(), nodes))) {
            DiagnosisService service = new DiagnosisService(new McpToolClient("http://127.0.0.1:" + mcp.port()), nodes);
            assertFailurePage(body -> service.diagnoseLog("log-1", history -> {
                throw new IllegalStateException("model down");
            }));
            mcp.close();
            assertFailurePage(body -> service.diagnoseLog("log-1", history -> {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("logId", "log-1");
                return ModelTurn.tool("query_link_logs_by_log_id", arguments);
            }));
        }
    }

    private static void assertFailurePage(java.util.function.Function<JsonObject, DiagnosisView> handler) throws Exception {
        try (DiagnosisHttpServer page = new DiagnosisHttpServer(0, handler)) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + page.port() + "/diagnosis"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(1, body.size());
            assertEquals(ReactRunner.FAILURE, body.get("text").getAsString());
        }
    }
}
