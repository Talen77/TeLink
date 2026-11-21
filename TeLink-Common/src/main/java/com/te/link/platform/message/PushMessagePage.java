package com.te.link.platform.message;

import java.util.List;

/**
 * 消息分页查询结果。
 */
public record PushMessagePage(
        List<PushMessageRecord> items,
        long total,
        int page,
        int pageSize,
        int totalPages
) {
}
