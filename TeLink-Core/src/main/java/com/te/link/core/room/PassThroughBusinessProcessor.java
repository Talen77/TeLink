package com.te.link.core.room;

/**
 * 当前业务处理：不修改消息体，保留 logId 和 roomId 给下行路由使用。
 */
public class PassThroughBusinessProcessor {
    public String process(String key, String payload) {
        return payload;
    }
}
