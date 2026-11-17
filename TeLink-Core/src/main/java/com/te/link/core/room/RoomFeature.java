package com.te.link.core.room;

public final class RoomFeature {
    public static final int DEFAULT_WEBSOCKET_PORT = 8082;
    public static final int LEASE_SECONDS = 90;
    public static final int REFRESH_SECONDS = 30;

    private RoomFeature() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("telink.room.enabled", "false"));
    }

    public static int webSocketPort() {
        return Integer.getInteger("telink.websocket.port", DEFAULT_WEBSOCKET_PORT);
    }
}
