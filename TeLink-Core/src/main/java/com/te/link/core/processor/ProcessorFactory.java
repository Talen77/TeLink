package com.te.link.core.processor;

import com.te.link.common.enums.MessageType;

import static com.te.link.common.enums.MessageType.*;

/**
 * @Author: Talen
 */
public class ProcessorFactory {

    public static AbstractMessageProcessor getProcessor(MessageType messageType) {
        return switch (messageType) {
            case LOGIN_MESSAGE -> LoginProcessor.getInstance();
            default -> throw new IllegalArgumentException("不支持的消息类型");
        };
     }
}
