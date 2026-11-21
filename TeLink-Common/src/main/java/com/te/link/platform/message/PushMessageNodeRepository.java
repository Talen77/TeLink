package com.te.link.platform.message;

import com.te.link.platform.log.LogMysqlConfig;
import com.te.link.platform.log.MysqlPool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * push_message_node 表专属数据访问入口。
 */
public class PushMessageNodeRepository {
    public void record(String logId, int machineId, String stage) {
        if (!LogMysqlConfig.enabled()) {
            throw new IllegalStateException("push message mysql config is empty");
        }
        if (logId == null || logId.isBlank() || machineId < 0 || stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("push message node fields are invalid");
        }
        Connection connection = null;
        try {
            connection = MysqlPool.borrow(
                    LogMysqlConfig.MYSQL_URL, LogMysqlConfig.MYSQL_USER, LogMysqlConfig.MYSQL_PASSWORD);
            try (PreparedStatement statement = connection.prepareStatement(PushMessageNodeQuery.INSERT_IGNORE)) {
            statement.setString(1, logId.trim());
            statement.setInt(2, machineId);
            statement.setString(3, stage.trim());
            statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("insert push_message_node failed", e);
        } finally {
            if (connection != null) {
                MysqlPool.giveBack(connection);
            }
        }
    }

    /** 每个节点经过的不同 logId 数量，包含已经下线的机器。 */
    public Map<Integer, Long> countByMachine() {
        if (!LogMysqlConfig.enabled()) {
            return Map.of();
        }
        Map<Integer, Long> counts = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(
                LogMysqlConfig.MYSQL_URL,
                LogMysqlConfig.MYSQL_USER,
                LogMysqlConfig.MYSQL_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(PushMessageNodeQuery.COUNT_BY_MACHINE);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                counts.put(resultSet.getInt("machine_id"), resultSet.getLong("message_count"));
            }
            return counts;
        } catch (Exception e) {
            throw new IllegalStateException("count push_message_node failed", e);
        }
    }
}
