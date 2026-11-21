package com.te.link.platform.message;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消息管理异步写入器。
 * 数据库写入不占用 Netty 或 Kafka 消费线程；状态 rank 保证多进程异步落库时不会回退。
 */
public final class PushMessageWriter {
    private static final PushMessageRepository MESSAGE_REPOSITORY = new PushMessageRepository();
    private static final PushMessageNodeRepository NODE_REPOSITORY = new PushMessageNodeRepository();
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(10_000),
            daemonThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private PushMessageWriter() {
    }

    public static void upsert(
            String logId,
            String messageId,
            Long uid,
            long roomId,
            String content,
            PushMessageStatus status
    ) {
        EXECUTOR.execute(() -> {
            try {
                MESSAGE_REPOSITORY.upsert(logId, messageId, uid, roomId, content, status);
            } catch (Exception e) {
                System.err.println("[PushMessageWriter] upsert push_message failed: " + e.getMessage());
            }
        });
    }

    public static void recordNode(String logId, int machineId, String stage) {
        EXECUTOR.execute(() -> {
            try {
                NODE_REPOSITORY.record(logId, machineId, stage);
            } catch (Exception e) {
                System.err.println("[PushMessageWriter] insert push_message_node failed: " + e.getMessage());
            }
        });
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "push-message-writer-" + THREAD_SEQUENCE.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }
}
