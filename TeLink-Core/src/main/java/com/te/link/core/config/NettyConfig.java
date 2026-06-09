package com.te.link.core.config;

import static com.te.link.common.constant.LinkConfigConstant.LISTENING_PORT;

/**
 * @Author: Talen
 */
public class NettyConfig {
    // boss、worker的数量
    public static int bossEventLoopGroupNum = 1;

    public static int workerEventLoopGroupNum = Runtime.getRuntime().availableProcessors() * 2;

    public static int maxContentSize = 64 * 1024 * 1024; // 64Mb

    /**
     * 监听端口 9999
     */
    public static int port = LISTENING_PORT;
}
