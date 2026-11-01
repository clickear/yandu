ALTER TABLE projects
    ADD COLUMN description TEXT CHARACTER SET utf8mb4 NULL COMMENT '项目描述，可为空',
    ADD COLUMN owner_name_key VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (LOWER(TRIM(name))) STORED COMMENT '同一所有者下去首尾空格并忽略大小写的项目名称键',
    MODIFY COLUMN status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '项目状态：MIGRATING、ACTIVE 或 ARCHIVED',
    DROP INDEX uq_projects_owner_name,
    ADD CONSTRAINT uq_projects_owner_name UNIQUE (owner_id, owner_name_key);

CREATE TABLE project_migrations (
    id BIGINT NOT NULL COMMENT '本地项目迁移任务标识，由应用侧雪花算法生成',
    actor_id BIGINT NOT NULL COMMENT '发起迁移的用户标识',
    source_project_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '本地项目标识；与发起用户组合用于幂等',
    package_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '校验并规范化后的完整迁移包，最大 5 MiB',
    package_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化迁移包 SHA-256 摘要',
    target_name VARCHAR(120) CHARACTER SET utf8mb4 NOT NULL COMMENT '用户确认的远端项目名称',
    preview_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '预览的项目名称冲突和导入规模信息',
    preview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '预览内容 SHA-256 摘要',
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务状态：PREVIEW_READY、QUEUED、RUNNING、COMPLETED 或 FAILED',
    target_project_id BIGINT NULL COMMENT '远端迁移项目标识；任务创建后固定',
    completed_entries INT NOT NULL DEFAULT 0 COMMENT '已原子写入的本地词条数量，用于断点续传',
    entry_id_map_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '本地词条 ID 到远端词条 ID 的映射',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '任务乐观锁版本',
    created_at DATETIME(6) NOT NULL COMMENT '迁移任务创建时间，UTC，微秒精度',
    updated_at DATETIME(6) NOT NULL COMMENT '迁移任务最近状态变更时间，UTC，微秒精度',
    error TEXT CHARACTER SET utf8mb4 NULL COMMENT '失败摘要，不写入资源原文',
    PRIMARY KEY (id),
    CONSTRAINT uq_project_migrations_source UNIQUE (actor_id, source_project_id),
    KEY ix_project_migrations_queue (status, updated_at, id),
    CONSTRAINT fk_project_migrations_actor FOREIGN KEY (actor_id) REFERENCES external_identities (user_id),
    CONSTRAINT fk_project_migrations_project FOREIGN KEY (target_project_id) REFERENCES projects (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='本地项目可恢复迁移任务及断点状态';
