package com.te.link.core.room;

import com.te.link.common.utils.JsonUtil;
import com.te.link.core.grpc.RoomDeliveryGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class RoomNode implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RoomNode.class);

    private final int machineId;
    private final RoomDirectory directory;
    private final MessageTrace trace;
    private final MessageBus bus;
    private final RoomChannelRegistry registry = new RoomChannelRegistry();
    private final RoomDeliveryGrpcService deliveryService;
    private final RoomMessageRouter router;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel webSocketChannel;
    private Server grpcServer;
    private AutoCloseable subscription;
    private volatile boolean closed;

    public RoomNode(int machineId, RoomDirectory directory, MessageTrace trace, MessageBus bus, RoomFanout fanout) {
        this.machineId = machineId;
        this.directory = directory;
        this.trace = trace;
        this.bus = bus;
        this.deliveryService = new RoomDeliveryGrpcService(this::broadcast);
        this.router = new RoomMessageRouter(machineId, directory, trace, fanout, this::broadcast);
    }

    public void startWebSocket(int port) {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        try {
            webSocketChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(64 * 1024))
                                    .addLast(new WebSocketServerProtocolHandler("/ws"))
                                    .addLast(new WebSocketRoomHandler(RoomNode.this));
                        }
                    })
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .bind(port)
                    .sync()
                    .channel();
            subscription = bus.subscribe(RoomTopics.OUT, RoomTopics.DOWNSTREAM_GROUP, router::onDownstream);
            workerGroup.scheduleAtFixedRate(this::refreshLeases, RoomFeature.REFRESH_SECONDS, RoomFeature.REFRESH_SECONDS, TimeUnit.SECONDS);
            log.info("room websocket started, machineId:{}, port:{}", machineId, webSocketPort());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("start room websocket interrupted", exception);
        }
    }

    public void startStandaloneGrpc() {
        try {
            grpcServer = ServerBuilder.forPort(0).addService(deliveryService).build().start();
            log.info("room grpc started, machineId:{}, port:{}", machineId, grpcPort());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("start room grpc failed", exception);
        }
    }

    public int broadcast(RoomRecord record) {
        return registry.broadcast(record.getRoomId(), RoomFrames.event("MESSAGE_DELIVERED", record, null));
    }

    public int machineId() {
        return machineId;
    }

    public int webSocketPort() {
        return ((InetSocketAddress) webSocketChannel.localAddress()).getPort();
    }

    public int grpcPort() {
        if (grpcServer == null) {
            return -1;
        }
        return grpcServer.getPort();
    }

    public RoomDirectory directory() {
        return directory;
    }

    public MessageTrace trace() {
        return trace;
    }

    public MessageBus bus() {
        return bus;
    }

    public RoomChannelRegistry registry() {
        return registry;
    }

    public RoomDeliveryGrpcService deliveryService() {
        return deliveryService;
    }

    public void attachLogs(com.te.link.core.diag.LinkLogStore logs) {
        router.attachLogs(logs);
    }

    /**
     * 退出下行消费组。验收用它把路由固定到仍在组内的节点。
     */
    public void stopDownstream() {
        AutoCloseable current = subscription;
        subscription = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (Exception ignored) {
            // 订阅关闭失败不阻止后续断言
        }
    }

    public boolean localRoomEmpty(long roomId) {
        return registry.isEmpty(roomId);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // 订阅关闭失败不阻止端口释放
            }
        }
        if (grpcServer != null) {
            grpcServer.shutdownNow();
        }
        if (webSocketChannel != null) {
            webSocketChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private void refreshLeases() {
        for (Long roomId : registry.roomIds()) {
            if (!registry.isEmpty(roomId)) {
                directory.refresh(roomId, machineId, RoomFeature.LEASE_SECONDS);
            }
        }
    }

    static RoomRecord parse(String payload) {
        return JsonUtil.fromJson(payload, RoomRecord.class);
    }
}
