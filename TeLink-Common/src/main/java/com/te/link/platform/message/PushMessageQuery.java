package com.te.link.platform.message;

/**
 * push_message 表专属 SQL。
 */
public final class PushMessageQuery {
    public static final String UPSERT = """
            INSERT INTO push_message (
                log_id, message_id, uid, room_id, content, status, status_rank
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                message_id = VALUES(message_id),
                uid = VALUES(uid),
                room_id = VALUES(room_id),
                content = VALUES(content),
                status = IF(VALUES(status_rank) >= status_rank, VALUES(status), status),
                status_rank = GREATEST(status_rank, VALUES(status_rank)),
                updated_at = CURRENT_TIMESTAMP(3)
            """;

    public static final String COUNT_BASE = """
            SELECT COUNT(*)
            FROM push_message m
            WHERE 1 = 1
            """;

    public static final String SELECT_PAGE_BASE = """
            SELECT
                m.id, m.log_id, m.message_id, m.uid, m.room_id, m.content,
                m.status, m.status_rank, m.created_at, m.updated_at,
                n.traversed_nodes
            FROM push_message m
            LEFT JOIN (
                SELECT
                    node_stage.log_id,
                    GROUP_CONCAT(
                        node_stage.machine_id
                        ORDER BY node_stage.first_stage_rank, node_stage.first_seen_at
                        SEPARATOR ','
                    ) AS traversed_nodes
                FROM (
                    SELECT
                        log_id,
                        machine_id,
                        MIN(created_at) AS first_seen_at,
                        MIN(CASE stage
                            WHEN 'SOURCE' THEN 10
                            WHEN 'ROUTER' THEN 20
                            WHEN 'DELIVERED' THEN 30
                            ELSE 99
                        END) AS first_stage_rank
                    FROM push_message_node
                    GROUP BY log_id, machine_id
                ) node_stage
                GROUP BY node_stage.log_id
            ) n ON n.log_id = m.log_id
            WHERE 1 = 1
            """;

    public static final String AND_LOG_ID = " AND m.log_id LIKE ?";
    public static final String AND_UID = " AND m.uid = ?";
    public static final String AND_ROOM_ID = " AND m.room_id = ?";
    public static final String AND_KEYWORD = " AND m.content LIKE ?";

    public static final String ORDER_PAGE = """

            ORDER BY m.created_at DESC, m.id DESC
            LIMIT ? OFFSET ?
            """;

    private PushMessageQuery() {
    }
}
