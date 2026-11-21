package com.te.link.platform.message;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息管理页面使用的消息记录。
 */
public class PushMessageRecord {
    private Long id;
    private String logId;
    private String messageId;
    private Long uid;
    private Long roomId;
    private String content;
    private String status;
    private Integer statusRank;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Integer> traversedNodeIds = List.of();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getStatusRank() {
        return statusRank;
    }

    public void setStatusRank(Integer statusRank) {
        this.statusRank = statusRank;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Integer> getTraversedNodeIds() {
        return traversedNodeIds;
    }

    public void setTraversedNodeIds(List<Integer> traversedNodeIds) {
        this.traversedNodeIds = traversedNodeIds == null ? List.of() : List.copyOf(traversedNodeIds);
    }
}
