package com.te.link.core.diag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class JdbcLinkLogStore implements LinkLogStore {
    static final String DDL = """
            CREATE TABLE IF NOT EXISTS link_log (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              log_id VARCHAR(64) NOT NULL,
              level VARCHAR(16) NOT NULL,
              machine_id INT NULL,
              message VARCHAR(500) NOT NULL,
              exception_text VARCHAR(2000) NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              KEY idx_link_log_log_id (log_id)
            )
            """;

    private final String url;
    private final String user;
    private final String password;

    public JdbcLinkLogStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static JdbcLinkLogStore fromSystemProperties() {
        String url = System.getProperty("telink.mysql.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("telink.mysql.url is required");
        }
        return new JdbcLinkLogStore(
                url,
                System.getProperty("telink.mysql.user", "root"),
                System.getProperty("telink.mysql.password", "")
        );
    }

    public void ensureTable() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(DDL);
        } catch (SQLException exception) {
            throw new IllegalStateException("create link_log failed", exception);
        }
    }

    @Override
    public void append(LinkLog log) {
        String sql = "INSERT INTO link_log (log_id, level, machine_id, message, exception_text) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, log.logId());
            statement.setString(2, log.level());
            statement.setInt(3, log.machineId());
            statement.setString(4, clip(log.message(), 500));
            statement.setString(5, clip(log.exceptionText(), 2000));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("append link log failed", exception);
        }
    }

    @Override
    public List<LinkLog> query(String logId) {
        String sql = "SELECT log_id, level, machine_id, message, exception_text FROM link_log WHERE log_id = ? ORDER BY id";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, logId);
            try (ResultSet rows = statement.executeQuery()) {
                List<LinkLog> logs = new ArrayList<>();
                while (rows.next()) {
                    logs.add(new LinkLog(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getInt(3),
                            rows.getString(4),
                            rows.getString(5)
                    ));
                }
                return logs;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("query link log failed", exception);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
