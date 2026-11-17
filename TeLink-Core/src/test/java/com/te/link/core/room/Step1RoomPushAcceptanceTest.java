package com.te.link.core.room;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.te.link.common.enums.MessageType;
import com.te.link.core.netty.processor.HeartBeatProcessor;
import com.te.link.core.netty.processor.LoginProcessor;
import com.te.link.core.netty.processor.ProcessorFactory;
import org.junit.After;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Step1RoomPushAcceptanceTest {
    private final List<Cluster> clusters = new ArrayList<>();

    @After
    public void closeClusters() {
        for (Cluster cluster : clusters) {
            cluster.close();
        }
        clusters.clear();
    }

    @Test(timeout = 20000)
    public void twoClientsInSameRoomShareLogIdAndReachDelivered() throws Exception {
        Cluster cluster = Cluster.open(clusters, true, 1);
        RoomNode node = cluster.node(1);
        Ws sender = Ws.connect(node);
        Ws peer = Ws.connect(node);
        sender.send(room("CREATE_ROOM", 1001));
        sender.awaitType("ROOM_CREATED");
        peer.send(room("JOIN_ROOM", 1001));
        peer.awaitType("ROOM_JOINED");

        sender.send(chat(1001, "hello"));
        JsonObject accepted = sender.awaitType("MESSAGE_ACCEPTED");
        JsonObject deliveredToSender = sender.awaitType("MESSAGE_DELIVERED");
        JsonObject deliveredToPeer = peer.awaitType("MESSAGE_DELIVERED");

        String logId = accepted.get("logId").getAsString();
        assertEquals(logId, deliveredToSender.get("logId").getAsString());
        assertEquals(logId, deliveredToPeer.get("logId").getAsString());
        assertEquals("hello", deliveredToPeer.get("content").getAsString());
        awaitStatus(cluster.trace, logId, RoomMessageStatus.DELIVERED);
        sender.close();
        peer.close();
    }

    @Test(timeout = 20000)
    public void withoutBusinessConsumerThereIsNoDelivery() throws Exception {
        Cluster cluster = Cluster.open(clusters, false, 1);
        RoomNode node = cluster.node(1);
        Ws sender = Ws.connect(node);
        sender.send(room("CREATE_ROOM", 1001));
        sender.awaitType("ROOM_CREATED");
        sender.send(chat(1001, "stuck"));
        JsonObject accepted = sender.awaitType("MESSAGE_ACCEPTED");
        assertFalse(sender.hasType("MESSAGE_DELIVERED", Duration.ofMillis(800)));
        assertEquals(RoomMessageStatus.ACCEPTED, cluster.trace.status(accepted.get("logId").getAsString()));
        sender.close();
    }

    @Test(timeout = 20000)
    public void absentNodeRoutesOnlyToMembers() throws Exception {
        Cluster cluster = Cluster.open(clusters, true, 1, 2, 3);
        RoomNode nodeA = cluster.node(1);
        RoomNode nodeB = cluster.node(2);
        RoomNode nodeC = cluster.node(3);
        Ws clientA = Ws.connect(nodeA);
        Ws clientB = Ws.connect(nodeB);
        Ws clientC = Ws.connect(nodeC);
        clientA.send(room("CREATE_ROOM", 1001));
        clientA.awaitType("ROOM_CREATED");
        clientC.send(room("JOIN_ROOM", 1001));
        clientC.awaitType("ROOM_JOINED");
        nodeA.stopDownstream();
        nodeC.stopDownstream();

        clientA.send(chat(1001, "cross-node"));
        JsonObject accepted = clientA.awaitType("MESSAGE_ACCEPTED");
        JsonObject deliveredA = clientA.awaitType("MESSAGE_DELIVERED");
        JsonObject deliveredC = clientC.awaitType("MESSAGE_DELIVERED");
        assertEquals(accepted.get("logId").getAsString(), deliveredA.get("logId").getAsString());
        assertEquals(accepted.get("logId").getAsString(), deliveredC.get("logId").getAsString());
        assertFalse(clientB.hasType("MESSAGE_DELIVERED", Duration.ofMillis(800)));
        awaitStatus(cluster.trace, accepted.get("logId").getAsString(), RoomMessageStatus.DELIVERED);
        clientA.close();
        clientB.close();
        clientC.close();
    }

    @Test(timeout = 20000)
    public void disconnectRemovesLocalRoomAndDirectoryMember() throws Exception {
        Cluster cluster = Cluster.open(clusters, false, 7);
        RoomNode node = cluster.node(7);
        Ws client = Ws.connect(node);
        client.send(room("CREATE_ROOM", 1001));
        client.awaitType("ROOM_CREATED");
        client.close();

        boolean removed = false;
        for (int attempt = 0; attempt < 50; attempt++) {
            if (node.localRoomEmpty(1001) && node.directory().findNodes(1001).isEmpty()) {
                removed = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(removed);
    }

    @Test
    public void deliveredStatusDoesNotMoveBackward() {
        MemoryMessageTrace trace = new MemoryMessageTrace();
        RoomRecord record = new RoomRecord();
        record.setLogId("log-forward-only");
        record.setMessageId("m-1");
        record.setRoomId(1001);
        trace.advance(record, RoomMessageStatus.DELIVERED, 1, "delivered");
        trace.advance(record, RoomMessageStatus.ACCEPTED, 1, "late-accepted");
        assertEquals(RoomMessageStatus.DELIVERED, trace.status("log-forward-only"));
    }

    @Test
    public void legacyLoginHeartbeatAndPush2LinkStayInPlace() throws Exception {
        assertSame(LoginProcessor.getInstance(), ProcessorFactory.getProcessor(MessageType.LOGIN_MESSAGE));
        assertSame(HeartBeatProcessor.getInstance(), ProcessorFactory.getProcessor(MessageType.HEARTBEAT_MESSAGE));

        String pushProto = Files.readString(Path.of("..", "TeLink-Common", "src", "main", "java",
                "com", "te", "link", "common", "proto", "push_grpc.proto"));
        String pushService = Files.readString(Path.of("src", "main", "java",
                "com", "te", "link", "core", "grpc", "PushServiceImpl.java"));
        String grpcServer = Files.readString(Path.of("src", "main", "java",
                "com", "te", "link", "core", "grpc", "GrpcServer.java"));
        assertTrue(pushProto.contains("rpc Push2Link"));
        assertTrue(pushService.contains("public void push2Link"));
        assertTrue(grpcServer.contains("new PushServiceImpl()"));
        assertNotNull(com.te.link.common.grpc.PushServiceGrpc.class);
    }

    private static void awaitStatus(MessageTrace trace, String logId, RoomMessageStatus expected) throws InterruptedException {
        RoomMessageStatus current = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            current = trace.status(logId);
            if (current == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, current);
    }

    private static JsonObject room(String type, long roomId) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("roomId", roomId);
        body.addProperty("userId", 42);
        return body;
    }

    private static JsonObject chat(long roomId, String content) {
        JsonObject body = room("CHAT_MESSAGE", roomId);
        body.addProperty("messageId", "msg-" + content);
        body.addProperty("content", content);
        body.addProperty("sentAt", System.currentTimeMillis());
        return body;
    }

    private static final class Cluster implements AutoCloseable {
        private final MemoryMessageTrace trace;
        private final InMemoryMessageBus bus;
        private final GrpcRoomFanout fanout;
        private final Map<Integer, RoomNode> nodes;

        private Cluster(MemoryMessageTrace trace, InMemoryMessageBus bus, GrpcRoomFanout fanout, Map<Integer, RoomNode> nodes) {
            this.trace = trace;
            this.bus = bus;
            this.fanout = fanout;
            this.nodes = nodes;
        }

        static Cluster open(List<Cluster> clusters, boolean business, int... machineIds) {
            MemoryRoomDirectory directory = new MemoryRoomDirectory();
            MemoryMessageTrace trace = new MemoryMessageTrace();
            InMemoryMessageBus bus = new InMemoryMessageBus();
            Map<Integer, RoomNode> nodes = new ConcurrentHashMap<>();
            GrpcRoomFanout fanout = new GrpcRoomFanout(machineId -> {
                RoomNode node = nodes.get(machineId);
                if (node == null || node.grpcPort() < 0) {
                    return null;
                }
                return new GrpcRoomFanout.Target("127.0.0.1", node.grpcPort());
            });
            if (business) {
                bus.subscribe(RoomTopics.IN, RoomTopics.BUSINESS_GROUP,
                        new RoomBusinessPipeline(new PassThroughBusinessProcessor(), trace, bus)::onUpstream);
            }
            for (int machineId : machineIds) {
                RoomNode node = new RoomNode(machineId, directory, trace, bus, fanout);
                nodes.put(machineId, node);
                node.startStandaloneGrpc();
                node.startWebSocket(0);
            }
            Cluster cluster = new Cluster(trace, bus, fanout, nodes);
            clusters.add(cluster);
            return cluster;
        }

        RoomNode node(int machineId) {
            return nodes.get(machineId);
        }

        @Override
        public void close() {
            for (RoomNode node : nodes.values()) {
                node.close();
            }
            fanout.close();
            bus.close();
        }
    }

    private static final class Ws implements AutoCloseable {
        private WebSocket socket;
        private final BlockingQueue<JsonObject> frames = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        static Ws connect(RoomNode node) {
            Ws client = new Ws();
            client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create("ws://127.0.0.1:" + node.webSocketPort() + "/ws"), client.listener())
                    .join();
            return client;
        }

        void send(JsonObject body) {
            socket.sendText(body.toString(), true).join();
        }

        JsonObject awaitType(String type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonObject frame = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (frame != null && type.equals(frame.get("type").getAsString())) {
                    return frame;
                }
            }
            fail("等待 " + type + " 超时");
            return null;
        }

        boolean hasType(String type, Duration quietPeriod) throws InterruptedException {
            long deadline = System.nanoTime() + quietPeriod.toNanos();
            while (System.nanoTime() < deadline) {
                JsonObject frame = frames.poll(50, TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(frame.get("type").getAsString())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        }

        private WebSocket.Listener listener() {
            return new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                        frames.add(JsonParser.parseString(partial.toString()).getAsJsonObject());
                        partial.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }
            };
        }
    }
}
