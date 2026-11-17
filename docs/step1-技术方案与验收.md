# 第一步：地基和推送闭环

本文件是第一步的实现合同。开发和验收都按这里执行。和最初口头方案有三处调整，原因写在对应小节。

## 目标

浏览器通过 WebSocket 进入房间并发送消息。消息先进入 Kafka `message_in`，独立业务进程处理后再写入 `message_out`。某个推送节点消费下行消息，按 Redis 里的房间成员，把消息交给本机连接，或通过 gRPC 交给其他节点。每条消息有 `logId`。状态只允许向前。

原有 TCP `9999`、登录、心跳、gRPC `push2Link` 保持原样。房间链路默认不启动，避免本机没有 Kafka 或 MySQL 时旧中台起不来。

## 调整

1. 房间下行 gRPC 加在现有 `10001` 端口上，和 `Push2Link` 并列，不新开端口。Nacos 里已经登记了 `te_grpc_port=10001`。
2. `MESSAGE_ACCEPTED` 和 `MESSAGE_DELIVERED` 走两条异步路径。验收要求两条都到达且 `logId` 相同，不要求套接字上的先后顺序。
3. 行为验收在一个 JVM 里启动多个真实 WebSocket 端口和真实 gRPC 端口，Kafka、Redis、MySQL 使用同一套接口的内存实现。生产启动仍使用真实 Kafka、Redis、MySQL。这样验收不依赖 `RedisConfig` 里的远程地址。

## 进程和端口

| 进程 | 入口 | 端口 |
| --- | --- | --- |
| 推送节点 | `com.te.link.bootstrap.Bootstrap` | TCP `9999`、gRPC `10001`、WebSocket `8082`（仅 `-Dtelink.room.enabled=true`） |
| 业务进程 | `com.te.link.service.ServiceStart` | 无监听端口 |

启用房间链路时还要提供：

```text
-Dtelink.room.enabled=true
-Dtelink.kafka.bootstrap=127.0.0.1:9092
-Dtelink.mysql.url=jdbc:mysql://127.0.0.1:3306/telink?useSSL=false&characterEncoding=utf8
-Dtelink.mysql.user=root
-Dtelink.mysql.password=
-Dtelink.redis.host=127.0.0.1
-Dtelink.redis.port=6379
-Dtelink.redis.password=
```

业务进程使用同一组 Kafka 和 MySQL 参数。表 `room_message_trace` 由业务进程和推送节点在启动时自动创建。脚本同时放在 `TeLink-Core/src/main/resources/sql/room_message_trace.sql`。

## 协议

连接 `ws://{host}:8082/ws`，文本帧为 JSON。

客户端：

```json
{"type":"CREATE_ROOM","roomId":1001,"userId":42}
{"type":"JOIN_ROOM","roomId":1001,"userId":42}
{"type":"CHAT_MESSAGE","messageId":"...","roomId":1001,"userId":42,"content":"hello","sentAt":1710000000000}
```

服务端：`ROOM_CREATED`、`ROOM_JOINED`、`ROOM_REJECTED`、`MESSAGE_ACCEPTED`、`MESSAGE_REJECTED`、`MESSAGE_DELIVERED`。

接受和投递帧都带 `logId`、`messageId`、`roomId`。一个连接同时只在一个房间。建房时已有未过期成员则拒绝。加入时没有成员则拒绝。断开连接后，若本机该房间已没有连接，就删掉本机的房间成员。

## 数据

Kafka：

- `message_in`：推送节点写入，业务消费组 `telink-business`。Key 是 `roomId`。
- `message_out`：业务进程写入，推送节点消费组 `telink-message-out`。一条记录只由组内一个节点路由。

Redis：

- `telink:room:{roomId}:nodes`：machineId 集合。
- `telink:room:{roomId}:node:{machineId}`：租约，90 秒。本机每 30 秒对仍有连接的房间续租。
- `telink:node:{machineId}:rooms`：该节点当前房间，供后续管理端查询。

MySQL `room_message_trace`：

| 状态 | rank |
| --- | --- |
| RECEIVED | 10 |
| ACCEPTED | 20 |
| BUSINESS_PROCESSED | 30 |
| ROUTING | 35 |
| FAILED | 40 |
| DELIVERED | 50 |

新 rank 小于当前 rank 时更新被忽略。因此 `DELIVERED` 不会退回 `ACCEPTED`，也可以覆盖此前的 `FAILED`。

## 代码位置

- `TeLink-Common`：`room_delivery.proto`，生成 `DeliverRoomMessage`。
- `TeLink-Core` 的 `com.te.link.core.room`：连接表、目录、消息总线、轨迹、路由、WebSocket、节点组装。
- `TeLink-Core` 的 `RoomDeliveryGrpcService`：只对本机房间广播，不再写入 Kafka。
- `TeLink-Service`：消费 `message_in`，原样写回 `message_out`，并记录 `BUSINESS_PROCESSED`。
- `TeLink-UserClient/index.html`：手工打开两个页面时使用。自动验收不依赖它。

## 验收

自动验收类是 `Step1RoomPushAcceptanceTest`。全部断言通过才算第一步完成。

1. 同一节点上两个 WebSocket 客户端进入房间 `1001`。发送方收到 `MESSAGE_ACCEPTED`，两个客户端都收到 `MESSAGE_DELIVERED`，`logId` 相同，轨迹状态为 `DELIVERED`。
2. 不启动业务消费时再发一条，发送方收到 `MESSAGE_ACCEPTED`，等待之后仍然没有 `MESSAGE_DELIVERED`。
3. 节点 A、C 持有房间，B 只有一条未入房的连接。消息只出现在 A 和 C。验收在发送前让 A、C 退出下行消费组，使 B 成为唯一路由节点；B 只通过 gRPC 发给 A 和 C，自己的连接收不到 `MESSAGE_DELIVERED`。
4. 唯一连接断开后，本机房间表为空，目录里也不再包含该节点。
5. 状态已是 `DELIVERED` 时，再写入 `ACCEPTED`，状态保持 `DELIVERED`。
6. `ProcessorFactory` 仍把登录和心跳交给原来的处理器。`Push2Link` 的协议和服务实现类不被替换。
