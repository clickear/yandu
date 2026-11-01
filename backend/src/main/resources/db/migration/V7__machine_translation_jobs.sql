CREATE TABLE machine_translation_jobs (
    id BIGINT NOT NULL COMMENT '机器翻译任务标识，由应用层雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    source_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务创建时的源语言标签',
    target_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务目标语言标签',
    idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建请求幂等键，在项目和发起人范围内唯一',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '语言和词条范围的 SHA-256 摘要，用于拒绝幂等键误复用',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务状态：QUEUED、RUNNING、COMPLETED、PARTIAL 或 FAILED',
    total_count INT NOT NULL COMMENT '任务生成时固定的词条数',
    completed_count INT NOT NULL DEFAULT 0 COMMENT '已成功回填为草稿的词条数',
    skipped_count INT NOT NULL DEFAULT 0 COMMENT '因版本或人工译文变化而未回填的词条数',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '调用或处理失败的词条数',
    created_by BIGINT NOT NULL COMMENT '发起任务的项目成员标识',
    created_at DATETIME(6) NOT NULL COMMENT '任务创建时间，UTC，微秒精度',
    updated_at DATETIME(6) NOT NULL COMMENT '任务最近状态变更时间，UTC，微秒精度',
    error TEXT CHARACTER SET utf8mb4 NULL COMMENT '首个失败摘要，不保存供应商原始响应或源文案',
    PRIMARY KEY (id),
    CONSTRAINT uq_machine_translation_jobs_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_machine_translation_jobs_idempotency UNIQUE (project_id, created_by, idempotency_key),
    CONSTRAINT ck_machine_translation_jobs_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_machine_translation_jobs_counts CHECK (completed_count + skipped_count + failed_count <= total_count),
    KEY ix_machine_translation_jobs_queue (status, created_at, id),
    CONSTRAINT fk_machine_translation_jobs_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_machine_translation_jobs_creator FOREIGN KEY (created_by) REFERENCES external_identities (user_id),
    CONSTRAINT fk_machine_translation_jobs_language FOREIGN KEY (project_id, target_locale)
        REFERENCES project_languages (project_id, locale)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='持久化机器翻译任务及幂等请求';

CREATE TABLE machine_translation_job_items (
    job_id BIGINT NOT NULL COMMENT '所属机器翻译任务标识',
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    entry_id BIGINT NOT NULL COMMENT '对应的项目词条标识；与任务标识组成复合主键',
    entry_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '任务创建时的词条键快照',
    source_text TEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '任务创建时的源文案快照，仅供任务处理且不写入审计日志',
    source_revision BIGINT NOT NULL COMMENT '任务创建时的源文版本；回填时必须仍匹配',
    expected_translation_version BIGINT NULL COMMENT '任务创建时的译文版本；NULL 表示译文尚不存在',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '词条状态：QUEUED、RUNNING、COMPLETED、SKIPPED 或 FAILED',
    attempts INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '处理尝试次数，进程失联恢复最多三次',
    error VARCHAR(1000) CHARACTER SET utf8mb4 NULL COMMENT '安全的失败或跳过说明，不包含译文正文',
    created_at DATETIME(6) NOT NULL COMMENT '任务项创建时间，UTC，微秒精度',
    updated_at DATETIME(6) NOT NULL COMMENT '任务项最近状态变更时间，UTC，微秒精度',
    PRIMARY KEY (job_id, entry_id),
    CONSTRAINT ck_machine_translation_items_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED')),
    KEY ix_machine_translation_items_queue (status, updated_at, job_id, entry_id),
    CONSTRAINT fk_machine_translation_items_job FOREIGN KEY (project_id, job_id)
        REFERENCES machine_translation_jobs (project_id, id),
    CONSTRAINT fk_machine_translation_items_entry FOREIGN KEY (project_id, entry_id)
        REFERENCES catalog_entries (project_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='机器翻译任务的词条快照、重试与回填状态';
