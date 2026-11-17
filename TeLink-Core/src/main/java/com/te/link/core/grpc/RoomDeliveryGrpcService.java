package com.te.link.core.grpc;

import com.te.link.common.grpc.RoomDeliveryRequest;
import com.te.link.common.grpc.RoomDeliveryResponse;
import com.te.link.common.grpc.RoomDeliveryServiceGrpc;
import com.te.link.common.utils.JsonUtil;
import com.te.link.core.room.RoomRecord;
import io.grpc.stub.StreamObserver;

import java.util.function.ToIntFunction;

/**
 * 目标节点收到后只广播本机房间，不再写入 Kafka，也不会继续转发。
 */
public class RoomDeliveryGrpcService extends RoomDeliveryServiceGrpc.RoomDeliveryServiceImplBase {
    private final ToIntFunction<RoomRecord> localBroadcast;

    public RoomDeliveryGrpcService(ToIntFunction<RoomRecord> localBroadcast) {
        this.localBroadcast = localBroadcast;
    }

    @Override
    public void deliverRoomMessage(RoomDeliveryRequest request, StreamObserver<RoomDeliveryResponse> responseObserver) {
        RoomRecord record = JsonUtil.fromJson(request.getPayloadJson(), RoomRecord.class);
        int delivered = localBroadcast.applyAsInt(record);
        responseObserver.onNext(RoomDeliveryResponse.newBuilder()
                .setAccepted(true)
                .setDeliveredCount(delivered)
                .build());
        responseObserver.onCompleted();
    }
}
