package com.te.link.core.grpc;

import com.te.link.core.config.GrpcConfig;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * @Author: Talen
 */
@Slf4j
public class GrpcServer {
    private final int port;
    private final io.grpc.BindableService roomDeliveryService;
    private Server server;

    public GrpcServer() {
        this(null);
    }

    public GrpcServer(io.grpc.BindableService roomDeliveryService) {
        this.port = GrpcConfig.GRPC_PORT;
        this.roomDeliveryService = roomDeliveryService;
    }

    public void start() {
        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(port)
                    .addService(new PushServiceImpl());
            if (roomDeliveryService != null) {
                builder.addService(roomDeliveryService);
            }
            server = builder.build().start();
        } catch (IOException e) {
            log.info("[grpcServer] 初始化异常: {}", e.getMessage());
        }
    }

    public void blockUntilShutdown() {
        if (server != null) {
            try {
                server.awaitTermination();
            } catch (InterruptedException e) {
                log.info("[grpcServer] 关闭异常: {}", e.getMessage());
            }
        }
    }
}
