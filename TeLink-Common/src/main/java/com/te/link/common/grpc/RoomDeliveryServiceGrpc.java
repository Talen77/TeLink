package com.te.link.common.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 房间下行只在目标节点的本机连接上广播，接收方不得再次转发。
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.55.1)",
    comments = "Source: room_delivery.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RoomDeliveryServiceGrpc {

  private RoomDeliveryServiceGrpc() {}

  public static final String SERVICE_NAME = "RoomDeliveryService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.te.link.common.grpc.RoomDeliveryRequest,
      com.te.link.common.grpc.RoomDeliveryResponse> getDeliverRoomMessageMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeliverRoomMessage",
      requestType = com.te.link.common.grpc.RoomDeliveryRequest.class,
      responseType = com.te.link.common.grpc.RoomDeliveryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.te.link.common.grpc.RoomDeliveryRequest,
      com.te.link.common.grpc.RoomDeliveryResponse> getDeliverRoomMessageMethod() {
    io.grpc.MethodDescriptor<com.te.link.common.grpc.RoomDeliveryRequest, com.te.link.common.grpc.RoomDeliveryResponse> getDeliverRoomMessageMethod;
    if ((getDeliverRoomMessageMethod = RoomDeliveryServiceGrpc.getDeliverRoomMessageMethod) == null) {
      synchronized (RoomDeliveryServiceGrpc.class) {
        if ((getDeliverRoomMessageMethod = RoomDeliveryServiceGrpc.getDeliverRoomMessageMethod) == null) {
          RoomDeliveryServiceGrpc.getDeliverRoomMessageMethod = getDeliverRoomMessageMethod =
              io.grpc.MethodDescriptor.<com.te.link.common.grpc.RoomDeliveryRequest, com.te.link.common.grpc.RoomDeliveryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeliverRoomMessage"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.te.link.common.grpc.RoomDeliveryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.te.link.common.grpc.RoomDeliveryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RoomDeliveryServiceMethodDescriptorSupplier("DeliverRoomMessage"))
              .build();
        }
      }
    }
    return getDeliverRoomMessageMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RoomDeliveryServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RoomDeliveryServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RoomDeliveryServiceStub>() {
        @java.lang.Override
        public RoomDeliveryServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RoomDeliveryServiceStub(channel, callOptions);
        }
      };
    return RoomDeliveryServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RoomDeliveryServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RoomDeliveryServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RoomDeliveryServiceBlockingStub>() {
        @java.lang.Override
        public RoomDeliveryServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RoomDeliveryServiceBlockingStub(channel, callOptions);
        }
      };
    return RoomDeliveryServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RoomDeliveryServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RoomDeliveryServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RoomDeliveryServiceFutureStub>() {
        @java.lang.Override
        public RoomDeliveryServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RoomDeliveryServiceFutureStub(channel, callOptions);
        }
      };
    return RoomDeliveryServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 房间下行只在目标节点的本机连接上广播，接收方不得再次转发。
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void deliverRoomMessage(com.te.link.common.grpc.RoomDeliveryRequest request,
        io.grpc.stub.StreamObserver<com.te.link.common.grpc.RoomDeliveryResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeliverRoomMessageMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RoomDeliveryService.
   * <pre>
   * 房间下行只在目标节点的本机连接上广播，接收方不得再次转发。
   * </pre>
   */
  public static abstract class RoomDeliveryServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RoomDeliveryServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RoomDeliveryService.
   * <pre>
   * 房间下行只在目标节点的本机连接上广播，接收方不得再次转发。
   * </pre>
   */
  public static final class RoomDeliveryServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RoomDeliveryServiceStub> {
    private RoomDeliveryServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RoomDeliveryServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RoomDeliveryServiceStub(channel, callOptions);
    }

    /**
     */
    public void deliverRoomMessage(com.te.link.common.grpc.RoomDeliveryRequest request,
        io.grpc.stub.StreamObserver<com.te.link.common.grpc.RoomDeliveryResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeliverRoomMessageMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RoomDeliveryService.
   * <pre>
   * 房间下行只在目标节点的本机连接上广播，接收方不得再次转发。
   * </pre>
   */
  public static final class RoomDeliveryServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RoomDeliveryServiceBlockingStub> {
    private RoomDeliveryServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RoomDeliveryServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RoomDeliveryServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.te.link.common.grpc.RoomDeliveryResponse deliverRoomMessage(com.te.link.common.grpc.RoomDeliveryRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeliverRoomMessageMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RoomDeliveryService.
   * <pre>
   * 房间下行只在目标节点的本机连接上广播，接收方不得再次转发。
   * </pre>
   */
  public static final class RoomDeliveryServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RoomDeliveryServiceFutureStub> {
    private RoomDeliveryServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RoomDeliveryServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RoomDeliveryServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.te.link.common.grpc.RoomDeliveryResponse> deliverRoomMessage(
        com.te.link.common.grpc.RoomDeliveryRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeliverRoomMessageMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_DELIVER_ROOM_MESSAGE = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_DELIVER_ROOM_MESSAGE:
          serviceImpl.deliverRoomMessage((com.te.link.common.grpc.RoomDeliveryRequest) request,
              (io.grpc.stub.StreamObserver<com.te.link.common.grpc.RoomDeliveryResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getDeliverRoomMessageMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.te.link.common.grpc.RoomDeliveryRequest,
              com.te.link.common.grpc.RoomDeliveryResponse>(
                service, METHODID_DELIVER_ROOM_MESSAGE)))
        .build();
  }

  private static abstract class RoomDeliveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RoomDeliveryServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.te.link.common.grpc.RoomDelivery.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RoomDeliveryService");
    }
  }

  private static final class RoomDeliveryServiceFileDescriptorSupplier
      extends RoomDeliveryServiceBaseDescriptorSupplier {
    RoomDeliveryServiceFileDescriptorSupplier() {}
  }

  private static final class RoomDeliveryServiceMethodDescriptorSupplier
      extends RoomDeliveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    RoomDeliveryServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (RoomDeliveryServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RoomDeliveryServiceFileDescriptorSupplier())
              .addMethod(getDeliverRoomMessageMethod())
              .build();
        }
      }
    }
    return result;
  }
}
