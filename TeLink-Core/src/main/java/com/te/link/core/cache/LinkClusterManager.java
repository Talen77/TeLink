package com.te.link.core.cache;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.te.link.core.config.NacosRegisterConfig;
import io.netty.channel.Channel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author: Talen
 * description: 维护两类数据
 *              1.te-Link集群信息，机器id -> 机器ip，由注册中心模块维护
 *              2.当前机器与其他机器建立的channel，用于下行消息的转发， 由当前机器维护 todo 如果channel断开了，要清理map
 */
public class LinkClusterManager {

    // targetMachineId -> targetMachineIp
    private static final ConcurrentHashMap<Integer, String> clusterMachineIpMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Integer> clusterGrpcPortMap = new ConcurrentHashMap<>();

    // 当前机器 与 目标机器 建立的channel targetMachineId -> channel
    @Deprecated
    private static final ConcurrentHashMap<Integer, Channel> LinkId2ChannelMap = new ConcurrentHashMap<>();

    // nacos 监听器触发
    public static void updateClusterInstances(Set<Instance> newInstances) {
        // 先清理，再添加
        clusterMachineIpMap.clear();
        clusterGrpcPortMap.clear();
        for (Instance instance : newInstances) {
            int machineId = Integer.parseInt(instance.getMetadata().get(NacosRegisterConfig.MACHINE_ID_KEY));
            clusterMachineIpMap.put(machineId, instance.getIp());
            String grpcPort = instance.getMetadata().get("te_grpc_port");
            if (grpcPort != null && !grpcPort.isBlank()) {
                clusterGrpcPortMap.put(machineId, Integer.parseInt(grpcPort));
            }
        }
    }

    public static String getClusterMachineIp(int machineId) {
        return clusterMachineIpMap.get(machineId);
    }

    public static int getGrpcPort(int machineId) {
        return clusterGrpcPortMap.getOrDefault(machineId, com.te.link.core.config.GrpcConfig.GRPC_PORT);
    }

    @Deprecated
    public static void addLinkId2Channel(int machineId, Channel channel) {
        LinkId2ChannelMap.put(machineId, channel);
    }

    @Deprecated
    public static void removeLinkId2Channel(int machineId) {
        LinkId2ChannelMap.remove(machineId);
    }

    @Deprecated
    public static Channel getLinkId2Channel(int machineId) {
        return LinkId2ChannelMap.get(machineId);
    }
}
