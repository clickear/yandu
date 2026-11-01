-- Keys identify catalog entries project-wide; namespaces are organizational only.
-- This migration intentionally fails if existing data has duplicate active or archived keys.
-- Before upgrading an existing database, find conflicts with:
-- SELECT project_id, entry_key, COUNT(*) FROM catalog_entries
-- GROUP BY project_id, entry_key HAVING COUNT(*) > 1;
ALTER TABLE catalog_entries
    DROP INDEX uq_catalog_entries_key,
    ADD CONSTRAINT uq_catalog_entries_project_key UNIQUE (project_id, entry_key);
