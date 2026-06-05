package com.te.link.core.processor;

import com.te.link.common.constant.ChannelAttrKey;
import com.te.link.common.domain.model.CompleteMessage;
import com.te.link.common.domain.model.MessageBody;
import com.te.link.common.domain.model.RedisOperationMessage;
import com.te.link.common.domain.protobuf.PacketHeader;
import com.te.link.common.utils.JsonUtil;
import com.te.link.core.config.KafkaConfig;
import com.te.link.core.config.RedisConfig;
import com.te.link.core.kafka.KafkaProducerManager;
import com.te.link.core.netty.UserChannelCtxMap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.concurrent.Future;

import static com.te.link.common.enums.AppId.LINK_SERVER;
import static com.te.link.common.enums.MessageType.LOGIN_MESSAGE;

/**
 * @Author: Talen
 */
@Slf4j
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

        KafkaProducer<String, String> producer = KafkaProducerManager.getProducer();

        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaConfig.LINK_TOPIC,
                JsonUtil.toJson(RedisOperationMessage
                        .builder()
                        .op(RedisConfig.OP_SETNX)
                        .key(RedisConfig.PREFIX_USER_ID+userId)
                        // todo 机器id的生成
                        .value("")
                        .expireSeconds(Integer.parseInt(RedisConfig.KEY_EXPIRE_TIME))
                        .build()
                )
        );

        Future<RecordMetadata> future = producer.send(record, (metadata, exception) -> {
            if (ctx.channel().isActive()) {
                ctx.channel().writeAndFlush(
                        CompleteMessage.builder()
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
                                                .content(exception == null ? "登录成功" : "登陆失败: " + exception.getMessage())
                                                .build()
                                ).build()
                );
            }
            if (exception != null) {
                log.error("[sendKafka] 发送消息失败: " + exception.getMessage());
            } else {
                log.info("[sendKafka] 发送消息成功,Topic: {}", metadata.topic());
            }
        });

    }

}
