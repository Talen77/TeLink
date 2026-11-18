package com.te.link.core.diag;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class ReactRunner {
    public static final String FAILURE = "分析失败";
    private static final int MAX_ROUNDS = 5;

    public String run(ModelClient model, List<DiagTool> tools, String prompt) {
        List<DiagMessage> history = new ArrayList<>();
        history.add(DiagMessage.user(prompt));
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                ModelTurn turn = model.complete(history);
                if (turn == null) {
                    return FAILURE;
                }
                if (!turn.hasToolCall()) {
                    if (turn.content() == null || turn.content().isBlank()) {
                        return FAILURE;
                    }
                    return turn.content();
                }
                if (round == MAX_ROUNDS - 1) {
                    return FAILURE;
                }
                DiagTool tool = find(tools, turn.toolName());
                if (tool == null) {
                    return FAILURE;
                }
                String observation = observe(tool, turn.arguments());
                if (observation == null) {
                    return FAILURE;
                }
                history.add(DiagMessage.tool(turn.toolName(), observation));
            }
            return FAILURE;
        } catch (Exception exception) {
            return FAILURE;
        }
    }

    private static String observe(DiagTool tool, JsonObject arguments) {
        try {
            return tool.call(arguments == null ? new JsonObject() : arguments);
        } catch (ToolRejectedException rejected) {
            JsonObject body = new JsonObject();
            body.addProperty("rejected", true);
            body.addProperty("reason", rejected.getMessage());
            return body.toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static DiagTool find(List<DiagTool> tools, String name) {
        for (DiagTool tool : tools) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }
}
