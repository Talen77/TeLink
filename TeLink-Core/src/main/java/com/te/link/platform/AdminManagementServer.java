package com.te.link.platform;

import com.te.link.platform.NettyServer.Agent.AgentAnalysisResult;
import com.te.link.platform.NettyServer.Agent.AgentConversationService;
import com.te.link.platform.NettyServer.Agent.LogAnalysisAgent;
import com.te.link.platform.NettyServer.Agent.LogAnalysisResult;
import com.te.link.platform.NettyServer.Agent.PushAgentAnalyzer;
import com.te.link.platform.NettyServer.Config.WebSocketGatewayConfig;
import com.te.link.platform.NettyServer.Monitor.NodeDetail;
import com.te.link.platform.log.LogLevel;
import com.te.link.platform.log.PushLogRecord;
import com.te.link.platform.log.PushLogRepository;
import com.te.link.platform.log.PushLogSearchParam;
import com.te.link.platform.message.PushMessagePage;
import com.te.link.platform.message.PushMessageRecord;
import com.te.link.platform.message.PushMessageRepository;
import com.te.link.platform.message.PushMessageSearchParam;
import com.te.link.platform.redis.RedisClient;
import com.te.link.platform.utils.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推送中台管理接口。
 * 节点列表从 Redis 注册中心读取；当前实例快照只用于节点自身定时上报。
 */
public class AdminManagementServer {
    private static final int DEFAULT_PORT = 9090;

    private final PushLogRepository pushLogRepository = new PushLogRepository();
    private final PushMessageRepository pushMessageRepository = new PushMessageRepository();
    private final LogAnalysisAgent logAnalysisAgent = new LogAnalysisAgent();
    private final PushAgentAnalyzer pushAgentAnalyzer = new PushAgentAnalyzer();
    private final AgentConversationService agentConversationService = new AgentConversationService();
    private final com.te.link.platform.operations.OperationService operationService;
    // 管理接口与 Netty 业务线程池隔离，避免慢日志分析占用消息处理线程。
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;

    public AdminManagementServer() { this(new com.te.link.platform.operations.OperationService()); }
    public AdminManagementServer(com.te.link.platform.operations.OperationService operationService) { this.operationService = operationService; }

    public void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(managementPort()), 0);
            server.createContext("/admin/api/nodes", this::handleNodes);
            server.createContext("/admin/api/knowledge", this::handleKnowledge);
            server.createContext("/admin/api/messages", this::handleMessages);
            server.createContext("/admin/api/logs", this::handleLogs);
            server.createContext("/admin/api/log-analysis", this::handleLogAnalysis);
            server.createContext("/admin/api/node-analysis", this::handleNodeAnalysis);
            server.createContext("/admin/api/client-nodes", this::handleClientNodes);
            server.createContext("/admin/api/client-rooms", this::handleClientRooms);
            server.createContext("/admin/api/agent/session/start", this::handleAgentSessionStart);
            server.createContext("/admin/api/agent/session/chat", this::handleAgentSessionChat);
            server.createContext("/admin/api/agent/operations", this::handleOperations);
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("start admin management server failed", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        executor.shutdownNow();
    }

    private void handleOperations(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) return;
        String path = exchange.getRequestURI().getPath().substring("/admin/api/agent/operations".length());
        try {
            if ("GET".equals(exchange.getRequestMethod()) && path.isEmpty()) {
                Map<String,String> query = queryParams(exchange);
                writeJson(exchange, 200, operationService.list(query.get("conversationId"), parseInt(query.get("page"),1), parseInt(query.get("pageSize"),20)));
            } else if ("POST".equals(exchange.getRequestMethod()) && path.matches("/[0-9a-fA-F-]{36}/decision")) {
                String key = exchange.getRequestHeaders().getFirst("X-Operation-Review-Key");
                operationService.authorize(key);
                byte[] body = exchange.getRequestBody().readNBytes(16385);
                if (body.length > 16384) { writeJson(exchange,413,Map.of("message","审核请求过大"));return; }
                JsonObject request;
                try { request = JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject(); }
                catch (RuntimeException e) { throw new IllegalArgumentException("审核请求必须是 JSON 对象"); }
                writeJson(exchange,200,operationService.decide(path.substring(1,37),request,key));
            } else writeJson(exchange,404,Map.of("message","审核接口不存在"));
        } catch (SecurityException e) { writeJson(exchange,401,Map.of("message",e.getMessage())); }
        catch (com.te.link.platform.operations.OperationRepository.Conflict e) { writeJson(exchange,409,Map.of("message",e.getMessage())); }
        catch (IllegalArgumentException e) { writeJson(exchange,400,Map.of("message",e.getMessage())); }
        catch (Exception e) { writeJson(exchange,503,Map.of("message","操作审核暂不可用，请检查共享 MySQL、agent_operation 和 agent_operation_node_guard 表及节点注册中心")); }
    }

    private void handleKnowledge(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) return;
        String method = exchange.getRequestMethod();
        if (!Set.of("GET", "POST", "PATCH", "DELETE").contains(method)) {
            writeJson(exchange, 405, Map.of("message", "method not allowed")); return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(com.te.link.platform.knowledge.KnowledgeHttpClient.MAX_UPLOAD_BYTES + 1);
        if (body.length > com.te.link.platform.knowledge.KnowledgeHttpClient.MAX_UPLOAD_BYTES) {
            writeJson(exchange, 413, Map.of("message", "文件不能超过 10 MiB")); return;
        }
        try {
            String path = exchange.getRequestURI().getRawPath().substring("/admin/api".length());
            if (exchange.getRequestURI().getRawQuery() != null) path += "?" + exchange.getRequestURI().getRawQuery();
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            var response = new com.te.link.platform.knowledge.KnowledgeHttpClient().request(method, path,
                    body.length == 0 ? null : body, contentType == null ? "application/json" : contentType);
            applyCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(response.statusCode(), response.body().length);
            exchange.getResponseBody().write(response.body());
            exchange.close();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            writeJson(exchange, 503, Map.of("message", "知识服务不可用，请先启动 BulletPushKnowledge 并检查连接配置。"));
        }
    }

    private void handleNodes(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.matches("/admin/api/nodes/[^/]+/rooms")) {
            handleNodeRooms(exchange, path.split("/")[4]);
            return;
        }
        if (!"/admin/api/nodes".equals(path)) {
            writeJson(exchange, 404, Map.of("message", "node endpoint not found"));
            return;
        }
        try {
            writeJson(exchange, 200, queryRegisteredNodes());
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("message", "query nodes failed"));
        }
    }

    private void handleNodeRooms(HttpExchange exchange, String value) throws IOException {
        int machineId;
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            machineId = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            writeJson(exchange, 400, Map.of("message", "machineId is invalid"));
            return;
        }
        try {
            JsonObject node;
            try { node = new com.te.link.platform.monitor.ClusterNodeRepository().get(machineId); }
            catch (IllegalArgumentException e) {
                writeJson(exchange, 404, Map.of("message", "node not found"));
                return;
            }
            String status = node.get("status").getAsString();
            List<String> rooms = node.get("fresh").getAsBoolean() && "RUNNING".equals(status)
                    ? RedisClient.listActivePushNodeRooms(machineId) : List.of();
            writeJson(exchange, 200, Map.of("machineId", machineId, "nodeStatus", status,
                    "roomIds", rooms, "queriedAtMillis", System.currentTimeMillis()));
        } catch (Exception e) {
            writeJson(exchange, 503, Map.of("message", "query node rooms failed"));
        }
    }

    /**
     * 分页查询所有真实消息，并支持按 LogID、发送 UID、房间 ID 和消息内容筛选。
     */
    private void handleMessages(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange);
            PushMessageSearchParam param = new PushMessageSearchParam()
                    .setPage(parseInt(query.get("page"), 1))
                    .setPageSize(parseInt(query.get("pageSize"), 20))
                    .setLogId(query.get("logId"))
                    .setUid(parseNullableLong(query.get("uid")))
                    .setRoomId(parseNullableLong(query.get("roomId")))
                    .setKeyword(query.get("keyword"));
            PushMessagePage page = pushMessageRepository.queryPage(param);
            writeJson(exchange, 200, new MessagePageResponse(
                    page.items().stream().map(this::toMessageResponse).toList(),
                    page.total(),
                    page.page(),
                    page.pageSize(),
                    page.totalPages()
            ));
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("message", "query messages failed"));
        }
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange);
            PushLogSearchParam param = new PushLogSearchParam()
                    .setLevel(parseLevel(query.get("level")))
                    .setKeyword(query.get("keyword"))
                    .setLimit(parseInt(query.get("limit"), 100))
                    .setOffset(parseInt(query.get("offset"), 0));
            List<PushLogRecord> records;
            if ("logId".equals(query.get("mode"))) {
                param.setLogId(required(query, "logId"));
                records = pushLogRepository.queryByLogId(param);
            } else if ("machineId".equals(query.get("mode"))) {
                param.setMachineId(parseInt(required(query, "machineId"), -1));
                if (param.getMachineId() < 0) {
                    throw new IllegalArgumentException("machineId is invalid");
                }
                records = pushLogRepository.queryByMachineId(param);
            } else {
                throw new IllegalArgumentException("mode must be logId or machineId");
            }
            writeJson(exchange, 200, records.stream().map(this::toLogResponse).toList());
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("message", "query logs failed"));
        }
    }

    private void handleLogAnalysis(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange);
            LogAnalysisResult result = logAnalysisAgent.analyze(required(query, "logId"), query.get("model"));
            writeJson(exchange, 200, result);
        } catch (Exception e) {
            writeJson(exchange, 200, LogAnalysisResult.serviceAnalyzeFailed());
        }
    }

    private void handleNodeAnalysis(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange);
            writeJson(exchange, 200, pushAgentAnalyzer.analyzeCurrentNode(query.get("model")));
        } catch (Exception e) {
            writeJson(exchange, 200, Map.of("conclusion", "服务分析失败"));
        }
    }

    /**
     * 返回显式配置的 WebSocket 节点列表。
     * 未配置时返回空列表，客户端不会猜测节点地址。
     */
    private void handleClientNodes(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        List<WebSocketNodeResponse> nodes = WebSocketGatewayConfig.publicNodeEndpoints().stream()
                .map(endpoint -> new WebSocketNodeResponse(endpoint))
                .toList();
        writeJson(exchange, 200, nodes);
    }

    /**
     * 返回所有活跃推送节点当前持有的房间列表。
     * 房间来源于 Redis Hash 中的 machineId -> roomIds 映射，并额外通过活跃节点快照过滤离线节点。
     */
    private void handleClientRooms(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            writeJson(exchange, 200, queryClientRooms());
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("message", "query rooms failed"));
        }
    }

    /**
     * 创建新的排障会话。
     * 当前支持节点会话和 LogID 会话，后续继续追问都依赖 conversationId。
     */
    private void handleAgentSessionStart(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            JsonObject request = readRequestJson(exchange);
            writeJson(exchange, 200, agentConversationService.startSession(
                    readText(request, "scopeType"),
                    readIntegerList(request, "machineIds"),
                    readText(request, "logId"),
                    readText(request, "model")
            ));
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("message", "start agent session failed"));
        }
    }

    /**
     * 继续某个会话的追问，并通过 SSE 将模型回复流式返回给前端。
     */
    private void handleAgentSessionChat(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        JsonObject request;
        try {
            request = readRequestJson(exchange);
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("message", "agent chat failed"));
            return;
        }
        String conversationId = readText(request, "conversationId");
        String message = readText(request, "message");
        if (conversationId == null || message == null) {
            writeJson(exchange, 400, Map.of("message", "agent chat failed"));
            return;
        }
        AgentSessionSseWriter sseWriter = startSse(exchange);
        try {
            agentConversationService.streamChat(conversationId, message, sseWriter);
        } catch (Exception e) {
            sseWriter.onFailed();
        }
    }

    private NodeResponse toNodeResponse(NodeDetail detail) {
        return new NodeResponse(
                detail.getMachineId(),
                detail.getServerName(),
                detail.getHostIp(),
                detail.getPort(),
                detail.getNettyMode(),
                detail.getBossThreadCount(),
                detail.getWorkerThreadCount(),
                detail.getStatus(),
                toText(detail.getStartTime()),
                detail.getUptimeSeconds(),
                detail.getConnectionCount(),
                detail.getTotalMessageCount(),
                toText(detail.getLastHeartbeatTime()),
                detail.getLastErrorMessage(),
                detail.getCpuUsage(),
                detail.getHeapUsed(),
                detail.getHeapMax(),
                detail.getThreadCount(),
                detail.getGcCount(),
                detail.getGcTimeMs(),
                detail.getSampledAtMillis(), detail.getMessagesLastMinute(), detail.getMessageWindowSeconds(),
                detail.getSystemCpuUsage(), detail.getSystemMemoryTotal(), detail.getSystemMemoryUsed(),
                0L
        );
    }

    private List<NodeResponse> queryRegisteredNodes() {
        Map<Integer, Long> counts = messageCountsByMachine();
        Set<Integer> seen = new java.util.HashSet<>();
        List<NodeResponse> nodes = new ArrayList<>();
        for (Map.Entry<String, String> entry : RedisClient.listPushNodeSnapshots().entrySet()) {
            Integer machineId = parseInt(entry.getKey(), -1);
            if (machineId == null || machineId < 0) {
                continue;
            }
            String snapshotJson = entry.getValue();
            long traversed = counts.getOrDefault(machineId, 0L);
            if (snapshotJson == null || snapshotJson.isBlank()) {
                nodes.add(offlineNode(machineId, traversed));
                seen.add(machineId);
                continue;
            }
            try {
                nodes.add(toNodeResponse(JsonParser.parseString(snapshotJson).getAsJsonObject(), traversed));
            } catch (Exception e) {
                nodes.add(offlineNode(machineId, traversed));
            }
            seen.add(machineId);
        }
        for (Map.Entry<Integer, Long> entry : counts.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                nodes.add(offlineNode(entry.getKey(), entry.getValue()));
            }
        }
        nodes.sort(Comparator.comparing(NodeResponse::machineId));
        return nodes;
    }

    private Map<Integer, Long> messageCountsByMachine() {
        try {
            return new com.te.link.platform.message.PushMessageNodeRepository().countByMachine();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private List<WebSocketRoomResponse> queryClientRooms() {
        Set<Integer> activeMachineIds = new TreeSet<>();
        for (Map.Entry<String, String> entry : RedisClient.listPushNodeSnapshots().entrySet()) {
            int machineId = parseInt(entry.getKey(), -1);
            if (machineId < 0 || !hasText(entry.getValue())) {
                continue;
            }
            activeMachineIds.add(machineId);
        }

        Map<String, String> machineRoomMapping = RedisClient.listPushNodeRooms();
        Map<Long, TreeSet<Integer>> roomMachineMapping = new TreeMap<>();
        for (Integer machineId : activeMachineIds) {
            for (Long roomId : parseRoomIds(machineRoomMapping.get(String.valueOf(machineId)))) {
                roomMachineMapping.computeIfAbsent(roomId, ignored -> new TreeSet<>()).add(machineId);
            }
        }

        List<WebSocketRoomResponse> rooms = new ArrayList<>();
        for (Map.Entry<Long, TreeSet<Integer>> entry : roomMachineMapping.entrySet()) {
            rooms.add(new WebSocketRoomResponse(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return rooms;
    }

    private NodeResponse toNodeResponse(JsonObject detail, long traversedMessageCount) {
        return new NodeResponse(
                readInteger(detail, "machineId"),
                readText(detail, "serverName"),
                readText(detail, "hostIp"),
                readInteger(detail, "port"),
                readText(detail, "nettyMode"),
                readInteger(detail, "bossThreadCount"),
                readInteger(detail, "workerThreadCount"),
                readText(detail, "status"),
                readText(detail, "startTime"),
                readLong(detail, "uptimeSeconds"),
                readInteger(detail, "connectionCount"),
                readLong(detail, "totalMessageCount"),
                readText(detail, "lastHeartbeatTime"),
                readText(detail, "lastErrorMessage"),
                readDouble(detail, "cpuUsage"),
                readLong(detail, "heapUsed"),
                readLong(detail, "heapMax"),
                readInteger(detail, "threadCount"),
                readLong(detail, "gcCount"),
                readLong(detail, "gcTimeMs"),
                readLong(detail, "sampledAtMillis"), readLong(detail, "messagesLastMinute"), readLong(detail, "messageWindowSeconds"),
                readDouble(detail, "systemCpuUsage"), readLong(detail, "systemMemoryTotal"), readLong(detail, "systemMemoryUsed"),
                traversedMessageCount
        );
    }

    private NodeResponse offlineNode(Integer machineId, long traversedMessageCount) {
        return new NodeResponse(
                machineId,
                null,
                null,
                null,
                null,
                null,
                null,
                "OFFLINE",
                null,
                0L,
                0,
                0L,
                null,
                "节点快照已过期",
                null,
                null,
                null,
                null,
                null,
                null,
                null, null, null, null, null, null,
                traversedMessageCount
        );
    }

    private LogResponse toLogResponse(PushLogRecord record) {
        return new LogResponse(
                record.getId(),
                toText(record.getLogTime()),
                record.getLevelName(),
                record.getMachineId(),
                record.getHostIp(),
                record.getLoggerName(),
                record.getSourceFilePath(),
                record.getSourceLine(),
                record.getTraceId(),
                record.getMessage(),
                record.getThrowable()
        );
    }

    private MessageResponse toMessageResponse(PushMessageRecord record) {
        return new MessageResponse(
                record.getId(),
                record.getLogId(),
                record.getMessageId(),
                record.getUid(),
                record.getRoomId(),
                record.getContent(),
                record.getStatus(),
                record.getTraversedNodeIds(),
                toText(record.getCreatedAt()),
                toText(record.getUpdatedAt())
        );
    }

    private Map<String, String> queryParams(HttpExchange exchange) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> pair.length == 2 ? decode(pair[1]) : "",
                        (left, right) -> right
                ));
    }

    private String required(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private JsonObject readRequestJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String readText(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        String value = jsonObject.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer readInteger(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        try {
            return jsonObject.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private Long readLong(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        try {
            return jsonObject.get(key).getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    private Double readDouble(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        try {
            return jsonObject.get(key).getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Integer> readIntegerList(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return List.of();
        }
        JsonArray jsonArray = jsonObject.getAsJsonArray(key);
        if (jsonArray == null) {
            return List.of();
        }
        List<Integer> values = new java.util.ArrayList<>();
        for (JsonElement element : jsonArray) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            values.add(element.getAsInt());
        }
        return values;
    }

    private LogLevel parseLevel(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
            return null;
        }
        return LogLevel.valueOf(value.toUpperCase());
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Long parseNullableLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("numeric filter must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("numeric filter is invalid", e);
        }
    }

    private List<Long> parseRoomIds(String roomIdsJson) {
        if (!hasText(roomIdsJson)) {
            return List.of();
        }
        try {
            JsonArray roomArray = JsonParser.parseString(roomIdsJson).getAsJsonArray();
            List<Long> roomIds = new ArrayList<>();
            for (JsonElement element : roomArray) {
                if (element == null || element.isJsonNull()) {
                    continue;
                }
                roomIds.add(element.getAsLong());
            }
            return roomIds;
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String toText(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private int managementPort() {
        return parseInt(System.getProperty("telink.admin.port", System.getenv("TELINK_ADMIN_PORT")), DEFAULT_PORT);
    }

    private void writeJson(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] body = JsonUtil.toJson(response).getBytes(StandardCharsets.UTF_8);
        applyCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private boolean handlePreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
            return false;
        }
        applyCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private AgentSessionSseWriter startSse(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        return new AgentSessionSseWriter(exchange);
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Operation-Review-Key");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PATCH,DELETE,OPTIONS");
    }

    /**
     * SSE 事件写入器。
     * 失败时只回传固定失败文案，避免把内部异常细节透给前端。
     */
    private static final class AgentSessionSseWriter implements AgentConversationService.AgentConversationStreamObserver {
        private final HttpExchange exchange;
        private boolean closed;

        private AgentSessionSseWriter(HttpExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public synchronized void onModel(String modelName) {
            if (closed) {
                return;
            }
            writeEvent("meta", Map.of("modelName", modelName));
        }

        @Override
        public synchronized void onStage(String title, String status, String detail) {
            if (closed) {
                return;
            }
            writeEvent("stage", Map.of(
                    "title", title,
                    "status", status,
                    "detail", detail
            ));
        }

        @Override
        public synchronized void onChunk(String chunk) {
            if (closed) {
                return;
            }
            writeEvent("chunk", Map.of("content", chunk));
        }

        @Override
        public synchronized void onFailed() {
            if (closed) {
                return;
            }
            writeEvent("failed", Map.of("content", AgentAnalysisResult.SERVICE_ANALYZE_FAILED));
            close();
        }

        @Override
        public synchronized void onCompleted() {
            if (closed) {
                return;
            }
            writeEvent("done", Map.of("done", true));
            close();
        }

        private void writeEvent(String event, Object payload) {
            if (closed) {
                return;
            }
            try {
                String body = "event: " + event + "\n"
                        + "data: " + JsonUtil.toJson(payload) + "\n\n";
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (IOException e) {
                close();
            }
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            exchange.close();
        }
    }

    private record NodeResponse(
            Integer machineId,
            String serverName,
            String hostIp,
            Integer port,
            String nettyMode,
            Integer bossThreadCount,
            Integer workerThreadCount,
            String status,
            String startTime,
            Long uptimeSeconds,
            Integer connectionCount,
            Long totalMessageCount,
            String lastHeartbeatTime,
            String lastErrorMessage,
            Double cpuUsage,
            Long heapUsed,
            Long heapMax,
            Integer threadCount,
            Long gcCount,
            Long gcTimeMs,
            Long sampledAtMillis,
            Long messagesLastMinute,
            Long messageWindowSeconds,
            Double systemCpuUsage,
            Long systemMemoryTotal,
            Long systemMemoryUsed,
            Long traversedMessageCount
    ) {
    }

    private record LogResponse(
            Long id,
            String time,
            String level,
            Integer machineId,
            String hostIp,
            String logger,
            String sourceFilePath,
            Integer sourceLine,
            String logId,
            String message,
            String throwable
    ) {
    }

    private record MessageResponse(
            Long id,
            String logId,
            String messageId,
            Long uid,
            Long roomId,
            String content,
            String status,
            List<Integer> traversedNodeIds,
            String createdAt,
            String updatedAt
    ) {
    }

    private record MessagePageResponse(
            List<MessageResponse> items,
            long total,
            int page,
            int pageSize,
            int totalPages
    ) {
    }

    private record WebSocketNodeResponse(String endpoint) {
    }

    private record WebSocketRoomResponse(Long roomId, List<Integer> machineIds) {
    }
}
