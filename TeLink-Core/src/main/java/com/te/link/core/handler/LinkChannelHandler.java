package com.te.link.core.handler;

import com.te.link.common.domain.model.CompleteMessage;
import com.te.link.common.enums.MessageType;
import com.te.link.core.processor.AbstractMessageProcessor;
import com.te.link.core.processor.ProcessorFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * @Author: Talen
 */
public class LinkChannelHandler extends SimpleChannelInboundHandler<CompleteMessage> {
    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, CompleteMessage completeMessage) throws Exception {
        MessageType messageType = MessageType.fromType((short) completeMessage.getPacketHeader().getMessageType());
        if (messageType == null) {
            return;
        }
        AbstractMessageProcessor processor = ProcessorFactory.getProcessor(messageType);
        processor.process(channelHandlerContext, completeMessage);
    }
}
