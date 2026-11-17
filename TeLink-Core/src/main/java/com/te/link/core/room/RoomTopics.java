package com.te.link.core.room;

public final class RoomTopics {
    public static final String IN = System.getProperty("telink.kafka.topic.in", "message_in");
    public static final String OUT = System.getProperty("telink.kafka.topic.out", "message_out");
    public static final String BUSINESS_GROUP = "telink-business";
    public static final String DOWNSTREAM_GROUP = "telink-message-out";

    private RoomTopics() {
    }
}
