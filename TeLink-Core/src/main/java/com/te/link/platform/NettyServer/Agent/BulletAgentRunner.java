package com.te.link.platform.NettyServer.Agent;

import com.te.link.platform.log.Log;
import com.te.link.platform.log.LogFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * BulletChatPush ReAct Runner：每轮携带可用工具，由模型决定继续调用工具或给出最终答案。
 */
public class BulletAgentRunner {
    private static final Log log = LogFactory.getLog(BulletAgentRunner.class);
    private static final int MAX_MODEL_TURNS = 5;

    private final LlmAnalyzeClient llmAnalyzeClient;
    private final AgentToolRegistry toolRegistry;

    public BulletAgentRunner(LlmAnalyzeClient llmAnalyzeClient, AgentToolRegistry toolRegistry) {
        this.llmAnalyzeClient = llmAnalyzeClient;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行一次流式 Agent 对话。
     * 每轮完整响应到达后才判断是否存在工具调用，避免把工具轮次的中间文本作为答案下发。
     * 最多请求五轮模型；只有不包含工具调用的非空回答才能通过 finalChunkConsumer 返回。
     */
    public String runStream(
            AgentModel model,
            List<AgentChatMessage> originalMessages,
            List<AgentToolDefinition> tools,
            AgentToolRequest toolRequest,
            AgentTraceContext traceContext,
            Consumer<String> finalChunkConsumer
    ) {
        List<AgentChatMessage> messages = new ArrayList<>(originalMessages);
        List<AgentToolDefinition> safeTools = tools == null ? List.of() : List.copyOf(tools);
        Set<String> availableToolNames = safeTools.stream()
                .map(AgentToolDefinition::name)
                .collect(Collectors.toSet());
        for (int turn = 1; turn <= MAX_MODEL_TURNS; turn++) {
            AgentLlmResponse response = llmAnalyzeClient.chatStreamWithTools(
                    model,
                    messages,
                    safeTools,
                    traceContext,
                    // LlmAnalyzeClient 会聚合正文和工具参数，确认本轮无工具调用后再统一下发正文。
                    chunk -> { }
            );
            if (response.hasToolCalls()) {
                log.Info(traceContext.logContext()
                                .put("agentTurn", turn)
                                .put("toolCallCount", response.toolCalls().size()),
                        "AGENT_RUNNER_TOOL_TURN");
                if (turn == MAX_MODEL_TURNS) {
                    throw new IllegalStateException("agent model turns exceed limit");
                }
                // 先校验整批调用，注册表中存在但未向当前请求开放的工具也不能执行。
                for (AgentToolCall toolCall : response.toolCalls()) {
                    if (!availableToolNames.contains(toolCall.name())) {
                        throw new IllegalArgumentException("agent tool is unavailable in this request:" + toolCall.name());
                    }
                }
                messages.add(AgentChatMessage.assistant(response.content(), response.toolCalls()));
                for (AgentToolCall toolCall : response.toolCalls()) {
                    executeTool(messages, toolCall, toolRequest, traceContext);
                }
                // 保留同一套可用工具和完整观察结果，下一轮继续使用 tool_choice=auto。
                continue;
            }
            String finalContent = response.content();
            if (!hasText(finalContent)) {
                throw new IllegalStateException("agent final response is empty");
            }
            finalChunkConsumer.accept(finalContent);
            log.Info(traceContext.logContext()
                            .put("agentTurn", turn)
                            .put("replyLength", finalContent == null ? 0 : finalContent.length()),
                    "AGENT_RUNNER_COMPLETED");
            return finalContent;
        }
        throw new IllegalStateException("agent model turns exceed limit");
    }

    private void executeTool(
            List<AgentChatMessage> messages,
            AgentToolCall toolCall,
            AgentToolRequest toolRequest,
            AgentTraceContext traceContext
    ) {
        long startedAtMillis = System.currentTimeMillis();
        log.Info(traceContext.logContext()
                        .put("toolCallId", toolCall.id())
                        .put("toolName", toolCall.name()),
                "AGENT_RUNNER_TOOL_STARTED");
        try {
            String result = toolRegistry.execute(toolCall, toolRequest);
            log.Info(traceContext.logContext()
                            .put("toolCallId", toolCall.id())
                            .put("toolName", toolCall.name())
                            .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis)
                            .put("toolResultLength", result == null ? 0 : result.length()),
                    "AGENT_RUNNER_TOOL_COMPLETED");
            messages.add(AgentChatMessage.tool(toolCall.id(), result));
        } catch (Exception e) {
            log.Error(traceContext.logContext()
                            .put("toolCallId", toolCall.id())
                            .put("toolName", toolCall.name())
                            .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis)
                            .put("errorType", e.getClass().getSimpleName()),
                    "AGENT_RUNNER_TOOL_FAILED",
                    e);
            throw e;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
