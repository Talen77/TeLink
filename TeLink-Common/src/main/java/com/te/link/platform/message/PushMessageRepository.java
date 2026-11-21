package com.te.link.platform.message;

import com.te.link.platform.log.LogMysqlConfig;
import com.te.link.platform.log.MysqlPool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * push_message 表专属数据访问入口。
 */
public class PushMessageRepository {
    public void upsert(
            String logId,
            String messageId,
            Long uid,
            long roomId,
            String content,
            PushMessageStatus status
    ) {
        requireEnabled();
        if (!hasText(logId) || !hasText(messageId) || roomId <= 0 || status == null) {
            throw new IllegalArgumentException("push message fields are invalid");
        }
        Connection connection = null;
        try {
            connection = MysqlPool.borrow(LogMysqlConfig.MYSQL_URL, LogMysqlConfig.MYSQL_USER, LogMysqlConfig.MYSQL_PASSWORD);
            try (PreparedStatement statement = connection.prepareStatement(PushMessageQuery.UPSERT)) {
            statement.setString(1, logId.trim());
            statement.setString(2, messageId.trim());
            if (uid == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, uid);
            }
            statement.setLong(4, roomId);
            statement.setString(5, content == null ? "" : content);
            statement.setString(6, status.name());
            statement.setInt(7, status.getRank());
            statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("upsert push_message failed", e);
        } finally {
            if (connection != null) {
                MysqlPool.giveBack(connection);
            }
        }
    }

    /**
     * 所有过滤条件都落在 push_message 自身字段上；节点轨迹只在结果聚合阶段关联。
     */
    public PushMessagePage queryPage(PushMessageSearchParam param) {
        requireEnabled();
        PushMessageSearchParam safeParam = param == null ? new PushMessageSearchParam() : param;
        int page = Math.max(safeParam.getPage(), 1);
        int pageSize = safePageSize(safeParam.getPageSize());

        String filterSql = buildFilterSql(safeParam);
        try (Connection connection = openConnection()) {
            long total = count(connection, filterSql, safeParam);
            List<PushMessageRecord> items = queryItems(
                    connection,
                    filterSql,
                    safeParam,
                    pageSize,
                    (page - 1) * pageSize
            );
            int totalPages = total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
            return new PushMessagePage(items, total, page, pageSize, totalPages);
        } catch (Exception e) {
            throw new IllegalStateException("query push_message page failed", e);
        }
    }

    private long count(Connection connection, String filterSql, PushMessageSearchParam param) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                PushMessageQuery.COUNT_BASE + filterSql
        )) {
            bindFilters(statement, param);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private List<PushMessageRecord> queryItems(
            Connection connection,
            String filterSql,
            PushMessageSearchParam param,
            int limit,
            int offset
    ) throws Exception {
        String sql = PushMessageQuery.SELECT_PAGE_BASE + filterSql + PushMessageQuery.ORDER_PAGE;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindFilters(statement, param);
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PushMessageRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(mapRecord(resultSet));
                }
                return records;
            }
        }
    }

    private String buildFilterSql(PushMessageSearchParam param) {
        StringBuilder sql = new StringBuilder();
        if (hasText(param.getLogId())) {
            sql.append(PushMessageQuery.AND_LOG_ID);
        }
        if (param.getUid() != null) {
            sql.append(PushMessageQuery.AND_UID);
        }
        if (param.getRoomId() != null) {
            sql.append(PushMessageQuery.AND_ROOM_ID);
        }
        if (hasText(param.getKeyword())) {
            sql.append(PushMessageQuery.AND_KEYWORD);
        }
        return sql.toString();
    }

    /**
     * count 与分页查询复用完全一致的参数顺序，避免筛选条件数量变化后错绑字段。
     *
     * @return 下一个可绑定参数的位置
     */
    private int bindFilters(PreparedStatement statement, PushMessageSearchParam param) throws Exception {
        int index = 1;
        if (hasText(param.getLogId())) {
            statement.setString(index++, "%" + param.getLogId().trim() + "%");
        }
        if (param.getUid() != null) {
            statement.setLong(index++, param.getUid());
        }
        if (param.getRoomId() != null) {
            statement.setLong(index++, param.getRoomId());
        }
        if (hasText(param.getKeyword())) {
            statement.setString(index++, "%" + param.getKeyword().trim() + "%");
        }
        return index;
    }

    private PushMessageRecord mapRecord(ResultSet resultSet) throws Exception {
        PushMessageRecord record = new PushMessageRecord();
        record.setId(resultSet.getLong("id"));
        record.setLogId(resultSet.getString("log_id"));
        record.setMessageId(resultSet.getString("message_id"));
        record.setUid(resultSet.getObject("uid", Long.class));
        record.setRoomId(resultSet.getObject("room_id", Long.class));
        record.setContent(resultSet.getString("content"));
        record.setStatus(resultSet.getString("status"));
        record.setStatusRank(resultSet.getInt("status_rank"));
        record.setCreatedAt(toLocalDateTime(resultSet.getTimestamp("created_at")));
        record.setUpdatedAt(toLocalDateTime(resultSet.getTimestamp("updated_at")));
        record.setTraversedNodeIds(parseNodeIds(resultSet.getString("traversed_nodes")));
        return record;
    }

    private List<Integer> parseNodeIds(String nodeIds) {
        if (!hasText(nodeIds)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (String nodeId : nodeIds.split(",")) {
            try {
                result.add(Integer.parseInt(nodeId.trim()));
            } catch (NumberFormatException ignored) {
                // 单个异常节点值不应阻断整页消息查询。
            }
        }
        return result;
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                LogMysqlConfig.MYSQL_URL,
                LogMysqlConfig.MYSQL_USER,
                LogMysqlConfig.MYSQL_PASSWORD
        );
    }

    private void requireEnabled() {
        if (!LogMysqlConfig.enabled()) {
            throw new IllegalStateException("push message mysql config is empty");
        }
    }

    private int safePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private java.time.LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
