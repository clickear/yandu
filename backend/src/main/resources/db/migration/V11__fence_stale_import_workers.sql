ALTER TABLE import_jobs
    ADD COLUMN run_generation BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '导入 worker 租约代次；每次领取递增，迟到 worker 不能修改后续尝试';
