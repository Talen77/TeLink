package com.te.link.platform.log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/** 消息和日志热路径复用连接，避免每条消息都向操作系统要一个新套接字。 */
public final class MysqlPool {
    private static final int PER_PROCESS = 16;
    private static final Semaphore PERMITS = new Semaphore(PER_PROCESS);
    private static final ArrayBlockingQueue<Connection> IDLE = new ArrayBlockingQueue<>(PER_PROCESS);

    private MysqlPool() {
    }

    public static Connection borrow(String url, String user, String password) throws SQLException {
        PERMITS.acquireUninterruptibly();
        Connection cached = IDLE.poll();
        if (cached != null) {
            try {
                if (cached.isValid(1)) {
                    return cached;
                }
            } catch (SQLException ignored) {
                closeQuietly(cached);
            }
        }
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException exception) {
            PERMITS.release();
            throw exception;
        }
    }

    public static void giveBack(Connection connection) {
        if (connection == null) {
            PERMITS.release();
            return;
        }
        if (!IDLE.offer(connection)) {
            closeQuietly(connection);
        }
        PERMITS.release();
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 坏连接直接丢掉。
        }
    }
}
