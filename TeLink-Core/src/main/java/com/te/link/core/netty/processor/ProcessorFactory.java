package com.te.link.core.netty.processor;

import com.te.link.common.domain.model.CompleteMessage;
import com.te.link.common.enums.MessageType;

/**
 * @Author: Talen
 */
public class ProcessorFactory {

    public static AbstractMessageProcessor<CompleteMessage> getProcessor(MessageType messageType) {
        return switch (messageType) {
            case LOGIN_MESSAGE -> LoginProcessor.getInstance();
            case HEARTBEAT_MESSAGE -> HeartBeatProcessor.getInstance();
            default -> throw new IllegalArgumentException("不支持的消息类型");
        };
     }
}
