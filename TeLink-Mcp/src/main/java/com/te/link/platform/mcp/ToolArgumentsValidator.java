package com.te.link.platform.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 校验当前工具使用的 Schema 子集：必填字段、基础类型、枚举与数值范围。 */
final class ToolArgumentsValidator {
    static void validate(McpToolDefinition definition, JsonObject arguments) {
        JsonObject schema = JsonParser.parseString(definition.parametersJson()).getAsJsonObject();
        if (schema.has("required")) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                String name = required.getAsString();
                if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
                    throw new IllegalArgumentException(name + " is required");
                }
            }
        }
        JsonObject properties = schema.getAsJsonObject("properties");
        if (properties == null) return;
        for (String name : arguments.keySet()) {
            if (!properties.has(name)) continue;
            JsonElement value = arguments.get(name);
            JsonObject rule = properties.getAsJsonObject(name);
            String type = rule.has("type") ? rule.get("type").getAsString() : "";
            boolean valid = switch (type) {
                case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                        && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
                case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
                case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                case "object" -> value.isJsonObject();
                case "array" -> value.isJsonArray();
                default -> true;
            };
            if (!valid) throw new IllegalArgumentException(name + " must be " + type);
            if (rule.has("enum") && !rule.getAsJsonArray("enum").contains(value)) {
                throw new IllegalArgumentException(name + " is not an allowed value");
            }
            if (rule.has("minimum") && value.getAsBigDecimal().compareTo(rule.get("minimum").getAsBigDecimal()) < 0
                    || rule.has("maximum") && value.getAsBigDecimal().compareTo(rule.get("maximum").getAsBigDecimal()) > 0) {
                throw new IllegalArgumentException(name + " is out of range");
            }
        }
    }
}
