package com.te.link.core.room;

/**
 * message_in / message_out 的发布和消费组订阅。同一个消费组里的多个订阅者，一条消息只交给其中一个。
 */
public interface MessageBus extends AutoCloseable {
    void publish(String topic, String key, String value, PublishListener listener);

    AutoCloseable subscribe(String topic, String group, MessageHandler handler);

    @Override
    void close();
}
