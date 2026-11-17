package com.te.link.core.room;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.te.link.common.utils.JsonUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class WebSocketRoomHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(WebSocketRoomHandler.class);

    private final RoomNode node;

    public WebSocketRoomHandler(RoomNode node) {
        this.node = node;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        try {
            JsonObject request = JsonParser.parseString(frame.text()).getAsJsonObject();
            String type = requiredText(request, "type");
            if ("CREATE_ROOM".equals(type)) {
                createRoom(context, request);
                return;
            }
            if ("JOIN_ROOM".equals(type)) {
                joinRoom(context, request);
                return;
            }
            if ("CHAT_MESSAGE".equals(type)) {
                chat(context, request);
                return;
            }
            reject(context, "MESSAGE_REJECTED", null, "不支持的消息类型");
        } catch (RuntimeException exception) {
            log.info("websocket message rejected: {}", exception.getMessage());
            reject(context, "MESSAGE_REJECTED", null, "消息格式错误");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        Long emptied = node.registry().leave(context.channel());
        if (emptied != null) {
            node.directory().unregister(emptied, node.machineId());
        }
        super.channelInactive(context);
    }

    private void createRoom(ChannelHandlerContext context, JsonObject request) {
        long roomId = requiredRoomId(request);
        if (!node.directory().createIfAbsent(roomId, node.machineId(), RoomFeature.LEASE_SECONDS)) {
            reject(context, "ROOM_REJECTED", roomId, "房间已存在，请直接加入房间");
            return;
        }
        enter(context, roomId, optionalUserId(request), "ROOM_CREATED", "房间创建成功，已进入房间");
    }

    private void joinRoom(ChannelHandlerContext context, JsonObject request) {
        long roomId = requiredRoomId(request);
        if (node.directory().findNodes(roomId).isEmpty()) {
            reject(context, "ROOM_REJECTED", roomId, "房间不存在");
            return;
        }
        node.directory().join(roomId, node.machineId(), RoomFeature.LEASE_SECONDS);
        enter(context, roomId, optionalUserId(request), "ROOM_JOINED", "已进入房间");
    }

    private void enter(ChannelHandlerContext context, long roomId, Long userId, String type, String message) {
        Long emptied = node.registry().join(roomId, context.channel());
        if (emptied != null) {
            node.directory().unregister(emptied, node.machineId());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("roomId", roomId);
        body.put("userId", userId);
        body.put("message", message);
        context.writeAndFlush(new TextWebSocketFrame(JsonUtil.toJson(body)));
    }

    private void chat(ChannelHandlerContext context, JsonObject request) {
        long roomId = requiredRoomId(request);
        if (!node.registry().isJoined(roomId, context.channel())) {
            reject(context, "MESSAGE_REJECTED", roomId, "尚未进入该房间");
            return;
        }
        String content = requiredText(request, "content");
        if (content.isBlank()) {
            reject(context, "MESSAGE_REJECTED", roomId, "消息内容不能为空");
            return;
        }
        RoomRecord record = new RoomRecord();
        record.setLogId(UUID.randomUUID().toString().replace("-", ""));
        record.setMessageId(requiredText(request, "messageId"));
        record.setRoomId(roomId);
        record.setUserId(optionalUserId(request));
        record.setContent(content);
        record.setSentAt(request.has("sentAt") ? request.get("sentAt").getAsLong() : System.currentTimeMillis());
        record.setSourceMachineId(node.machineId());
        String payload = JsonUtil.toJson(record);
        node.trace().advance(record, RoomMessageStatus.RECEIVED, node.machineId(), "received");
        node.bus().publish(RoomTopics.IN, String.valueOf(roomId), payload, new PublishListener() {
            @Override
            public void onSuccess() {
                node.trace().advance(record, RoomMessageStatus.ACCEPTED, node.machineId(), "accepted");
                context.writeAndFlush(new TextWebSocketFrame(RoomFrames.event("MESSAGE_ACCEPTED", record, "消息已进入业务队列")));
            }

            @Override
            public void onFailure(Throwable error) {
                node.trace().advance(record, RoomMessageStatus.FAILED, node.machineId(), error.getMessage());
                reject(context, "MESSAGE_REJECTED", roomId, "消息写入失败");
            }
        });
    }

    private void reject(ChannelHandlerContext context, String type, Long roomId, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("roomId", roomId);
        body.put("message", message);
        context.writeAndFlush(new TextWebSocketFrame(JsonUtil.toJson(body)));
    }

    private static long requiredRoomId(JsonObject request) {
        JsonElement roomId = request.get("roomId");
        if (roomId == null || !roomId.isJsonPrimitive() || roomId.getAsLong() <= 0) {
            throw new IllegalArgumentException("roomId");
        }
        return roomId.getAsLong();
    }

    private static String requiredText(JsonObject request, String field) {
        JsonElement value = request.get(field);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.getAsString();
    }

    private static Long optionalUserId(JsonObject request) {
        JsonElement userId = request.get("userId");
        if (userId == null || userId.isJsonNull()) {
            return null;
        }
        long value = userId.getAsLong();
        if (value <= 0) {
            throw new IllegalArgumentException("userId");
        }
        return value;
    }
}
