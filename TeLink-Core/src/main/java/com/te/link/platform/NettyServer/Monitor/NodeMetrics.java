package com.te.link.platform.NettyServer.Monitor;

import com.te.link.platform.NettyServer.Config.LinkConfig;
import com.te.link.platform.NettyServer.Config.NettyConfig;
import com.te.link.platform.constant.LinkConfigConstant;
import com.te.link.platform.log.LogRuntime;
import com.te.link.platform.utils.SystemUtils;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NodeMetrics {
    private static final NodeMetrics INSTANCE = new NodeMetrics();

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicLong totalMessageCount = new AtomicLong();
    private final RecentMessageCounter recentMessages = new RecentMessageCounter();
    private final String instanceId = java.util.UUID.randomUUID().toString();
    private final long createdAtNanos = System.nanoTime();
    private volatile boolean started;
    private volatile LocalDateTime startTime;
    private volatile LocalDateTime lastHeartbeatTime;
    private volatile String lastErrorMessage;

    private NodeMetrics() {
    }

    public static NodeMetrics getInstance() {
        return INSTANCE;
    }

    public void markStarted() {
        this.started = true;
        this.startTime = LocalDateTime.now();
    }

    public void markStopped() {
        this.started = false;
    }

    public void channelActive() {
        connectionCount.incrementAndGet();
    }

    public void channelInactive() {
        connectionCount.updateAndGet(count -> Math.max(0, count - 1));
    }

    public void messageReceived() {
        totalMessageCount.incrementAndGet();
    }

    /** 业务消息计数不包括登录、心跳、ACK、建房或入房。 */
    public void businessMessageReceived() { recentMessages.increment(); }

    public void heartbeat() {
        lastHeartbeatTime = LocalDateTime.now();
    }

    public void recordError(Throwable throwable) {
        if (throwable != null) {
            lastErrorMessage = throwable.getMessage();
        }
    }

    public NodeDetail snapshot() {
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        com.sun.management.OperatingSystemMXBean system = os instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
        double systemLoad = system == null ? -1 : system.getCpuLoad();
        long totalMemory = system == null ? -1 : system.getTotalMemorySize();
        long freeMemory = system == null ? -1 : system.getFreeMemorySize();

        return NodeDetail.builder()
                .machineId(LinkConfig.MACHINE_ID)
                .serverName(LinkConfig.SERVERNAME)
                .hostIp(LogRuntime.getHostIp())
                .port(Integer.getInteger("telink.tcp.port", LinkConfigConstant.LISTENING_PORT))
                .nettyMode(nettyMode())
                .bossThreadCount(NettyConfig.bossEventLoopGroupNum)
                .workerThreadCount(NettyConfig.workerEventLoopGroupNum)
                .status(started ? "RUNNING" : "STOPPED")
                .startTime(startTime)
                .uptimeSeconds(uptimeSeconds())
                .connectionCount(connectionCount.get())
                .totalMessageCount(totalMessageCount.get())
                .lastHeartbeatTime(lastHeartbeatTime)
                .lastErrorMessage(lastErrorMessage)
                .cpuUsage(cpuUsage())
                .heapUsed(heapMemoryUsage.getUsed())
                .heapMax(heapMemoryUsage.getMax())
                .threadCount(threadMXBean.getThreadCount())
                .gcCount(gcCount())
                .gcTimeMs(gcTimeMs())
                .sampledAtMillis(System.currentTimeMillis())
                .instanceId(instanceId)
                .deploymentId(com.te.link.platform.operations.OperationConfig.DEPLOYMENT_ID)
                .operationCapabilities(com.te.link.platform.operations.OperationConfig.capabilities())
                .messagesLastMinute(recentMessages.count())
                .messageWindowSeconds(Math.min(60, (System.nanoTime() - createdAtNanos) / 1_000_000_000L))
                .systemCpuUsage(systemLoad < 0 ? null : systemLoad * 100)
                .systemMemoryTotal(totalMemory <= 0 ? null : totalMemory)
                .systemMemoryUsed(totalMemory <= 0 || freeMemory < 0 ? null : totalMemory - freeMemory)
                .build();
    }

    private Long uptimeSeconds() {
        if (startTime == null) {
            return 0L;
        }
        return Duration.between(startTime, LocalDateTime.now()).getSeconds();
    }

    private String nettyMode() {
        return switch (SystemUtils.ChargeMode()) {
            case 1 -> "Epoll";
            case 2 -> "IO_uring";
            default -> "NIO";
        };
    }

    private Double cpuUsage() {
        java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            double load = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
            return load < 0 ? null : load * 100;
        }
        return null;
    }

    private Long gcCount() {
        long count = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long current = gcBean.getCollectionCount();
            if (current > 0) {
                count += current;
            }
        }
        return count;
    }

    private Long gcTimeMs() {
        long time = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long current = gcBean.getCollectionTime();
            if (current > 0) {
                time += current;
            }
        }
        return time;
    }
}
