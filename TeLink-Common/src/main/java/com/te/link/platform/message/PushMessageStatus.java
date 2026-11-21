package com.te.link.platform.message;

/**
 * 消息链路状态及其推进顺序。
 * 不同进程可能异步更新同一条消息，rank 用于保证数据库状态只能向前推进。
 */
public enum PushMessageStatus {
    RECEIVED(10),
    ACCEPTED(20),
    BUSINESS_PROCESSED(30),
    ROUTING(40),
    FAILED(45),
    DELIVERED(50);

    private final int rank;

    PushMessageStatus(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }
}
