package com.te.link.platform.message;

/**
 * push_message_node 表专属 SQL。
 */
public final class PushMessageNodeQuery {
    public static final String INSERT_IGNORE = """
            INSERT IGNORE INTO push_message_node (
                log_id, machine_id, stage
            )             VALUES (?, ?, ?)
            """;

    public static final String COUNT_BY_MACHINE = """
            SELECT machine_id, COUNT(DISTINCT log_id) AS message_count
            FROM push_message_node
            GROUP BY machine_id
            """;

    private PushMessageNodeQuery() {
    }
}
