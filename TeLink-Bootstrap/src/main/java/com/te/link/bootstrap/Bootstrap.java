package com.te.link.bootstrap;

import com.te.link.core.grpc.GrpcServer;
import com.te.link.core.netty.NettyServer;
import com.te.link.core.cache.LinkClusterManager;
import com.te.link.core.register.NacosRegisterCenter;
import com.te.link.core.register.RegisterCenterProcessor;
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
        GrpcServer grpcServer = new GrpcServer();

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
