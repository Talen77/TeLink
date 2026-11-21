package com.te.link.core.room;

import com.te.link.platform.log.LogMysqlConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * 管理端、消息轨迹和会话归档用到的表。脚本可重复执行。
 *
 * @Author: Talen
 */
public final class PlatformSchema {
    private PlatformSchema() {
    }

    public static void ensure() {
        if (!LogMysqlConfig.enabled()) {
            return;
        }
        for (String resource : new String[]{
                "sql/push_log.sql",
                "sql/push_message.sql",
                "sql/agent_operation.sql",
                "sql/platform/agent_conversation.sql"
        }) {
            apply(resource);
        }
    }

    private static void apply(String resource) {
        try (var input = PlatformSchema.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing " + resource);
            }
            String script = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            try (Connection connection = DriverManager.getConnection(
                    LogMysqlConfig.MYSQL_URL,
                    LogMysqlConfig.MYSQL_USER,
                    LogMysqlConfig.MYSQL_PASSWORD == null ? "" : LogMysqlConfig.MYSQL_PASSWORD);
                 Statement statement = connection.createStatement()) {
                for (String sql : script.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("apply " + resource + " failed", exception);
        }
    }
}
