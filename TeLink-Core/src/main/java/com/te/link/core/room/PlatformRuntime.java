package com.te.link.core.room;

import com.te.link.platform.AdminManagementServer;
import com.te.link.platform.NettyServer.Monitor.NodeMetrics;
import com.te.link.platform.NettyServer.Monitor.NodeRegistryService;
import com.te.link.platform.log.LogRuntime;
import com.te.link.platform.operations.NodeOperationWorker;

/**
 * 把推送节点的 machineId 交给监控、日志和管理接口，并在本进程启动审核执行器。
 *
 * @Author: Talen
 */
public final class PlatformRuntime {
    private static NodeRegistryService registry;
    private static NodeOperationWorker worker;
    private static AdminManagementServer admin;

    private PlatformRuntime() {
    }

    public static void bindMachine(int machineId) {
        com.te.link.platform.NettyServer.Config.LinkConfig.MACHINE_ID = machineId;
        LogRuntime.setMachineId(machineId);
        NodeMetrics.getInstance().markStarted();
    }

    public static synchronized void startServices() {
        PlatformSchema.ensure();
        if (registry == null) {
            registry = new NodeRegistryService();
            registry.start();
        }
        if (worker == null) {
            worker = new NodeOperationWorker();
            worker.start();
        }
        if (admin == null && !"false".equalsIgnoreCase(System.getProperty("telink.admin.enabled", "true"))) {
            admin = new AdminManagementServer();
            admin.start();
        }
    }

    public static synchronized void close() {
        if (worker != null) {
            worker.close();
            worker = null;
        }
        if (registry != null) {
            registry.stop();
            registry = null;
        }
        if (admin != null) {
            admin.stop();
            admin = null;
        }
    }
}
