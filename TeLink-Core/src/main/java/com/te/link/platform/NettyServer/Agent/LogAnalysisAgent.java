package com.te.link.platform.NettyServer.Agent;

import com.te.link.platform.log.Log;
import com.te.link.platform.log.LogFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * LogID 日志分析 Agent。
 * 复用 ReAct Runner，由模型逐轮决定查询日志或输出最终结论。
 */
public class LogAnalysisAgent {
    private static final String SYSTEM_PROMPT = """
            你是推送中台日志分析助手。
            服务端已按用户 LogID 预取真实日志，可用 query_push_logs_by_log_id 复核该链路。
            可以根据已有查询结果继续调用可用工具补充证据，证据充分后再直接回答。
            禁止在未调用工具或工具结果为空时推断日志内容和故障原因。
            查询结果返回后，分析日志链路中的异常、失败位置、影响范围和建议操作。
            不得输出 SQL、数据库表名、密钥、令牌或未在工具结果中出现的信息。
            输出中文，面向后端研发和运维人员。
            """;

    private static final Log log = LogFactory.getLog(LogAnalysisAgent.class);
    private final AutoModelRouter autoModelRouter;
    private final AgentToolRegistry toolRegistry;
    private final BulletAgentRunner agentRunner;

    public LogAnalysisAgent() {
        this(new LlmAnalyzeClient(), new AutoModelRouter(), new AgentToolRegistry());
    }

    LogAnalysisAgent(LlmAnalyzeClient llmAnalyzeClient, AutoModelRouter autoModelRouter,
                     AgentToolRegistry toolRegistry) {
        this.autoModelRouter = autoModelRouter;
        this.toolRegistry = toolRegistry;
        this.agentRunner = new BulletAgentRunner(llmAnalyzeClient, toolRegistry);
    }

    /**
     * 使用 Auto 路由按 LogID 查询日志并生成分析。
     */
    public LogAnalysisResult analyze(String logId) {
        return analyze(logId, AgentModelRegistry.AUTO);
    }

    /**
     * 同一次分析中持续回填工具结果，直到模型返回不含工具调用的最终回答。
     */
    public LogAnalysisResult analyze(String logId, String requestedModel) {
        if (!hasText(logId)) {
            return LogAnalysisResult.serviceAnalyzeFailed();
        }
        AgentTraceContext traceContext = AgentTraceContext.forLogAnalysis(logId.trim());
        log.Info(traceContext.logContext().put("requestedModel", requestedModel),
                "AGENT_LOG_ANALYSIS_STARTED");
        AgentToolRequest scope = new AgentToolRequest(logId.trim(), traceContext);
        String facts;
        com.google.gson.JsonObject knowledge;
        try {
            com.google.gson.JsonObject args = new com.google.gson.JsonObject(); args.addProperty("logId", logId.trim()); args.addProperty("limit", 100);
            facts = toolRegistry.execute(new AgentToolCall("initial-logs", AgentToolNames.QUERY_LOGS, args.toString()), scope);
            knowledge = AgentKnowledgeSupport.retrieve(toolRegistry, scope, AgentKnowledgeSupport.logQuery(facts));
        } catch (RuntimeException e) { return LogAnalysisResult.serviceAnalyzeFailed(); }
        AgentModelLease lease;
        try {
            lease = autoModelRouter.acquire(requestedModel);
        } catch (Exception e) {
            log.Error(traceContext.logContext().put("errorType", e.getClass().getSimpleName()),
                    "AGENT_MODEL_LEASE_FAILED",
                    e);
            return LogAnalysisResult.serviceAnalyzeFailed();
        }
        log.Info(traceContext.logContext().put("modelName", lease.model().name()),
                "AGENT_MODEL_LEASE_ACQUIRED");

        try {
            List<AgentChatMessage> messages = new ArrayList<>();
            messages.add(AgentChatMessage.system(SYSTEM_PROMPT + AgentKnowledgeSupport.INSTRUCTION + ProposeNodeOperationTool.INSTRUCTION));
            messages.add(AgentChatMessage.user("服务端已查询的真实日志：" + facts + "\n初次知识检索：" + knowledge));
            messages.add(AgentChatMessage.user("请分析日志 LogID: " + logId.trim()));

            String analysis = agentRunner.runStream(
                    lease.model(),
                    messages,
                    AgentKnowledgeSupport.tools(toolRegistry, AgentToolNames.QUERY_LOGS),
                    scope,
                    traceContext,
                    ignored -> { }
            );

            autoModelRouter.complete(lease, System.currentTimeMillis());
            LogAnalysisResult result = new LogAnalysisResult();
            result.setLogId(logId.trim());
            result.setConversationId(traceContext.conversationId());
            result.setOperations(scope.operations().proposals());
            result.setModelName(lease.model().name());
            result.setAnalysis(analysis + AgentKnowledgeSupport.referenceFooter(scope.knowledge()));
            log.Info(traceContext.logContext()
                            .put("modelName", lease.model().name())
                            .put("analysisLength", analysis.length()),
                    "AGENT_LOG_ANALYSIS_COMPLETED");
            return result;
        } catch (Exception e) {
            log.Error(traceContext.logContext().put("errorType", e.getClass().getSimpleName()),
                    "AGENT_LOG_ANALYSIS_FAILED",
                    e);
            return fail(lease, traceContext);
        }
    }

    /**
     * 工具或模型失败时不向调用方透出日志、模型或异常信息。
     */
    private LogAnalysisResult fail(AgentModelLease lease, AgentTraceContext traceContext) {
        try {
            autoModelRouter.fail(lease);
        } catch (Exception ignored) {
        }
        log.Warn(traceContext.logContext(), "AGENT_LOG_ANALYSIS_RETURNED_FAILED");
        return LogAnalysisResult.serviceAnalyzeFailed();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
