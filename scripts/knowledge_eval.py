"""生成 100 篇运维说明，上传到知识库，并用固定问题测量 Hit@1、Hit@3 和类别 Precision@3。"""
import json
import time
import urllib.parse
import urllib.request

CATEGORIES = [
    ("jvm", "JVM 堆与 GC", [
        ("Full GC 后老年代仍然占满", "老年代", "Full GC 结束后老年代占用不下降，先看晋升失败和堆外引用"),
        ("Young GC 间隔突然变短", "年轻代", "Young GC 间隔从数秒缩到几百毫秒，说明年轻代分配过快"),
        ("GC 停顿超过一秒", "停顿", "单次 GC 停顿超过一秒时要区分 Young GC 和 Full GC"),
        ("堆外直接内存持续上涨", "直接内存", "堆使用不高但进程 RSS 上涨，检查直接内存和 DirectByteBuffer"),
        ("元空间持续扩张", "元空间", "Metaspace 只增不减，排查类加载器和动态代理生成"),
        ("大对象直接进入老年代", "大对象", "超过大对象阈值的数组会绕过年轻代，加快 Full GC"),
        ("System.gc 被频繁显式调用", "显式回收", "业务代码调用 System.gc 会造成可预期之外的 Full GC"),
        ("GC 日志显示晋升失败", "晋升失败", "晋升失败会触发 Full GC，要看存活对象大小是否超过老年代剩余"),
        ("堆转储里字符串占用过高", "字符串", "堆转储中 String 和 char 数组占比高，检查重复缓存和日志拼接"),
        ("JIT 编译后堆分配抖动", "编译", "方法编译完成前后分配速率变化，不要把预热阶段当成泄漏"),
    ]),
    ("cpu", "CPU 与线程", [
        ("进程 CPU 高但系统 CPU 不高", "进程CPU", "cpuUsage 高而 systemCpuUsage 不高，热点在本 JVM"),
        ("系统 CPU 高而进程 CPU 很低", "系统CPU", "systemCpuUsage 高、cpuUsage 很低时压力在其他进程"),
        ("线程数持续上涨", "线程泄漏", "线程数只增不减，检查未关闭的线程池和按请求建线程"),
        ("BLOCKED 线程堆积", "锁等待", "大量 BLOCKED 线程说明同一把锁被长时间持有"),
        ("CPU 采样里 safepoint 偏多", "安全点", "安全点停顿拉高 CPU 等待，检查偏向锁撤销和 GC"),
        ("上下文切换过高", "切换", "上下文切换高而业务量低，线程数可能超过核心数很多"),
        ("单个工作线程打满一核", "单核", "一个 Netty worker 打满一核，看是否有同步阻塞写在事件循环里"),
        ("启动后 CPU 短时升高", "预热", "进程刚启动时 JIT 和类加载会抬高 CPU，观察窗口不足不要下结论"),
        ("定时任务同一秒扎堆", "定时", "多个定时任务在同一秒触发，CPU 呈尖峰而不是持续高位"),
        ("日志同步写放大 CPU", "日志", "同步写日志在高 QPS 下会占用大量 CPU 和线程"),
    ]),
    ("memory", "内存", [
        ("系统内存高不等于堆泄漏", "系统内存", "systemMemoryUsed 高而 heapUsed 低，先排除其他进程和页缓存"),
        ("容器内存限制导致提前 Full GC", "容器", "容器 limit 小于堆上限时，进程会在堆尚未满时被限制"),
        ("堆使用率长期低于百分之十", "低占用", "heapUsed 远小于 heapMax 时不需要申请回收"),
        ("RSS 高于堆上限", "常驻内存", "RSS 包含堆、元空间、线程栈和直接内存，不能只看堆"),
        ("页缓存把可用内存显示得很低", "页缓存", "Linux 可用内存低可能是页缓存，不等于进程泄漏"),
        ("堆内存申请后没有归还操作系统", "归还", "堆占用下降后 RSS 不一定马上下降，JVM 可以保留空闲堆"),
        ("NMT 显示线程栈占比高", "线程栈", "线程数量乘以栈大小会形成堆外占用"),
        ("本地缓存没有上限", "本地缓存", "无上限的本地 Map 会让 heapUsed 随时间单调上升"),
        ("复制流量导致年轻代暴涨", "复制", "短时间大消息会抬高年轻代，结束后应回落"),
        ("交换分区开始使用", "交换", "出现 swap 后延迟会陡增，应先降负载而不是只做 GC"),
    ]),
    ("kafka", "Kafka", [
        ("message_in 消费滞后", "上行滞后", "message_in 的 lag 增大说明业务进程消费慢于写入"),
        ("message_out 消费组重平衡", "重平衡", "推送节点重启会触发 message_out 消费组重平衡，期间投递变慢"),
        ("同一房间消息顺序", "房间顺序", "roomId 作为 Kafka key 才能让同一房间进入同一分区"),
        ("消费位点提交过早", "位点", "处理完成前提交 offset 会在失败后丢消息"),
        ("生产者缓冲打满", "缓冲", "Kafka 生产者 buffer 满时发送会阻塞或失败"),
        ("副本不足导致写入变慢", "副本", "acks 要求的副本不可用时生产请求超时"),
        ("死信主题堆积", "死信", "knowledge_ingest_dlq 出现消息说明索引任务反复失败"),
        ("消费者 poll 间隔超时", "poll", "单次处理超过 max.poll.interval 会被踢出消费组"),
        ("分区数小于节点数", "分区", "分区少于消费者时部分节点分不到 message_out"),
        ("消息键为空导致倾斜", "倾斜", "空 key 会让消息集中到部分分区"),
    ]),
    ("redis", "Redis", [
        ("节点快照三十秒过期", "快照过期", "节点快照 TTL 约 30 秒，过期后列表应显示离线而不是删除历史"),
        ("房间租约没有续期", "房间租约", "房间路由租约到期后跨节点投递会找不到成员"),
        ("Redis 连接池耗尽", "连接池", "Jedis 连接池打满时心跳和快照上报一起失败"),
        ("慢查询阻塞快照写入", "慢查询", "Redis 慢查询会推迟节点快照，页面上的 CPU 会停在旧值"),
        ("内存淘汰误删路由", "淘汰", "Redis 内存淘汰可能删掉房间成员键，表现为房间突然没人"),
        ("主从切换后读到旧值", "主从", "切换瞬间可能读到过期路由，投递会短时失败"),
        ("大 key 的房间成员集合", "大key", "单个房间成员集合过大时续租会变慢"),
        ("密码错误导致上报失败", "密码", "Redis 密码不一致时节点快照全部停止刷新"),
        ("键前缀混用", "前缀", "TeLink 的管理键前缀是 TeLink_，不要和旧键混查"),
        ("持久化阻塞", "持久化", "RDB 或 AOF 重写时延迟升高，心跳续期会抖动"),
    ]),
    ("mysql", "MySQL", [
        ("push_message 与节点计数不一致", "消息计数", "累计消息数来自 push_message，节点经过数来自 push_message_node"),
        ("连接数打满", "连接打满", "MySQL 连接打满后日志和消息写入失败，但 WebSocket 仍可能接受"),
        ("慢查询拖住管理接口", "慢查询", "按房间查询消息如果缺索引，管理页会超时"),
        ("时区导致时间差八小时", "时区", "容器 UTC 与东八区写入混用时，重试时间会偏差"),
        ("磁盘快满", "磁盘", "数据目录磁盘快满时提交事务变慢，消息状态停在中间态"),
        ("死锁重试", "死锁", "同一 logId 并发更新可能死锁，应重试而不是当成投递失败"),
        ("二进制日志膨胀", "binlog", "binlog 不清理会占满磁盘，间接拖慢消息入库"),
        ("只读副本被误写入", "只读", "管理写入打到只读实例会全部失败"),
        ("字符集截断正文", "字符集", "消息正文被截断时检查连接字符集是否为 utf8mb4"),
        ("备份窗口锁表", "备份", "备份锁表期间消息查询和写入都会变慢"),
    ]),
    ("room", "房间与 WebSocket", [
        ("一个连接只能在一个房间", "单房间", "同一连接切房会离开旧房间，不会同时待在两个房间"),
        ("未入房就发消息被拒绝", "未入房", "没有 JOIN 或 CREATE 就发 CHAT_MESSAGE 会收到 MESSAGE_REJECTED"),
        ("入口节点和路由节点不同", "路由节点", "message_out 由共享消费组里的任意节点取出，所以路由节点不一定是入口"),
        ("两次 MESSAGE_DELIVERED", "两次送达", "每个实际广播的节点各记一次 DELIVERED，不是同一次投递记重"),
        ("跨节点走 gRPC", "跨节点", "其他节点上的房间成员通过 DeliverRoomMessage 转发，不走 Redis 频道"),
        ("房间不存在时加入失败", "房间不存在", "JOIN 一个没有租约的房间会返回房间不存在"),
        ("建房冲突", "建房冲突", "房间号已存在时 CREATE 失败，应改为加入"),
        ("发送者也会收到自己的消息", "自发自收", "房间广播包含发送者自己的连接"),
        ("连接断开后房间租约", "断开", "最后一个连接离开后该节点上的房间路由会被摘掉"),
        ("客户端随机选节点", "随机节点", "用户端从 client-nodes 返回的地址里随机连接"),
    ]),
    ("grpc", "集群与 gRPC", [
        ("Nacos 元数据缺少端口", "元数据", "注册时必须写入 machine_id 和 te_grpc_port，否则无法转发"),
        ("gRPC 端口冲突", "端口冲突", "同一台机器四个节点要使用不同的 TCP 和 gRPC 端口"),
        ("目标节点 gRPC 不可达", "不可达", "DeliverRoomMessage 连接失败时该节点的成员收不到，本机广播仍继续"),
        ("注册中心短暂不可用", "注册中心", "Nacos 抖动时新节点无法发现，已建立的连接仍可本机收发"),
        ("机器号重复", "机器号", "重启若再次自增机器号，会出现新的离线历史节点"),
        ("管理接口只开一个", "管理接口", "telink.admin.enabled 只应在一个节点打开，避免 9090 冲突"),
        ("时钟偏差导致采样过期", "时钟", "节点时钟超前或落后超过窗口会被当成采样过期"),
        ("实例重启后 instanceId 变化", "实例", "进程重启会换 instanceId，已批准的旧计划不能在新实例执行"),
        ("同机回环转发", "回环", "本机成员直接广播，不需要再给自己发一次 gRPC"),
        ("转发失败不阻断发送者回执", "回执", "入口节点写入 Kafka 成功就返回 MESSAGE_ACCEPTED，不等待全部节点送达"),
    ]),
    ("ops", "人工审核", [
        ("审核默认关闭", "默认关闭", "没有打开 operations.enabled 且没有审核密钥时，页面提示尚未启用"),
        ("REQUEST_GC 不保证内存下降", "请求回收", "REQUEST_GC 只调用 System.gc，JVM 可以忽略，也不释放其他进程内存"),
        ("RESTART 需要固定命令", "重启命令", "没有 deploymentId 和 restartCommand 时不能申请 RESTART"),
        ("健康节点不要申请操作", "健康节点", "堆占用很低且 CPU 正常时结论应为 NONE，不要提交审核"),
        ("批准必须输入确认文字", "确认文字", "批准时要填写卡片给出的确认文字和审核密钥"),
        ("目标节点自己认领", "认领", "批准后的计划只由 machineId 指向的那个进程执行"),
        ("审核卡片出现在对话下方", "审核卡片", "申请成功后卡片在当前对话和操作审核页同时可见"),
        ("知识引用必须是本轮检索结果", "知识引用", "没有真实 chunkId 的申请会被拒绝，不能凭空编造依据"),
        ("旧实例的计划失效", "旧实例", "节点重启后 instanceId 变化，旧的已批准计划不能继续执行"),
        ("拒绝申请不会执行", "拒绝", "拒绝后状态变为已拒绝，节点不会认领"),
    ]),
    ("log", "日志与链路", [
        ("LogID 对应 trace_id", "链路标识", "页面上的 LogID 存在 push_log.trace_id，也等于 push_message.log_id"),
        ("日志中心每十秒刷新", "十秒刷新", "日志页自动刷新不应把用户已经展开的行收回去"),
        ("源码位置来自调用栈", "源码位置", "sourceFilePath 和 sourceLine 用于回到打印日志的那一行"),
        ("同一 LogID 有多条日志", "多条日志", "接收、接受、业务处理、路由、送达会各写一条日志"),
        ("按机器查询只看该节点", "按机器", "machineId 模式只返回该节点打印的日志，看不到其他节点的同链路日志"),
        ("ERROR 才适合作为检索词", "错误日志", "用异常文本检索知识，不要只把 LogID 本身拿去检索"),
        ("日志写入失败不影响收发", "日志失败", "MySQL 日志写失败时消息收发仍应继续，页面上可能暂时查不到"),
        ("展开行被刷新覆盖", "展开", "刷新列表时如果清空展开状态，用户会看到刚打开的日志合上"),
        ("级别过滤", "级别", "INFO、WARN、ERROR 过滤是在查询时生效，不是前端藏起来"),
        ("时间显示差八小时", "日志时间", "日志时间来自 JVM 本地时间，消息表时间可能是数据库 UTC"),
    ]),
]
assert sum(len(items) for _, _, items in CATEGORIES) == 100


def upload(filename, title, component, text):
    query = urllib.parse.urlencode({
        "filename": filename,
        "title": title,
        "component": component,
        "service": "telink",
        "applicableVersion": "1",
    })
    request = urllib.request.Request(
        "http://127.0.0.1:9093/knowledge/documents?" + query,
        data=text.encode("utf-8"),
        method="POST",
        headers={"Content-Type": "text/plain; charset=utf-8"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode())["documentId"]


def wait_ready(doc_id):
    for _ in range(45):
        time.sleep(2)
        with urllib.request.urlopen(f"http://127.0.0.1:9093/knowledge/documents/{doc_id}", timeout=10) as response:
            detail = json.loads(response.read().decode())
        status = (detail.get("versions") or [{}])[0].get("status")
        if status in ("READY", "FAILED"):
            return status
    return "TIMEOUT"


def search(query):
    request = urllib.request.Request(
        "http://127.0.0.1:9093/knowledge/search",
        data=json.dumps({"query": query, "topK": 3}).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=40) as response:
        body = json.loads(response.read().decode())
    return body.get("matches") or []


def main():
    cases = []
    index = 1
    for component, category, items in CATEGORIES:
        for title, keyword, symptom in items:
            code = f"KB-{index:03d}"
            full_title = f"{code} {title}"
            text = (
                f"{full_title}\n类别：{category}\n关键词：{keyword}\n"
                f"现象：{symptom}\n"
                f"处置：先核对节点自己上报的 fresh 指标，再决定是否需要人工审核。"
                f"本篇只讨论{keyword}，不要把它用到无关故障。\n"
            )
            doc_id = upload(f"{code}.txt", full_title, component, text)
            print("queued", code, doc_id, flush=True)
            cases.append({
                "code": code,
                "title": full_title,
                "component": component,
                "category": category,
                "documentId": doc_id,
                "query": f"{keyword}。{symptom}",
            })
            index += 1
    ready = 0
    for case in cases:
        status = wait_ready(case["documentId"])
        ready += status == "READY"
        print(case["code"], status, flush=True)
    hit1 = hit3 = precision_hits = 0
    for case in cases:
        matches = search(case["query"])
        titles = [item.get("title", "") for item in matches]
        if titles and titles[0] == case["title"]:
            hit1 += 1
        if case["title"] in titles:
            hit3 += 1
        precision_hits += sum(1 for item in matches if item.get("component") == case["component"])
    total = len(cases)
    print(json.dumps({
        "ready": ready,
        "documents": total,
        "hitAt1": round(hit1 / total, 3),
        "hitAt3": round(hit3 / total, 3),
        "precisionAt3": round(precision_hits / (total * 3), 3),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
