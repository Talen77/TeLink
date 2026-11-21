package com.te.link.platform.NettyServer.Config;

import com.te.link.platform.constant.LinkConfigConstant;
import lombok.Data;

/**
 * 链接协议
 */
@Data
public class LinkConfig {
    /**
     * 服务名
     */
    public static String SERVERNAME = LinkConfigConstant.DEFAULT_SERVERNAME;
    /**
     * 机器id
     */
    public static Integer MACHINE_ID;
}
