-- Cleanup migration: remove legacy/unused columns from content_hashes
-- Safe to run multiple times.

BEGIN;

ALTER TABLE IF EXISTS content_hashes
    DROP COLUMN IF EXISTS context_fingerprint_hash;

ALTER TABLE IF EXISTS content_hashes
    DROP COLUMN IF EXISTS raw_content_hash;

COMMIT;
