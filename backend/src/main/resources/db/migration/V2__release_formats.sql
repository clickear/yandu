ALTER TABLE releases
    DROP INDEX uq_releases_project_locale_version,
    CHANGE COLUMN content_json content LONGTEXT CHARACTER SET utf8mb4 NOT NULL
        COMMENT '不可变发布文件内容，按发布格式保存',
    ADD COLUMN format VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        DEFAULT 'json-flat-v1' AFTER locale
        COMMENT '不可变发布文件格式标识',
    ADD CONSTRAINT uq_releases_project_locale_format_version
        UNIQUE (project_id, locale, format, version_no);
