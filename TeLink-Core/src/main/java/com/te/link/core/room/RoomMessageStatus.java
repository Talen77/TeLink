package com.te.link.core.room;

/**
 * 消息轨迹只允许向更高的 rank 前进。DELIVERED 高于 FAILED，因此成功投递可以覆盖失败，失败不能覆盖成功。
 */
public enum RoomMessageStatus {
    RECEIVED(10),
    ACCEPTED(20),
    BUSINESS_PROCESSED(30),
    ROUTING(35),
    FAILED(40),
    DELIVERED(50);

    private final int rank;

    RoomMessageStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static RoomMessageStatus fromName(String name) {
        for (RoomMessageStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown room message status: " + name);
    }
}
