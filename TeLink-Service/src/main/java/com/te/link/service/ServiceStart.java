package com.te.link.service;

import com.te.link.core.room.JdbcMessageTrace;
import com.te.link.core.room.KafkaMessageBus;
import com.te.link.core.room.PassThroughBusinessProcessor;
import com.te.link.core.room.RoomBusinessPipeline;
import com.te.link.core.room.RoomTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 独立业务进程。消费 message_in，原样写入 message_out，并推进 BUSINESS_PROCESSED。
 */
public class ServiceStart {
    private static final Logger log = LoggerFactory.getLogger(ServiceStart.class);

    public static void main(String[] args) throws InterruptedException {
        JdbcMessageTrace trace = JdbcMessageTrace.fromSystemProperties();
        trace.ensureTable();
        KafkaMessageBus bus = KafkaMessageBus.fromSystemProperties();
        RoomBusinessPipeline pipeline = new RoomBusinessPipeline(new PassThroughBusinessProcessor(), trace, bus);
        bus.subscribe(RoomTopics.IN, RoomTopics.BUSINESS_GROUP, pipeline::onUpstream);
        Runtime.getRuntime().addShutdownHook(new Thread(bus::close));
        log.info("telink business service started, input:{}, output:{}, group:{}",
                RoomTopics.IN, RoomTopics.OUT, RoomTopics.BUSINESS_GROUP);
        Thread.currentThread().join();
    }
}
