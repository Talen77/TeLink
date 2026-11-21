package com.te.link.platform.message;

/**
 * push_message 专属分页查询参数。
 */
public class PushMessageSearchParam {
    private int page = 1;
    private int pageSize = 20;
    private String logId;
    private Long uid;
    private Long roomId;
    private String keyword;

    public int getPage() {
        return page;
    }

    public PushMessageSearchParam setPage(int page) {
        this.page = page;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public PushMessageSearchParam setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public String getLogId() {
        return logId;
    }

    public PushMessageSearchParam setLogId(String logId) {
        this.logId = logId;
        return this;
    }

    public Long getUid() {
        return uid;
    }

    public PushMessageSearchParam setUid(Long uid) {
        this.uid = uid;
        return this;
    }

    public Long getRoomId() {
        return roomId;
    }

    public PushMessageSearchParam setRoomId(Long roomId) {
        this.roomId = roomId;
        return this;
    }

    public String getKeyword() {
        return keyword;
    }

    public PushMessageSearchParam setKeyword(String keyword) {
        this.keyword = keyword;
        return this;
    }
}
