package com.te.link.bootstrap;

import com.te.link.core.grpc.GrpcServer;
import com.te.link.core.netty.NettyServer;
import com.te.link.core.cache.LinkClusterManager;
import com.te.link.core.register.NacosRegisterCenter;
import com.te.link.core.register.RegisterCenterProcessor;
import com.te.link.core.config.LinkConfig;
import com.te.link.core.room.PlatformRuntime;
import com.te.link.core.room.RoomFeature;
import com.te.link.core.room.RoomNode;
import com.te.link.core.room.RoomRuntime;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: Talen
 */
@Slf4j
public class Bootstrap {

    public static void main(String[] args) {
        new Bootstrap().start(args);
    }

    public void start(String[] args) {
        log.info("[startTeLink]...");

        new NettyServer().start();
        if (LinkConfig.MACHINE_ID != null) {
            PlatformRuntime.bindMachine(LinkConfig.MACHINE_ID);
        }
        PlatformRuntime.startServices();
        Runtime.getRuntime().addShutdownHook(new Thread(PlatformRuntime::close));
        RoomNode roomNode = null;
        if (RoomFeature.enabled()) {
            roomNode = RoomRuntime.startWebSocket(LinkConfig.MACHINE_ID);
            Runtime.getRuntime().addShutdownHook(new Thread(RoomRuntime::close));
        }
        GrpcServer grpcServer = new GrpcServer(roomNode == null ? null : roomNode.deliveryService());
        grpcServer.start();
        try {
            initConfigCenter();
        } catch (Exception exception) {
            log.warn("nacos unavailable, this node stays up without cluster discovery: {}", exception.getMessage());
        }
        grpcServer.blockUntilShutdown();


    }

    private void initConfigCenter() {
        RegisterCenterProcessor registerCenterProcessor = new NacosRegisterCenter();
        registerCenterProcessor.init();
        registerCenterProcessor.subscribeServiceChange(LinkClusterManager::updateClusterInstances);
    }
}
