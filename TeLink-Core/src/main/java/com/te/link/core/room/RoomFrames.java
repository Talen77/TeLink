package com.te.link.core.room;

import com.te.link.common.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RoomFrames {
    private RoomFrames() {
    }

    public static String event(String type, RoomRecord record, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        if (record != null) {
            body.put("messageId", record.getMessageId());
            body.put("logId", record.getLogId());
            body.put("roomId", record.getRoomId());
            body.put("userId", record.getUserId());
            body.put("content", record.getContent());
        }
        if (message != null) {
            body.put("message", message);
        }
        return JsonUtil.toJson(body);
    }
}
