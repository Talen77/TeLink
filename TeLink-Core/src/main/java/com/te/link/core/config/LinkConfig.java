package com.te.link.core.config;

import com.te.link.common.utils.NetUtil;
import lombok.Data;

import static com.te.link.common.constant.LinkConfigConstant.*;

/**
 * @Author: Talen
 */
@Data
public class LinkConfig {
    /**
     * 服务名称
     */
    public static String SERVICENAME = DEFAULT_SERVICE_NAME;

    /**
     * 运行环境
     */
    public static String ENV = DEFAULT_ENV;

    /**
     * 机器id
     */
    public static Integer MACHINE_ID;

    /**
     * 机器ip
     */
    public static String LINK_HOST = NetUtil.getLocalIp();

}
