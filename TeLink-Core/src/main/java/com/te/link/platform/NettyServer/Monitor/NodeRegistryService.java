package com.te.link.platform.NettyServer.Monitor;

import com.te.link.platform.NettyServer.Config.LinkConfig;
import com.te.link.platform.log.Log;
import com.te.link.platform.log.LogContext;
import com.te.link.platform.log.LogFactory;
import com.te.link.platform.redis.RedisClient;
import com.google.gson.JsonObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推送节点注册中心上报器。
 * 节点每 10 秒上报，快照保留 30 秒；采样时间用于识别旧版本或过期数据。
 */
public class NodeRegistryService {
    public static final int NODE_SNAPSHOT_TTL_SECONDS = 30;
    private static final int NODE_SNAPSHOT_REFRESH_SECONDS = 10;
    private static final Log log = LogFactory.getLog(NodeRegistryService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "push-node-registry-reporter");
            thread.setDaemon(true);
            return thread;
        });
        // 启动后立即注册，快照失效窗口覆盖三次上报间隔。
        scheduler.scheduleAtFixedRate(this::refreshSafely, 0, NODE_SNAPSHOT_REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        refreshSafely();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void refreshSafely() {
        if (LinkConfig.MACHINE_ID == null) {
            return;
        }
        try {
            NodeMetrics.getInstance().heartbeat();
            NodeDetail snapshot = NodeMetrics.getInstance().snapshot();
            RedisClient.refreshPushNodeSnapshot(
                    LinkConfig.MACHINE_ID,
                    snapshotJson(snapshot),
                    NODE_SNAPSHOT_TTL_SECONDS
            );
            log.Info(LogContext.create()
                            .put("machineId", LinkConfig.MACHINE_ID)
                            .put("nodeStatus", snapshot.getStatus())
                            .put("nodeSnapshotTtlSeconds", NODE_SNAPSHOT_TTL_SECONDS),
                    "TELINK_NODE_REGISTRY_REFRESHED");
        } catch (Exception e) {
            log.Warn(LogContext.create()
                            .put("machineId", LinkConfig.MACHINE_ID)
                            .put("errorType", e.getClass().getSimpleName()),
                    "TELINK_NODE_REGISTRY_REFRESH_FAILED");
        }
    }

    public static String snapshotJson(NodeDetail detail) {
        JsonObject json = new JsonObject();
        json.addProperty("machineId", detail.getMachineId());
        json.addProperty("serverName", detail.getServerName());
        json.addProperty("hostIp", detail.getHostIp());
        json.addProperty("port", detail.getPort());
        json.addProperty("nettyMode", detail.getNettyMode());
        json.addProperty("bossThreadCount", detail.getBossThreadCount());
        json.addProperty("workerThreadCount", detail.getWorkerThreadCount());
        json.addProperty("status", detail.getStatus());
        json.addProperty("startTime", detail.getStartTime() == null ? null : detail.getStartTime().toString());
        json.addProperty("uptimeSeconds", detail.getUptimeSeconds());
        json.addProperty("connectionCount", detail.getConnectionCount());
        json.addProperty("totalMessageCount", detail.getTotalMessageCount());
        json.addProperty("lastHeartbeatTime",
                detail.getLastHeartbeatTime() == null ? null : detail.getLastHeartbeatTime().toString());
        json.addProperty("lastErrorMessage", detail.getLastErrorMessage());
        json.addProperty("cpuUsage", detail.getCpuUsage());
        json.addProperty("heapUsed", detail.getHeapUsed());
        json.addProperty("heapMax", detail.getHeapMax());
        json.addProperty("threadCount", detail.getThreadCount());
        json.addProperty("gcCount", detail.getGcCount());
        json.addProperty("gcTimeMs", detail.getGcTimeMs());
        json.addProperty("sampledAtMillis", detail.getSampledAtMillis());
        json.addProperty("instanceId", detail.getInstanceId());
        json.addProperty("deploymentId", detail.getDeploymentId());
        json.add("operationCapabilities", detail.getOperationCapabilities());
        json.addProperty("messagesLastMinute", detail.getMessagesLastMinute());
        json.addProperty("messageWindowSeconds", detail.getMessageWindowSeconds());
        json.addProperty("systemCpuUsage", detail.getSystemCpuUsage());
        json.addProperty("systemMemoryTotal", detail.getSystemMemoryTotal());
        json.addProperty("systemMemoryUsed", detail.getSystemMemoryUsed());
        return json.toString();
    }
}
