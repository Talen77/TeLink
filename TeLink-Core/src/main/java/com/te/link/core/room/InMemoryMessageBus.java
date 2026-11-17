package com.te.link.core.room;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 发布成功回调发生在投递之前。同一个消费组的多个订阅者轮流接收，模拟 Kafka 共享消费组。
 */
public class InMemoryMessageBus implements MessageBus {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telink-memory-bus");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Map<String, List<MessageHandler>>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public void publish(String topic, String key, String value, PublishListener listener) {
        if (closed.get()) {
            listener.onFailure(new IllegalStateException("message bus closed"));
            return;
        }
        listener.onSuccess();
        executor.execute(() -> dispatch(topic, key, value));
    }

    @Override
    public AutoCloseable subscribe(String topic, String group, MessageHandler handler) {
        List<MessageHandler> handlers = subscribers
                .computeIfAbsent(topic, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(group, ignored -> new CopyOnWriteArrayList<>());
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }

    @Override
    public void close() {
        closed.set(true);
        executor.shutdownNow();
    }

    private void dispatch(String topic, String key, String value) {
        Map<String, List<MessageHandler>> groups = subscribers.get(topic);
        if (groups == null) {
            return;
        }
        for (List<MessageHandler> handlers : groups.values()) {
            if (handlers.isEmpty()) {
                continue;
            }
            int index = Math.floorMod(cursor.getAndIncrement(), handlers.size());
            handlers.get(index).onMessage(key, value);
        }
    }
}
