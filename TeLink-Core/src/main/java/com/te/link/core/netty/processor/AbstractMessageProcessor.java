package com.te.link.core.netty.processor;

import com.te.link.common.domain.model.CompleteMessage;
import io.netty.channel.ChannelHandlerContext;

/**
 * @Author: Talen
 */
public abstract class AbstractMessageProcessor<T> {

    public abstract void process(ChannelHandlerContext ctx, T msg);

}
