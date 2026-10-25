CREATE TABLE glossary_terms (
    id BIGINT NOT NULL COMMENT '术语标识，由应用侧雪花算法生成',
    project_id BIGINT NOT NULL COMMENT '术语所属项目标识',
    source_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '源语言标签，使用 BCP 47 格式',
    target_locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标语言标签，使用 BCP 47 格式',
    source_term VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '源术语，保留原始大小写',
    term_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '应用生成的小写规范化源术语，用于唯一约束',
    target_term VARCHAR(500) CHARACTER SET utf8mb4 NOT NULL COMMENT '经审核的目标术语',
    note VARCHAR(1000) CHARACTER SET utf8mb4 NULL COMMENT '给译员的术语使用说明',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '术语状态：ACTIVE 或 ARCHIVED',
    created_by BIGINT NOT NULL COMMENT '创建术语的项目成员标识',
    created_at DATETIME(6) NOT NULL COMMENT '术语创建时间，UTC，微秒精度',
    updated_at DATETIME(6) NOT NULL COMMENT '术语最近更新时间，UTC，微秒精度',
    PRIMARY KEY (id),
    CONSTRAINT uq_glossary_project_locale_term UNIQUE (project_id, source_locale, target_locale, term_key),
    CONSTRAINT uq_glossary_project_id UNIQUE (project_id, id),
    KEY ix_glossary_match (project_id, source_locale, target_locale, status),
    CONSTRAINT fk_glossary_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_glossary_target_locale FOREIGN KEY (project_id, target_locale)
        REFERENCES project_languages (project_id, locale),
    CONSTRAINT fk_glossary_creator FOREIGN KEY (created_by) REFERENCES external_identities (user_id),
    CONSTRAINT ck_glossary_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='项目级双语术语表';
