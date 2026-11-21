package com.te.link.platform.operations;

import com.te.link.platform.NettyServer.Agent.*;
import com.te.link.platform.monitor.ClusterNodeRepository;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;
import static com.te.link.platform.operations.OperationRepository.str;

/** 审批边界：服务端冻结计划，独立验证审核身份、节点实例、配置和有效期。 */
public class OperationService {
    private final OperationRepository repository;
    private final ClusterNodeRepository nodes;
    private final LongSupplier clock;
    public OperationService() { this(new OperationRepository(), new ClusterNodeRepository(), System::currentTimeMillis); }
    public OperationService(OperationRepository repository, ClusterNodeRepository nodes, LongSupplier clock) {
        this.repository = repository; this.nodes = nodes; this.clock = clock;
    }
    public JsonObject propose(JsonObject args, AgentToolRequest request) throws Exception {
        if (!OperationConfig.ENABLED) throw new IllegalArgumentException("人工审核操作尚未启用");
        if (OperationConfig.REVIEW_KEY.isBlank()) throw new IllegalArgumentException("尚未配置人工审核密钥");
        if (request == null || request.traceContext() == null) throw new IllegalArgumentException("缺少分析上下文");
        if (args == null || !Set.of("machineId","action","reason","expectedOutcome","knowledgeChunkIds").containsAll(args.keySet()))
            throw new IllegalArgumentException("申请包含不支持的参数");
        int machine;
        try { if (!args.get("machineId").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(); machine = args.get("machineId").getAsBigDecimal().intValueExact(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("machineId 必须为整数"); }
        if (machine < 0 || (request.machineId() != null && machine != request.machineId()) || !request.operations().allows(machine))
            throw new IllegalArgumentException("目标节点不在本轮真实观测范围内");
        String action = text(args, "action", 24);
        if (!Set.of("REQUEST_GC","RESTART").contains(action)) throw new IllegalArgumentException("不支持该操作");
        String reason = text(args, "reason", 2000), outcome = text(args, "expectedOutcome", 1000);
        JsonArray selected = new JsonArray();
        JsonArray ids;
        try { ids = args.getAsJsonArray("knowledgeChunkIds"); }
        catch (RuntimeException e) { throw new IllegalArgumentException("knowledgeChunkIds 必须为数组"); }
        if (ids == null || ids.isEmpty() || ids.size() > 8) throw new IllegalArgumentException("操作申请需要 1 至 8 个实际知识引用");
        Map<String,JsonObject> references = new HashMap<>();
        for (JsonElement reference : request.knowledge().reviewSnapshot()) references.put(str(reference.getAsJsonObject(),"chunkId"), reference.getAsJsonObject());
        Set<String> seen = new HashSet<>();
        for (JsonElement id : ids) {
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !references.containsKey(id.getAsString()))
                throw new IllegalArgumentException("知识引用不是本轮实际检索结果");
            if (seen.add(id.getAsString())) selected.add(references.get(id.getAsString()).deepCopy());
        }
        JsonObject metrics = fresh(machine);
        checkCapability(metrics, action);
        if ("RESTART".equals(action)) uniqueDeployment(metrics);
        JsonObject plan = new JsonObject();
        plan.addProperty("conversationId", request.traceContext().conversationId());
        plan.addProperty("analysisId", request.traceContext().traceId());
        plan.addProperty("machineId", machine);
        plan.addProperty("instanceId", text(metrics,"instanceId",64));
        plan.addProperty("deploymentId", str(metrics,"deploymentId"));
        plan.addProperty("executorFingerprint", text(metrics.getAsJsonObject("operationCapabilities"),"executorFingerprint",64));
        plan.addProperty("action", action); plan.addProperty("reason",reason); plan.addProperty("expectedOutcome",outcome);
        plan.addProperty("logId", request.requestedLogId());
        plan.addProperty("impact", "RESTART".equals(action)
                ? "重启整个节点进程，当前连接会中断，客户端需要重连，处理中消息可能失败或重试；若本页连接目标节点，页面也会暂时不可用。"
                : "向目标 JVM 请求垃圾回收，可能产生停顿；JVM 可能忽略请求，不能保证降低堆占用，也不会释放其他进程的内存。");
        plan.addProperty("confirmationText", OperationConfig.confirmation(action,machine));
        plan.add("metrics", metrics.deepCopy()); plan.add("logEvidence",request.operations().logs(machine)); plan.add("knowledgeReferences",selected);
        return repository.propose(plan);
    }
    public JsonObject list(String conversation, int page, int size) throws Exception {
        if (page < 1 || page > 100000 || size < 1 || size > 100 || (conversation != null && conversation.length() > 64))
            throw new IllegalArgumentException("分页或会话参数无效");
        JsonObject result = new JsonObject();
        result.addProperty("enabled", OperationConfig.ENABLED); result.addProperty("reviewConfigured", !OperationConfig.REVIEW_KEY.isBlank());
        result.addProperty("page",page); result.addProperty("pageSize",size);
        if (!OperationConfig.ENABLED) { result.add("items",new JsonArray()); return result; }
        reconcile();
        result.add("items",repository.list(conversation,page,size)); return result;
    }
    public void authorize(String key) {
        if (OperationConfig.REVIEW_KEY.isBlank() || key == null || !MessageDigest.isEqual(OperationConfig.REVIEW_KEY.getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("审核密钥无效或尚未配置");
    }
    public JsonObject decide(String id, JsonObject body, String key) throws Exception {
        authorize(key);
        if (!OperationConfig.ENABLED) throw new OperationRepository.Conflict("人工审核操作尚未启用");
        if (body == null || !Set.of("decision","planDigest","reviewer","comment","confirmationText").containsAll(body.keySet()))
            throw new IllegalArgumentException("审核参数不能修改操作计划");
        String decision = text(body,"decision",16), digest = text(body,"planDigest",64), reviewer = text(body,"reviewer",100);
        if (!Set.of("APPROVE","REJECT").contains(decision)) throw new IllegalArgumentException("无效审核决定");
        String comment = str(body,"comment"); if (comment.length()>1000) throw new IllegalArgumentException("审核备注不能超过 1000 字");
        JsonObject plan = repository.get(id);
        if (!digest.equals(str(plan,"planDigest"))) throw new OperationRepository.Conflict("操作计划不匹配，请刷新");
        if ("APPROVE".equals(decision)) {
            if (!str(plan,"confirmationText").equals(str(body,"confirmationText"))) throw new IllegalArgumentException("请输入卡片中的确认文字");
            // 已审批请求保持幂等；初次批准必须重新读取当前节点，拒绝旧实例和被更换的执行配置。
            if ("PENDING_APPROVAL".equals(str(plan,"state"))) {
                JsonObject current = fresh(plan.get("machineId").getAsInt());
                validateTarget(plan,current);
                if ("RESTART".equals(str(plan,"action"))) uniqueDeployment(current);
            }
        }
        return repository.decide(id,digest,decision,reviewer,comment);
    }
    public static void validateTarget(JsonObject plan, JsonObject current) {
        if (!"RUNNING".equals(str(current,"status"))) throw new OperationRepository.Conflict("目标节点已停止，未执行");
        checkCapability(current,str(plan,"action"));
        if (plan.get("machineId").getAsInt()!=current.get("machineId").getAsInt()
                || !str(plan,"instanceId").equals(str(current,"instanceId"))
                || !str(plan,"deploymentId").equals(str(current,"deploymentId"))
                || !str(plan,"executorFingerprint").equals(str(current.getAsJsonObject("operationCapabilities"),"executorFingerprint")))
            throw new OperationRepository.Conflict("节点实例或执行配置已变化，请重新分析并提交审核");
    }
    private JsonObject fresh(int machine) {
        JsonObject current;
        try { current = nodes.get(machine); }
        catch (IllegalArgumentException e) { throw new OperationRepository.Conflict("目标节点不在线，请重新分析"); }
        if (!current.get("fresh").getAsBoolean() || !"RUNNING".equals(str(current,"status"))) throw new OperationRepository.Conflict("目标节点不在线或采样过期，请重新分析");
        return current;
    }
    private static void checkCapability(JsonObject current, String action) {
        JsonObject capabilities = current.getAsJsonObject("operationCapabilities");
        String capability = "RESTART".equals(action) ? "restart" : "requestGc";
        if (!Set.of("REQUEST_GC","RESTART").contains(action) || capabilities == null || !capabilities.has(capability) || !capabilities.get(capability).getAsBoolean())
            throw new OperationRepository.Conflict("目标节点没有开放该操作；重启需要配置固定命令和唯一 deploymentId");
        if (str(current,"deploymentId").length()>128) throw new OperationRepository.Conflict("节点 deploymentId 配置过长");
    }
    private void uniqueDeployment(JsonObject current) {
        String deployment = str(current,"deploymentId");
        long count = nodes.snapshots().asList().stream().map(JsonElement::getAsJsonObject)
                .filter(n -> deployment.equals(str(n,"deploymentId")) && n.get("fresh").getAsBoolean() && "RUNNING".equals(str(n,"status"))).count();
        if (deployment.isBlank() || count != 1) throw new OperationRepository.Conflict("deploymentId 必须唯一标识一个在线部署实例");
    }
    /** 不重试副作用：崩溃或超时后结果未知，只有新实例上报才能确认重启成功。 */
    public void reconcile() throws Exception {
        JsonArray snapshots = null;
        for (JsonElement item : repository.unsettled()) {
            JsonObject operation = item.getAsJsonObject(); String state=str(operation,"state"), id=str(operation,"id"); long now=clock.getAsLong();
            if ("PENDING_APPROVAL".equals(state)) {
                if (operation.get("expiresAt").getAsLong()<=now) repository.finish(id,Set.of(state),"EXPIRED","审核已过期，未执行",null);
                continue;
            }
            long deadline=operation.get("deadlineAt").getAsLong();
            if ("VERIFYING".equals(state)) {
                try {
                    if (snapshots==null) snapshots=nodes.snapshots();
                    List<JsonObject> matches=new ArrayList<>();
                    for (JsonElement n:snapshots) { JsonObject node=n.getAsJsonObject();
                        if (str(operation,"deploymentId").equals(str(node,"deploymentId")) && node.get("fresh").getAsBoolean() && "RUNNING".equals(str(node,"status"))) matches.add(node);
                    }
                    if (matches.size()==1 && !str(matches.get(0),"instanceId").isBlank() && !str(operation,"instanceId").equals(str(matches.get(0),"instanceId"))
                            && matches.get(0).get("sampledAtMillis").getAsLong()>=operation.get("startedAt").getAsLong() && now<=deadline) {
                        JsonObject result=new JsonObject();result.add("newNode",matches.get(0));result.addProperty("message","同一 deploymentId 的新进程已上报在线指标");
                        repository.finish(id,Set.of(state),"SUCCEEDED","已确认目标部署的新节点实例在线",result);continue;
                    }
                } catch (RuntimeException ignored) { /* 注册中心故障不能当作重启成功，仍按期限结算。 */ }
            }
            if (deadline<=now) repository.finish(id,Set.of(state),"APPROVED".equals(state)?"EXPIRED":"UNKNOWN",
                    "APPROVED".equals(state)?"等待节点认领超时，未执行":"未能在期限内确认执行结果，请人工检查；不会自动重试",null);
        }
    }
    private static String text(JsonObject value,String key,int limit) {
        if (value==null || !value.has(key) || !value.get(key).isJsonPrimitive() || !value.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key+" 必须为文本");
        String result=value.get(key).getAsString().trim(); if (result.isBlank() || result.length()>limit) throw new IllegalArgumentException(key+" 为空或过长"); return result;
    }
}
