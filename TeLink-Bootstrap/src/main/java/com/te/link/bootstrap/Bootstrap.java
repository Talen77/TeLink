package com.te.link.bootstrap;

import com.te.link.core.grpc.GrpcServer;
import com.te.link.core.netty.NettyServer;
import com.te.link.core.cache.LinkClusterManager;
import com.te.link.core.register.NacosRegisterCenter;
import com.te.link.core.register.RegisterCenterProcessor;
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
        RoomNode roomNode = null;
        if (RoomFeature.enabled()) {
            roomNode = RoomRuntime.startWebSocket(com.te.link.core.config.LinkConfig.MACHINE_ID);
            Runtime.getRuntime().addShutdownHook(new Thread(RoomRuntime::close));
        }
        GrpcServer grpcServer = new GrpcServer(roomNode == null ? null : roomNode.deliveryService());

        // 初始化注册中心
        initConfigCenter();

        grpcServer.start();
        grpcServer.blockUntilShutdown();


    }

    private void initConfigCenter() {
        RegisterCenterProcessor registerCenterProcessor = new NacosRegisterCenter();
        registerCenterProcessor.init();
        registerCenterProcessor.subscribeServiceChange(LinkClusterManager::updateClusterInstances);
    }
}
