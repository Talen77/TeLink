package com.te.link.core.room;

import com.te.link.common.utils.JsonUtil;
import com.te.link.platform.log.Log;
import com.te.link.platform.log.LogFactory;
import com.te.link.platform.message.PushMessageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务进程和验收里的业务消费者共用这一段，避免两边各写一遍状态推进。
 */
public class RoomBusinessPipeline {
    private static final Logger log = LoggerFactory.getLogger(RoomBusinessPipeline.class);
    private static final Log chain = LogFactory.getLog(RoomBusinessPipeline.class);

    private final PassThroughBusinessProcessor processor;
    private final MessageTrace trace;
    private final MessageBus bus;
    private final int machineId;

    public RoomBusinessPipeline(PassThroughBusinessProcessor processor, MessageTrace trace, MessageBus bus) {
        this(processor, trace, bus, 0);
    }

    public RoomBusinessPipeline(PassThroughBusinessProcessor processor, MessageTrace trace, MessageBus bus, int machineId) {
        this.processor = processor;
        this.trace = trace;
        this.bus = bus;
        this.machineId = machineId;
    }

    public void onUpstream(String key, String payload) {
        String outbound = processor.process(key, payload);
        RoomRecord record = JsonUtil.fromJson(outbound, RoomRecord.class);
        trace.advance(record, RoomMessageStatus.BUSINESS_PROCESSED, machineId, record.getContent());
        MessageTrail.status(record, PushMessageStatus.BUSINESS_PROCESSED);
        chain.Info(MessageTrail.context(record), "BUSINESS_MESSAGE_PROCESSED");
        bus.publish(RoomTopics.OUT, key, outbound, new PublishListener() {
            @Override
            public void onSuccess() {
                log.info("business output accepted, logId:{}", record.getLogId());
            }

            @Override
            public void onFailure(Throwable error) {
                trace.advance(record, RoomMessageStatus.FAILED, machineId, error.getMessage());
                MessageTrail.status(record, PushMessageStatus.FAILED);
                chain.Error(MessageTrail.context(record), "BUSINESS_MESSAGE_FAILED");
                log.warn("business output failed, logId:{}", record.getLogId(), error);
            }
        });
    }
}
