ALTER TABLE import_jobs
    ADD KEY ix_import_jobs_project_created (project_id, created_at, id);
