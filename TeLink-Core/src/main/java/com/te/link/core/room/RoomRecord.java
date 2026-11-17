package com.te.link.core.room;

/**
 * 在 message_in 和 message_out 之间原样传递的业务体。业务进程可以改 content，不能去掉 logId 和 roomId。
 */
public class RoomRecord {
    private String logId;
    private String messageId;
    private long roomId;
    private Long userId;
    private String content;
    private long sentAt;
    private int sourceMachineId;

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public long getRoomId() {
        return roomId;
    }

    public void setRoomId(long roomId) {
        this.roomId = roomId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getSentAt() {
        return sentAt;
    }

    public void setSentAt(long sentAt) {
        this.sentAt = sentAt;
    }

    public int getSourceMachineId() {
        return sourceMachineId;
    }

    public void setSourceMachineId(int sourceMachineId) {
        this.sourceMachineId = sourceMachineId;
    }
}
