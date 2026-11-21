package com.te.link.platform.operations;

import com.te.link.platform.NettyServer.Monitor.NodeMetrics;
import com.te.link.platform.NettyServer.Monitor.NodeRegistryService;
import com.google.gson.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.te.link.platform.operations.OperationRepository.str;

/** 各节点只认领指向本实例、已人工批准的任务；状态持久化后才产生副作用。 */
public final class NodeOperationWorker implements AutoCloseable {
    public interface Actions { void requestGc() throws Exception; void restart() throws Exception; }
    private final OperationRepository repository;
    private final OperationService service;
    private final Supplier<JsonObject> snapshot;
    private final Actions actions;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r,"approved-node-operation"); t.setDaemon(true); return t;
    });
    private long lastErrorAt;
    public NodeOperationWorker() {
        this(new OperationRepository(), new OperationService(),
                () -> JsonParser.parseString(NodeRegistryService.snapshotJson(NodeMetrics.getInstance().snapshot())).getAsJsonObject(), new Actions() {
                    public void requestGc() { System.gc(); }
                    public void restart() throws Exception {
                        if (!OperationConfig.restartAvailable()) throw new IllegalStateException("restart unavailable");
                        new ProcessBuilder(OperationConfig.RESTART_COMMAND).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    }
                });
    }
    public NodeOperationWorker(OperationRepository repository, OperationService service, Supplier<JsonObject> snapshot, Actions actions) {
        this.repository=repository;this.service=service;this.snapshot=snapshot;this.actions=actions;
    }
    public void start() {
        if (OperationConfig.ENABLED) worker.scheduleWithFixedDelay(() -> {
            try { tick(); }
            catch (Exception e) {
                if (System.currentTimeMillis()-lastErrorAt>60000) {
                    lastErrorAt=System.currentTimeMillis();
                    System.err.println("Operation worker unavailable ("+e.getClass().getSimpleName()+"); check shared MySQL and operation tables.");
                }
            }
        },2,3,TimeUnit.SECONDS);
    }
    public void tick() throws Exception {
        if (!OperationConfig.ENABLED) return;
        service.reconcile();
        JsonObject local=snapshot.get();
        if (!"RUNNING".equals(str(local,"status"))) return;
        for (JsonElement item:repository.approvedFor(local.get("machineId").getAsInt())) {
            JsonObject operation=item.getAsJsonObject();String id=str(operation,"id");
            JsonObject before=snapshot.get();
            try { OperationService.validateTarget(operation,before); }
            catch (RuntimeException e) { repository.finish(id,Set.of("APPROVED"),"EXPIRED","目标节点实例或执行配置已变化，未执行",null);continue; }
            if (!repository.claim(id,str(before,"instanceId"),str(before.getAsJsonObject("operationCapabilities"),"executorFingerprint"))) continue;
            JsonObject result=new JsonObject();result.add("before",before);
            try {
                if ("REQUEST_GC".equals(str(operation,"action"))) {
                    actions.requestGc();
                    result.add("after",snapshot.get());result.addProperty("message","已向目标 JVM 请求垃圾回收；是否回收及实际效果由 JVM 决定");
                    repository.finish(id,Set.of("RUNNING"),"SUCCEEDED","JVM 回收请求已发出，不保证释放内存",result);
                } else {
                    // 重启会中断当前进程，必须提前落库，后续由任一存活节点/管理请求确认新实例。
                    result.addProperty("message","正在调用固定重启入口，等待目标部署的新实例上线");
                    if (repository.finish(id,Set.of("RUNNING"),"VERIFYING","等待重启后新实例上报",result)) actions.restart();
                }
            } catch (Exception e) {
                // 入口调用异常也可能已经产生副作用，保守标为 UNKNOWN，不重新投递。
                result.addProperty("message","执行或结果保存出现异常，请人工检查目标节点；不会自动重试");
                result.addProperty("errorType",e.getClass().getSimpleName());
                repository.finish(id,Set.of("RUNNING","VERIFYING"),"UNKNOWN","执行结果不确定，需人工检查",result);
            }
        }
    }
    @Override public void close() { worker.shutdownNow(); }
}
