-- MySQL 8.x，在 telink.mysql.url 指向的共享库执行；所有推送节点使用同一库。
CREATE TABLE IF NOT EXISTS agent_operation (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    analysis_id VARCHAR(64) NOT NULL,
    machine_id INT NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    deployment_id VARCHAR(128) NOT NULL DEFAULT '',
    action VARCHAR(24) NOT NULL,
    plan_json MEDIUMTEXT NOT NULL,
    plan_digest CHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    reviewer VARCHAR(100) NULL,
    review_comment VARCHAR(1000) NULL,
    result_json MEDIUMTEXT NULL,
    events_json MEDIUMTEXT NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    reviewed_at BIGINT NULL,
    started_at BIGINT NULL,
    deadline_at BIGINT NULL,
    finished_at BIGINT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_operation_analysis (analysis_id, machine_id, action),
    KEY idx_operation_conversation (conversation_id, created_at),
    KEY idx_operation_worker (machine_id, state),
    KEY idx_operation_deadline (state, deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 锁定目标节点以串行审批、检查冷却期，防止不同管理进程并发批准重复操作。
CREATE TABLE IF NOT EXISTS agent_operation_node_guard (
    node_key VARCHAR(160) PRIMARY KEY,
    blocked_until BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
