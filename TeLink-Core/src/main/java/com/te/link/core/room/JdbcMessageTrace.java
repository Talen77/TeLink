package com.te.link.core.room;

import com.te.link.platform.log.MysqlPool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class JdbcMessageTrace implements MessageTrace, AutoCloseable {
    static final String DDL = """
            CREATE TABLE IF NOT EXISTS room_message_trace (
              log_id VARCHAR(64) PRIMARY KEY,
              message_id VARCHAR(64) NOT NULL,
              room_id BIGINT NOT NULL,
              user_id BIGINT NULL,
              status VARCHAR(32) NOT NULL,
              status_rank INT NOT NULL,
              machine_id INT NULL,
              detail VARCHAR(500) NULL,
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
            )
            """;

    private static final String UPSERT = """
            INSERT INTO room_message_trace
              (log_id, message_id, room_id, user_id, status, status_rank, machine_id, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              status = IF(VALUES(status_rank) >= status_rank, VALUES(status), status),
              status_rank = GREATEST(status_rank, VALUES(status_rank)),
              machine_id = IF(VALUES(status_rank) >= status_rank, VALUES(machine_id), machine_id),
              detail = IF(VALUES(status_rank) >= status_rank, VALUES(detail), detail)
            """;

    private final String url;
    private final String user;
    private final String password;

    public JdbcMessageTrace(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static JdbcMessageTrace fromSystemProperties() {
        String url = System.getProperty("telink.mysql.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("telink.mysql.url is required when room push is enabled");
        }
        return new JdbcMessageTrace(
                url,
                System.getProperty("telink.mysql.user", "root"),
                System.getProperty("telink.mysql.password", "")
        );
    }

    public void ensureTable() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(DDL);
        } catch (SQLException exception) {
            throw new IllegalStateException("create room_message_trace failed", exception);
        }
    }

    @Override
    public void advance(RoomRecord record, RoomMessageStatus status, int machineId, String detail) {
        Connection connection = null;
        try {
            connection = MysqlPool.borrow(url, user, password);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, record.getLogId());
            statement.setString(2, record.getMessageId() == null ? "" : record.getMessageId());
            statement.setLong(3, record.getRoomId());
            if (record.getUserId() == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, record.getUserId());
            }
            statement.setString(5, status.name());
            statement.setInt(6, status.rank());
            statement.setInt(7, machineId);
            statement.setString(8, detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500)));
            statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("advance room message status failed", exception);
        } finally {
            if (connection != null) {
                MysqlPool.giveBack(connection);
            }
        }
    }

    @Override
    public RoomMessageStatus status(String logId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status FROM room_message_trace WHERE log_id = ?")) {
            statement.setString(1, logId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return RoomMessageStatus.fromName(resultSet.getString(1));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("query room message status failed", exception);
        }
    }

    @Override
    public void close() {
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
