CREATE TABLE external_identities (
    user_id BIGINT NOT NULL COMMENT '系统内部用户标识，由应用侧雪花算法生成',
    issuer VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'OIDC 身份提供方签发者地址',
    subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'OIDC 稳定主体标识；与 issuer 组合唯一',
    created_at DATETIME(6) NOT NULL COMMENT '首次认证时间，UTC，微秒精度',
    PRIMARY KEY (user_id),
    CONSTRAINT uq_external_identities_issuer_subject UNIQUE (issuer, subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='外部 OIDC 主体到系统内部用户 ID 的映射';

CREATE TABLE projects (
    id BIGINT NOT NULL COMMENT '项目标识，由应用侧 MyBatis-Plus 雪花算法生成',
    owner_id BIGINT NOT NULL COMMENT '项目创建者用户标识',
    name VARCHAR(120) NOT NULL COMMENT '项目名称，在同一创建者下唯一',
    source_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '项目源语言，使用 BCP 47 格式',
    version BIGINT NOT NULL COMMENT '项目配置乐观锁版本',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '项目状态：ACTIVE 或 ARCHIVED',
    created_at DATETIME(6) NOT NULL COMMENT '项目创建时间，UTC，微秒精度',
    updated_at DATETIME(6) NOT NULL COMMENT '项目最近更新时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_projects_owner_name UNIQUE (owner_id, name),
    CONSTRAINT fk_projects_owner FOREIGN KEY (owner_id) REFERENCES external_identities (user_id),
    KEY ix_projects_owner_status (owner_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='翻译工作项目';

CREATE TABLE project_members (
    project_id BIGINT NOT NULL COMMENT '项目标识',
    user_id BIGINT NOT NULL COMMENT '成员系统内部用户标识',
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '项目角色：OWNER、ADMIN、TRANSLATOR、REVIEWER 或 VIEWER',
    created_at DATETIME(6) NOT NULL COMMENT '成员加入时间，UTC，微秒精度',
    PRIMARY KEY (project_id, user_id),
    KEY ix_project_members_user_role (user_id, role, project_id),
    CONSTRAINT ck_project_members_role CHECK (role IN ('OWNER', 'ADMIN', 'TRANSLATOR', 'REVIEWER', 'VIEWER')),
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_project_members_user FOREIGN KEY (user_id) REFERENCES external_identities (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='项目成员和授权角色';

CREATE TABLE project_languages (
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标语言，使用 BCP 47 格式',
    created_at DATETIME(6) NOT NULL COMMENT '目标语言添加时间，UTC，微秒精度',
    PRIMARY KEY (project_id, locale),
    CONSTRAINT fk_project_languages_project FOREIGN KEY (project_id) REFERENCES projects (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='项目启用的目标语言';

CREATE TABLE namespaces (
    id BIGINT NOT NULL COMMENT '命名空间标识；默认命名空间与项目共用数值 ID',
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    name VARCHAR(120) NOT NULL COMMENT '项目内唯一的资源命名空间名称',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命名空间状态：ACTIVE 或 ARCHIVED',
    created_by BIGINT NOT NULL COMMENT '命名空间创建者用户标识',
    created_at DATETIME(6) NOT NULL COMMENT '命名空间创建时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_namespaces_project_name UNIQUE (project_id, name),
    CONSTRAINT uq_namespaces_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_namespaces_project FOREIGN KEY (project_id) REFERENCES projects (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='项目资源命名空间';

CREATE TABLE catalog_entries (
    id BIGINT NOT NULL COMMENT '词条全局唯一标识，由 MyBatis-Plus 雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '所属项目标识；当前阶段的资源访问边界',
    namespace_id BIGINT NOT NULL COMMENT '所属命名空间标识',
    source_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '源语言标签，使用 BCP 47 格式',
    entry_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '项目命名空间内区分大小写的资源键，首尾空白由领域层去除',
    source_text TEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '当前源文案；变更历史保存在 source_revisions',
    source_revision BIGINT NOT NULL COMMENT '源文案修订号；每次源文案实际变化递增 1',
    version BIGINT NOT NULL COMMENT '聚合乐观锁版本；每次成功修改递增 1',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '生命周期状态：ACTIVE 或 ARCHIVED',
    created_by BIGINT NOT NULL COMMENT '创建该词条的用户标识',
    updated_by BIGINT NOT NULL COMMENT '最近修改该词条的用户标识',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC，微秒精度',
    updated_at DATETIME(6) NOT NULL COMMENT '最近更新时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_catalog_entries_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_catalog_entries_key UNIQUE (project_id, namespace_id, entry_key),
    KEY ix_catalog_entries_project_status (project_id, status, id),
    CONSTRAINT fk_catalog_entries_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_catalog_entries_namespace FOREIGN KEY (project_id, namespace_id) REFERENCES namespaces (project_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='项目中的源词条当前状态';

CREATE TABLE source_revisions (
    id BIGINT NOT NULL COMMENT '修订记录全局唯一标识，由 MyBatis-Plus 雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '项目标识，用于按项目授权查询',
    entry_id BIGINT NOT NULL COMMENT '对应的源词条标识',
    source_revision BIGINT NOT NULL COMMENT '该词条内单调递增的源文案修订号',
    source_text TEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '该修订的源文案完整快照，不可覆盖更新',
    changed_by BIGINT NOT NULL COMMENT '执行本次源文案变更的用户标识',
    created_at DATETIME(6) NOT NULL COMMENT '修订记录创建时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_source_revisions_entry_revision UNIQUE (entry_id, source_revision),
    KEY ix_source_revisions_project_entry (project_id, entry_id, source_revision),
    CONSTRAINT fk_source_revisions_entry FOREIGN KEY (project_id, entry_id) REFERENCES catalog_entries (project_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='源文案不可变修订历史';

CREATE TABLE translations (
    id BIGINT NOT NULL COMMENT '译文标识，由 MyBatis-Plus 雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    entry_id BIGINT NOT NULL COMMENT '源词条标识',
    target_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标语言，使用 BCP 47 格式',
    value TEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '当前译文内容；历史快照保存在 translation_revisions',
    based_on_source_revision BIGINT NOT NULL COMMENT '译文基于的源文修订号；低于当前源文时必须复核',
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态：DRAFT、IN_REVIEW、APPROVED、CHANGES_REQUESTED 或 NEEDS_REVIEW',
    version BIGINT NOT NULL COMMENT '译文聚合乐观锁版本，任何内容或状态变化递增',
    translated_by BIGINT NOT NULL COMMENT '最近一次修改译文内容的用户标识；用于禁止自审',
    updated_by BIGINT NOT NULL COMMENT '最近修改译文或审核状态的用户标识',
    created_at DATETIME(6) NOT NULL COMMENT '译文首次保存时间，UTC，微秒精度',
    updated_at DATETIME(6) NOT NULL COMMENT '译文最近更新时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_translations_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_translations_entry_locale UNIQUE (project_id, entry_id, target_locale),
    CONSTRAINT ck_translations_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'CHANGES_REQUESTED', 'NEEDS_REVIEW')),
    CONSTRAINT fk_translations_entry FOREIGN KEY (project_id, entry_id) REFERENCES catalog_entries (project_id, id),
    CONSTRAINT fk_translations_locale FOREIGN KEY (project_id, target_locale) REFERENCES project_languages (project_id, locale),
    CONSTRAINT fk_translations_translator FOREIGN KEY (translated_by) REFERENCES external_identities (user_id),
    CONSTRAINT fk_translations_updated_by FOREIGN KEY (updated_by) REFERENCES external_identities (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='每个词条和目标语言对应的译文聚合';

CREATE TABLE translation_revisions (
    id BIGINT NOT NULL COMMENT '译文修订快照标识，由 MyBatis-Plus 雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    translation_id BIGINT NOT NULL COMMENT '译文聚合标识',
    entry_id BIGINT NOT NULL COMMENT '源词条标识',
    target_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标语言标签',
    translation_version BIGINT NOT NULL COMMENT '该快照对应的译文聚合版本号',
    value TEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '不可变译文内容快照',
    based_on_source_revision BIGINT NOT NULL COMMENT '该译文快照基于的源文修订号',
    edited_by BIGINT NOT NULL COMMENT '保存该译文快照的用户标识',
    created_at DATETIME(6) NOT NULL COMMENT '快照创建时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_translation_revisions_version UNIQUE (translation_id, translation_version),
    KEY ix_translation_revisions_project_entry_locale (project_id, entry_id, target_locale, translation_version),
    CONSTRAINT fk_translation_revisions_translation FOREIGN KEY (project_id, translation_id) REFERENCES translations (project_id, id),
    CONSTRAINT fk_translation_revisions_editor FOREIGN KEY (edited_by) REFERENCES external_identities (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='译文内容不可变修订历史';

CREATE TABLE translation_review_decisions (
    id BIGINT NOT NULL COMMENT '审核决定标识，由 MyBatis-Plus 雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    translation_id BIGINT NOT NULL COMMENT '译文聚合标识',
    entry_id BIGINT NOT NULL COMMENT '源词条标识',
    target_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标语言标签',
    decision_version BIGINT NOT NULL COMMENT '被审核的译文版本号；决定绑定该精确版本',
    source_revision BIGINT NOT NULL COMMENT '审核时的源文修订号',
    decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审核决定：APPROVE 或 REQUEST_CHANGES',
    reason TEXT CHARACTER SET utf8mb4 NULL COMMENT '退回修改原因；批准时可为空',
    reviewer_id BIGINT NOT NULL COMMENT '执行审核的用户标识',
    created_at DATETIME(6) NOT NULL COMMENT '审核决定时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_translation_review_version UNIQUE (translation_id, decision_version),
    KEY ix_translation_review_project_entry (project_id, entry_id, target_locale, decision_version),
    CONSTRAINT ck_translation_review_decision CHECK (decision IN ('APPROVE', 'REQUEST_CHANGES')),
    CONSTRAINT fk_translation_review_translation FOREIGN KEY (project_id, translation_id) REFERENCES translations (project_id, id),
    CONSTRAINT fk_translation_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES external_identities (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='针对精确译文版本的不可变审核决定';

CREATE TABLE releases (
    id BIGINT NOT NULL COMMENT '不可变发布快照标识，由应用层雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次发布的目标语言标签',
    version_no BIGINT NOT NULL COMMENT '项目和语言内单调递增的发布序号',
    rollback_of_release_id BIGINT NULL COMMENT '回滚时引用的旧发布标识；普通发布为空',
    content_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '发布时生成的完整 JSON 资源快照，发布后不再修改',
    manifest_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '发布内容来源清单，记录词条 ID、源版本和译文版本',
    checksum_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'UTF-8 快照内容的 SHA-256 十六进制摘要',
    published_by BIGINT NOT NULL COMMENT '创建该发布快照的项目成员标识',
    published_at DATETIME(6) NOT NULL COMMENT '快照创建时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_releases_project_locale_version UNIQUE (project_id, locale, version_no),
    CONSTRAINT uq_releases_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_releases_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_releases_rollback FOREIGN KEY (project_id, rollback_of_release_id) REFERENCES releases (project_id, id),
    CONSTRAINT fk_releases_locale FOREIGN KEY (project_id, locale) REFERENCES project_languages (project_id, locale)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可变的项目语言发布快照';

CREATE TABLE import_jobs (
    id BIGINT NOT NULL COMMENT '导入任务标识，由应用层雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '所属项目标识',
    namespace_id BIGINT NOT NULL COMMENT '导入目标命名空间标识',
    locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '导入的源语言或目标语言标签',
    project_version BIGINT NOT NULL COMMENT '生成预览时的项目配置版本；应用时用于检测配置变化',
    idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端生成的幂等键，在项目内唯一',
    payload_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '校验并规范化后的扁平 JSON 导入内容',
    preview_json LONGTEXT CHARACTER SET utf8mb4 NOT NULL COMMENT '只读差异预览，绑定预览哈希',
    preview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化预览内容的 SHA-256 摘要',
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务状态：PREVIEW_READY、QUEUED、RUNNING、COMPLETED 或 FAILED',
    created_by BIGINT NOT NULL COMMENT '创建导入任务的用户标识',
    created_at DATETIME(6) NOT NULL COMMENT '任务创建时间，UTC，微秒精度',
    updated_at DATETIME(6) NOT NULL COMMENT '任务最近状态变更时间，UTC，微秒精度',
    error TEXT CHARACTER SET utf8mb4 NULL COMMENT '任务失败摘要，不写入原始资源内容',
    PRIMARY KEY (id),
    CONSTRAINT uq_import_jobs_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_import_jobs_idempotency UNIQUE (project_id, idempotency_key),
    KEY ix_import_jobs_status_created (status, created_at, id),
    CONSTRAINT fk_import_jobs_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_import_jobs_namespace FOREIGN KEY (project_id, namespace_id) REFERENCES namespaces (project_id, id),
    CONSTRAINT fk_import_jobs_creator FOREIGN KEY (created_by) REFERENCES external_identities (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='可追踪、可幂等的 JSON 导入任务与差异预览';

CREATE TABLE audit_log (
    id BIGINT NOT NULL COMMENT '审计记录标识，由应用层雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '所属项目标识；用于项目成员授权范围查询',
    actor_id BIGINT NOT NULL COMMENT '执行操作的内部用户标识',
    action VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定的操作代码，例如 SOURCE_CHANGED 或 RELEASE_PUBLISHED',
    resource_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '资源类型，例如 PROJECT、CATALOG_ENTRY、TRANSLATION、IMPORT_JOB 或 RELEASE',
    resource_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '受影响资源标识的十进制字符串；项目级操作可为空',
    details_json TEXT CHARACTER SET utf8mb4 NULL COMMENT '非敏感审计元数据 JSON；不存储完整源文案或译文',
    occurred_at DATETIME(6) NOT NULL COMMENT '操作发生时间，UTC，微秒精度',
    PRIMARY KEY (id),
    KEY ix_audit_project_time (project_id, occurred_at, id),
    KEY ix_audit_actor_time (actor_id, occurred_at, id),
    CONSTRAINT fk_audit_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES external_identities (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='项目范围的不可变关键操作审计记录';
