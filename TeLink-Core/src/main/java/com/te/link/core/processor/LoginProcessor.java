package com.te.link.core.processor;

import com.te.link.common.constant.ChannelAttrKey;
import com.te.link.common.domain.model.CompleteMessage;
import com.te.link.common.domain.model.MessageBody;
import com.te.link.common.domain.protobuf.PacketHeader;
import com.te.link.core.netty.UserChannelCtxMap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import static com.te.link.common.enums.AppId.LINK_SERVER;
import static com.te.link.common.enums.MessageType.LOGIN_MESSAGE;

/**
 * @Author: Talen
 */
public class LoginProcessor extends AbstractMessageProcessor<CompleteMessage>{

    private LoginProcessor(){
    }

    private static final LoginProcessor INSTANCE = new LoginProcessor();

    public static LoginProcessor getInstance(){
        return INSTANCE;
    }

    @Override
    public void process(ChannelHandlerContext ctx, CompleteMessage msg){
        PacketHeader packetHeader = msg.getPacketHeader();
        String token = packetHeader.getToken();
        // todo 验证token

        long userId = packetHeader.getUid();
        UserChannelCtxMap.addChannelCtx(userId, ctx);
        AttributeKey<Long> userIdKey = AttributeKey.valueOf(ChannelAttrKey.USER_ID);
        ctx.channel().attr(userIdKey).set(userId);
        AttributeKey<Long> heartBeatTimesKey = AttributeKey.valueOf(ChannelAttrKey.HEARTBEAT_TIMES);
        ctx.channel().attr(heartBeatTimesKey).set(0L);
        // 3.往kafka 发登录消息

        CompleteMessage rspMessage = CompleteMessage.builder()
                .packetHeader(
                        PacketHeader.newBuilder()
                                .setAppId(LINK_SERVER.getId())
                                .setUid(userId)
                                .setMessageType(LOGIN_MESSAGE.getType())
                                .build()
                )
                .messageBody(
                        MessageBody.builder()
                                .timestamp(System.currentTimeMillis())
                                .content("登录成功")
                                .build()
                ).build();
        ctx.channel().writeAndFlush(rspMessage);
    }

}
