package com.te.link.core.processor;

import com.te.link.common.domain.model.CompleteMessage;
import io.netty.channel.ChannelHandlerContext;

/**
 * @Author: Talen
 */
public abstract class AbstractMessageProcessor {

    public void process(ChannelHandlerContext ctx, CompleteMessage msg){}

}
