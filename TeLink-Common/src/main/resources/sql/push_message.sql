CREATE TABLE IF NOT EXISTS push_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    log_id VARCHAR(64) NOT NULL COMMENT '消息全链路LogID',
    message_id VARCHAR(64) NOT NULL COMMENT '客户端消息ID',
    uid BIGINT DEFAULT NULL COMMENT '发送用户ID',
    room_id BIGINT NOT NULL COMMENT '目标房间ID',
    content TEXT NOT NULL COMMENT '消息内容',
    status VARCHAR(32) NOT NULL COMMENT '消息当前处理状态',
    status_rank INT NOT NULL DEFAULT 0 COMMENT '状态推进序号，防止跨进程异步更新发生状态回退',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次记录时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_log_id (log_id),
    KEY idx_created_at (created_at),
    KEY idx_uid_created_at (uid, created_at),
    KEY idx_room_created_at (room_id, created_at),
    KEY idx_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送消息管理表';

CREATE TABLE IF NOT EXISTS push_message_node (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    log_id VARCHAR(64) NOT NULL COMMENT '消息全链路LogID',
    machine_id INT NOT NULL COMMENT '经过的推送节点机器ID',
    stage VARCHAR(32) NOT NULL COMMENT '节点处理阶段：SOURCE/ROUTER/DELIVERED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次经过时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_log_machine_stage (log_id, machine_id, stage),
    KEY idx_log_created_at (log_id, created_at),
    KEY idx_machine_created_at (machine_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送消息节点轨迹表';
