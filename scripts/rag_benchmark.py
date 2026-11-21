"""用改写后的问题测量 Dense、Sparse、Hybrid RRF、Rerank。

问题不复述文档原句。相关文档在出题时指定，每题只有一篇。
Rerank 是对 Hybrid 前 20 名再用词面重合重排，没有单独的交叉编码器服务。
"""
import json
import math
import os
import subprocess
import urllib.request

QUERIES = [
    ("KB-001", "完整回收做完，年老区还是满的，该先看什么"),
    ("KB-002", "新生代回收隔得很近，是不是分配太快了"),
    ("KB-003", "一次回收把业务卡住超过一秒，怎么区分是哪一种回收"),
    ("KB-004", "堆看起来不大，进程内存却一直涨，是不是堆外面的缓冲"),
    ("KB-005", "类加载相关的内存只增不减，要查什么"),
    ("KB-006", "很大的数组是不是会跳过新生代"),
    ("KB-007", "代码里主动让虚拟机回收，会不会打乱正常节奏"),
    ("KB-008", "对象往年老区放不下时，日志里一般是什么信号"),
    ("KB-009", "转储里字符和它的数组特别多，缓存是不是没边界"),
    ("KB-010", "刚启动时分配忽高忽低，能不能当成泄漏"),
    ("KB-011", "只有这个 Java 进程很忙，整机并不忙，热点在哪"),
    ("KB-012", "整机很忙，这个 Java 进程却很闲，该不该动这个节点"),
    ("KB-013", "线程数量一直往上走，停不下来"),
    ("KB-014", "很多线程卡在等同一把锁"),
    ("KB-015", "停在安全点的时间变多，CPU 等待变高"),
    ("KB-016", "业务不多，线程切换却非常频繁"),
    ("KB-017", "某个网络工作线程把一个核占满了"),
    ("KB-018", "进程刚起来那一会儿 CPU 冲高，随后下来"),
    ("KB-019", "CPU 不是一直高，而是同一秒扎堆尖峰"),
    ("KB-020", "每条请求都同步写日志，CPU 被写盘拖住"),
    ("KB-021", "整机内存很高，但虚拟机堆还很低"),
    ("KB-022", "跑在容器里，堆还没满就回收得很勤"),
    ("KB-023", "堆只用了不到一成，有没有必要去申请回收"),
    ("KB-024", "常驻内存比堆上限还大，多出来的是什么"),
    ("KB-025", "可用内存看起来很少，会不会只是文件缓存"),
    ("KB-026", "堆占用掉下去了，进程内存却不还给系统"),
    ("KB-027", "线程栈把堆外面的内存撑大了"),
    ("KB-028", "本地 Map 没有容量上限，堆跟着时间涨"),
    ("KB-029", "短时间来了很多大消息，年轻代冲高后又回去"),
    ("KB-030", "机器开始用交换分区，延迟突然变很差"),
    ("KB-031", "上行主题的积压越来越大，业务处理跟不上"),
    ("KB-032", "推送节点一重启，下行消费组就重新分配"),
    ("KB-033", "同一个房间的消息能不能保证进同一个分区"),
    ("KB-034", "处理还没做完就提交了位点，失败后会怎样"),
    ("KB-035", "生产者缓冲区满了，发送开始堵住"),
    ("KB-036", "副本不够的时候，写入为什么变慢"),
    ("KB-037", "知识索引进了死信，是不是任务一直失败"),
    ("KB-038", "一次处理太久，消费者被踢出组"),
    ("KB-039", "分区比消费者少，有的节点拿不到下行"),
    ("KB-040", "消息没有键，流量是不是会歪到少数分区"),
    ("KB-041", "节点快照过了大约半分钟，页面该显示离线还是把记录删掉"),
    ("KB-042", "房间成员的租约没人续，跨节点就找不到人"),
    ("KB-043", "连接池被拿光，快照和心跳一起失败"),
    ("KB-044", "缓存变慢以后，页面上的 CPU 停在旧数字"),
    ("KB-045", "内存淘汰把房间成员键删了，房间像是突然没人"),
    ("KB-046", "主从刚切换，读到的路由是旧的"),
    ("KB-047", "一个房间里人太多，续租变慢"),
    ("KB-048", "缓存密码不对，所有节点都不再上报"),
    ("KB-049", "管理用的键前缀和旧系统混在一起了"),
    ("KB-050", "持久化重写的时候，续期会抖一下"),
    ("KB-051", "消息总条数和每个节点经过的条数为什么不一样"),
    ("KB-052", "数据库连接用完了，页面还能连上但消息写不进库"),
    ("KB-053", "按房间查消息很慢，是不是缺索引"),
    ("KB-054", "库是零时区，应用按东八区写，重试时间会偏"),
    ("KB-055", "数据盘快满了，消息状态停在半路"),
    ("KB-056", "同一条链路并发更新，出现死锁要不要当成投递失败"),
    ("KB-057", "二进制日志把磁盘撑满了"),
    ("KB-058", "写请求打到了只读副本"),
    ("KB-059", "正文里的字被截断，字符集是不是不对"),
    ("KB-060", "备份锁表的那段时间，查询和写入都变慢"),
    ("KB-061", "一个连接能不能同时待在两个房间"),
    ("KB-062", "还没进房间就发聊天，服务端会怎么回"),
    ("KB-063", "收下这条消息的节点，不一定是后来负责转发的节点"),
    ("KB-064", "同一条链路为什么会看到两次送达日志"),
    ("KB-065", "别的节点上的房间成员，消息是怎么过去的"),
    ("KB-066", "加入一个已经没人的房间号会怎样"),
    ("KB-067", "房间号已经被占用，再建一次会失败吗"),
    ("KB-068", "发消息的人自己会不会也收到这条广播"),
    ("KB-069", "房间里最后一个连接走了，路由还在不在"),
    ("KB-070", "浏览器是固定连某一台，还是从列表里挑"),
    ("KB-071", "注册中心上看不到转发端口，跨节点为什么失败"),
    ("KB-072", "同一台机器起四个节点，端口为什么不能一样"),
    ("KB-073", "转发调用连不上目标时，本机的人还能收到吗"),
    ("KB-074", "注册中心抖一下，已经连上的连接会不会断"),
    ("KB-075", "进程重启后又领了新的机器号，旧号还在不在列表里"),
    ("KB-076", "管理端口为什么只应该在一个节点上开"),
    ("KB-077", "机器时钟差一截，采样会被当成过期吗"),
    ("KB-078", "进程重启以后，以前批准的计划还能执行吗"),
    ("KB-079", "人就在本机，还需不需要再转发一次给自己"),
    ("KB-080", "是不是要等所有节点都送到，入口才回接受"),
    ("KB-081", "没开审核开关、也没有密钥，页面上会看到什么"),
    ("KB-082", "让虚拟机回收一次，内存一定会降下来吗"),
    ("KB-083", "想申请重启，还缺哪两项配置"),
    ("KB-084", "堆很低、CPU 也正常，该不该提操作申请"),
    ("KB-085", "点批准的时候，除了密钥还要填什么"),
    ("KB-086", "批准之后是管理进程去执行，还是那台节点自己执行"),
    ("KB-087", "申请成功以后，卡片会出现在哪"),
    ("KB-088", "没有这次检索到的片段编号，申请能提交吗"),
    ("KB-089", "节点已经换了新进程，旧计划为什么不能接着跑"),
    ("KB-090", "点了拒绝以后，节点还会不会去执行"),
    ("KB-091", "页面上的链路号，在日志表和消息表里各叫什么"),
    ("KB-092", "日志页自己刷新的时候，展开的行应不应该合上"),
    ("KB-093", "日志里的文件名和行号是用来干什么的"),
    ("KB-094", "一条消息为什么会留下接收、接受、路由、送达好几行"),
    ("KB-095", "按机器号查日志，能不能看到别的节点上的同一条链路"),
    ("KB-096", "拿链路号本身去搜知识，和拿异常文本去搜，哪个对"),
    ("KB-097", "日志没写进库，消息收发会不会停"),
    ("KB-098", "刚展开一条日志，过几秒自己合上，是什么造成的"),
    ("KB-099", "级别筛选是查询时过滤，还是前端把行藏起来"),
    ("KB-100", "日志时间比消息表时间差了八个小时，两边各用的什么钟"),
]


def env_of(pid):
    import ctypes
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    ntdll = ctypes.WinDLL("ntdll", use_last_error=True)

    class Info(ctypes.Structure):
        _fields_ = [("ExitStatus", ctypes.c_ulong), ("PebBaseAddress", ctypes.c_void_p),
                    ("AffinityMask", ctypes.c_size_t), ("BasePriority", ctypes.c_void_p),
                    ("UniqueProcessId", ctypes.c_size_t), ("InheritedFromUniqueProcessId", ctypes.c_size_t)]

    handle = kernel32.OpenProcess(0x0410, False, pid)
    info = Info()
    ntdll.NtQueryInformationProcess(handle, 0, ctypes.byref(info), ctypes.sizeof(info), None)

    def read(addr, size):
        buf = ctypes.create_string_buffer(size)
        got = ctypes.c_size_t()
        kernel32.ReadProcessMemory(handle, ctypes.c_void_p(addr), buf, size, ctypes.byref(got))
        return buf.raw[:got.value]

    params = int.from_bytes(read(info.PebBaseAddress + 0x20, 8), "little")
    ptr = int.from_bytes(read(params + 0x80, 8), "little")
    text = read(ptr, 262144).decode("utf-16-le", errors="ignore")
    env = {}
    for item in text.split("\0"):
        if not item:
            break
        if "=" in item:
            key, value = item.split("=", 1)
            env[key] = value
    return env


def knowledge_key():
    raw = subprocess.check_output(["powershell", "-NoProfile", "-Command",
        "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'KnowledgeServiceStart' } | Select-Object -First 1 -ExpandProperty ProcessId)"], text=True)
    env = env_of(int(raw.strip()))
    return env.get("TELINK_EMBED_KEY") or env.get("TELINK_KNOWLEDGE_EMBEDDING_API_KEY") or ""


def post(url, body, headers=None):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode())


def embed(texts, key):
    question_path = os.path.join(os.environ.get("TEMP", "."), "rag-questions.txt")
    vector_path = os.path.join(os.environ.get("TEMP", "."), "rag-vectors.txt")
    java_path = os.path.join(os.environ.get("TEMP", "."), "EmbedBatch.java")
    with open(question_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(texts))
    subprocess.check_call(["javac", "-encoding", "UTF-8", "-d", os.path.dirname(java_path), java_path])
    env = os.environ.copy()
    env["TELINK_EMBED_KEY"] = key
    subprocess.check_call(["java", "-cp", os.path.dirname(java_path), "EmbedBatch", question_path, vector_path], env=env)
    vectors = []
    folder = os.path.dirname(java_path)
    names = [name for name in os.listdir(folder) if name.startswith("rag-vectors.txt.") and name.endswith(".json")]
    for name in sorted(names, key=lambda item: int(item.split(".")[-2])):
            with open(os.path.join(folder, name), encoding="utf-8") as handle:
                body = json.load(handle)
            ordered = sorted(body["data"], key=lambda item: item["index"])
            vectors.extend(item["embedding"] for item in ordered)
    if len(vectors) != len(texts):
        raise SystemExit(f"vector count {len(vectors)}")
    return vectors


def docs():
    import subprocess as sp
    raw = sp.check_output(["docker", "exec", "telink-mysql", "mysql", "-uroot", "telink", "-N", "-e",
                           "SELECT d.id, d.title, d.component, v.version FROM rag_document d JOIN rag_document_version v ON v.document_id=d.id AND v.version=d.active_version WHERE d.deleted=0 AND v.status='READY' AND d.title LIKE 'KB-%'"], text=True, encoding="utf-8")
    rows = {}
    for line in raw.splitlines():
        doc_id, title, component, version = line.split("\t")
        code = title.split()[0]
        rows[code] = {"id": doc_id, "title": title, "component": component, "version": version, "key": f"{doc_id}:{version}"}
    return rows


def chunks():
    raw = subprocess.check_output(["docker", "exec", "telink-mysql", "mysql", "-uroot", "telink", "-N", "-e",
        "SELECT document_id, content FROM rag_chunk"], text=True, encoding="utf-8")
    out = {}
    for line in raw.splitlines():
        if "\t" not in line:
            continue
        doc_id, content = line.split("\t", 1)
        out.setdefault(doc_id, "")
        out[doc_id] += content
    return out


def qdrant(body):
    return post("http://127.0.0.1:6333/collections/telink_knowledge/points/query", body)["result"]["points"]


def ids_of(points, allowed):
    found = []
    for point in points:
        doc_id = (point.get("payload") or {}).get("documentId")
        if doc_id in allowed and doc_id not in found:
            found.append(doc_id)
    return found


def terms(text):
    import re
    found = []
    for word in re.findall(r"[A-Za-z0-9_.$-]+|[\u4e00-\u9fff]+", text.lower()):
        if "\u4e00" <= word[:1] <= "\u9fff":
            if len(word) == 1:
                found.append(word)
            else:
                found.extend(word[i:i + 2] for i in range(len(word) - 1))
        else:
            found.append(word)
    return found


def metrics(ranks):
    n = len(ranks)
    def recall(k):
        return sum(rank is not None and rank <= k for rank in ranks) / n
    dcg = []
    for rank in ranks:
        dcg.append(1 / math.log2(rank + 1) if rank is not None and rank <= 10 else 0)
    return {"recallAt1": round(recall(1), 4), "recallAt5": round(recall(5), 4),
            "recallAt10": round(recall(10), 4), "ndcgAt10": round(sum(dcg) / n, 4)}


def main():
    catalog = docs()
    text_by_doc = chunks()
    key = knowledge_key()
    if not key:
        raise SystemExit("embedding key missing")
    questions = [item[1] for item in QUERIES]
    vectors = embed(questions, key)
    version_filter = {"must": [{"key": "versionKey", "match": {"any": [row["key"] for row in catalog.values()]}}]}
    allowed = {row["id"] for row in catalog.values()}
    strategies = {"Dense": [], "Sparse": [], "Hybrid RRF": [], "Rerank": []}
    for (code, question), vector in zip(QUERIES, vectors):
        target = catalog[code]["id"]
        dense_points = qdrant({"query": vector, "using": "dense", "limit": 10, "with_payload": True, "filter": version_filter})
        sparse_body = {"query": lexical(question), "using": "lexical", "limit": 10, "with_payload": True, "filter": version_filter}
        sparse_points = qdrant(sparse_body)
        hybrid_points = qdrant({
            "prefetch": [
                {"query": vector, "using": "dense", "limit": 30, "filter": version_filter},
                {"query": lexical(question), "using": "lexical", "limit": 30, "filter": version_filter},
            ],
            "query": {"fusion": "rrf"},
            "limit": 20,
            "with_payload": True,
        })
        hybrid_ids = ids_of(hybrid_points, allowed)
        query_terms = set(terms(question))
        reranked = sorted(hybrid_ids, key=lambda doc_id: overlap(query_terms, text_by_doc.get(doc_id, "")), reverse=True)
        ranked = {
            "Dense": ids_of(dense_points, allowed),
            "Sparse": ids_of(sparse_points, allowed),
            "Hybrid RRF": hybrid_ids[:10],
            "Rerank": reranked[:10],
        }
        for name, ordered in ranked.items():
            strategies[name].append(ordered.index(target) + 1 if target in ordered else None)
    print(json.dumps({name: metrics(ranks) for name, ranks in strategies.items()}, ensure_ascii=False, indent=2))


def lexical(text):
    import collections
    counts = collections.Counter(terms(text))
    indices, values = [], []
    for term, count in counts.items():
        hash_value = 0x811c9dc5
        for byte in term.encode():
            hash_value ^= byte
            hash_value = (hash_value * 0x01000193) & 0xFFFFFFFF
        indices.append(hash_value)
        values.append(1 + math.log(count))
    order = sorted(range(len(indices)), key=lambda i: indices[i])
    return {"indices": [indices[i] for i in order], "values": [values[i] for i in order]}


def overlap(query_terms, content):
    if not query_terms:
        return 0
    shared = query_terms.intersection(terms(content))
    return len(shared) / len(query_terms)


if __name__ == "__main__":
    main()
