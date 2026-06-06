package com.te.link.common.constant;

/**
 * @Author: Talen
 */
public interface ProtoConstant {

    // magic(2) + version(2) + packet_headerLength(4) + packet_dataLength(4)
    int FIXED_PACKET_BOUNDARY = 12;

    short MAGIC = 0xABC;

    short VERSION = 1;

    String DEFAULT_SECRETKEY = "SECRETKEYBYXEXGM";
}
