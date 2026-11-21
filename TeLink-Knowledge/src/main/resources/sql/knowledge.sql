-- MySQL 8.x：在应用数据库执行。只创建新表，不修改现有日志或消息表。
CREATE TABLE IF NOT EXISTS rag_document (
 id VARCHAR(36) PRIMARY KEY,
 title VARCHAR(255) NOT NULL,
 component VARCHAR(100) NOT NULL DEFAULT '',
 service_name VARCHAR(100) NOT NULL DEFAULT '',
 applicable_version VARCHAR(100) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 latest_version INT NOT NULL,
 active_version INT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rag_document_version (
 document_id VARCHAR(36) NOT NULL,
 version INT NOT NULL,
 file_name VARCHAR(255) NOT NULL,
 storage_path VARCHAR(512) NOT NULL,
 content_hash CHAR(64) NOT NULL,
 byte_size BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 chunk_count INT NOT NULL DEFAULT 0,
 embedding_model VARCHAR(255) NOT NULL,
 embedding_dimensions INT NOT NULL,
 embedding_profile CHAR(64) NOT NULL,
 index_collection VARCHAR(100) NOT NULL,
 error_message VARCHAR(1000) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(document_id, version),
 CONSTRAINT fk_rag_version_document FOREIGN KEY(document_id) REFERENCES rag_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rag_chunk (
 id VARCHAR(36) PRIMARY KEY,
 document_id VARCHAR(36) NOT NULL,
 version INT NOT NULL,
 ordinal_no INT NOT NULL,
 heading VARCHAR(512) NOT NULL,
 content MEDIUMTEXT NOT NULL,
 content_hash CHAR(64) NOT NULL,
 UNIQUE KEY uk_rag_chunk(document_id,version,ordinal_no),
 CONSTRAINT fk_rag_chunk_version FOREIGN KEY(document_id,version) REFERENCES rag_document_version(document_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rag_ingest_task (
 id VARCHAR(36) PRIMARY KEY,
 document_id VARCHAR(36) NOT NULL,
 version INT NOT NULL,
 kind VARCHAR(16) NOT NULL,
 state VARCHAR(24) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 lease_owner VARCHAR(36) NULL,
 lease_until TIMESTAMP(3) NULL,
 retry_at TIMESTAMP(3) NULL,
 error_message VARCHAR(1000) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_rag_task_recovery(state,lease_until,retry_at),
 KEY idx_rag_task_document(document_id,version),
 CONSTRAINT fk_rag_task_document FOREIGN KEY(document_id) REFERENCES rag_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 本地事务 Outbox：文档/版本/任务与待投递事件同事务提交。
CREATE TABLE IF NOT EXISTS rag_outbox (
 id VARCHAR(36) PRIMARY KEY,
 task_id VARCHAR(36) NOT NULL,
 topic VARCHAR(255) NOT NULL,
 payload TEXT NOT NULL,
 published_at TIMESTAMP(3) NULL,
 available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 attempts INT NOT NULL DEFAULT 0,
 error_message VARCHAR(1000) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_rag_outbox_pending(published_at,available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
