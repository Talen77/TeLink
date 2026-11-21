package com.te.link.core.room;

import com.te.link.common.grpc.RoomDeliveryServiceGrpc;
import com.te.link.common.grpc.RoomDeliveryRequest;
import com.te.link.common.grpc.RoomDeliveryResponse;
import com.te.link.core.cache.LinkClusterManager;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

public class GrpcRoomFanout implements RoomFanout, AutoCloseable {
    private final IntFunction<Target> targets;
    private final Map<Integer, ManagedChannel> channels = new ConcurrentHashMap<>();

    public GrpcRoomFanout(IntFunction<Target> targets) {
        this.targets = targets;
    }

    public static GrpcRoomFanout nacos() {
        return new GrpcRoomFanout(machineId -> {
            String ip = LinkClusterManager.getClusterMachineIp(machineId);
            if (ip == null || ip.isBlank()) {
                return null;
            }
            return new Target(ip, LinkClusterManager.getGrpcPort(machineId));
        });
    }

    @Override
    public boolean deliver(int machineId, String payloadJson) {
        Target target = targets.apply(machineId);
        if (target == null) {
            return false;
        }
        ManagedChannel channel = channels.computeIfAbsent(machineId, ignored ->
                ManagedChannelBuilder.forAddress(target.host, target.port).usePlaintext().build());
        try {
            RoomDeliveryResponse response = RoomDeliveryServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .deliverRoomMessage(RoomDeliveryRequest.newBuilder().setPayloadJson(payloadJson).build());
            return response.getAccepted() && response.getDeliveredCount() > 0;
        } catch (StatusRuntimeException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        for (ManagedChannel channel : channels.values()) {
            channel.shutdownNow();
        }
    }

    public record Target(String host, int port) {
    }
}
