-- Narrow exact-source candidates before joining approved translations.
CREATE INDEX ix_catalog_entries_source_match
    ON catalog_entries (project_id, status, source_text(191));
