-- Asset Finder schema (Option 3: latest-only occurrence + audit)
-- Safe for local Yugabyte/PostgreSQL environments.

BEGIN;

-- Optional cleanup of previous single-table design.
DROP TABLE IF EXISTS asset_image_store;

-- Keep request metadata capture on raw_data_store.
ALTER TABLE IF EXISTS raw_data_store
    ADD COLUMN IF NOT EXISTS source_request_metadata JSONB;

-- Canonical metadata catalog (deduplicated by metadata hash).
CREATE TABLE IF NOT EXISTS asset_metadata_catalog (
    id UUID PRIMARY KEY,
    metadata_hash TEXT NOT NULL,
    asset_key TEXT NOT NULL,
    asset_model TEXT,
    interactive_path TEXT,
    preview_uri TEXT,
    alt_text TEXT,
    accessibility_text TEXT,
    viewports_json JSONB,
    asset_metadata_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        WHERE c.conname = 'uk_asset_metadata_catalog_metadata_hash'
          AND c.conrelid = 'asset_metadata_catalog'::regclass
    ) THEN
        ALTER TABLE asset_metadata_catalog
            ADD CONSTRAINT uk_asset_metadata_catalog_metadata_hash
            UNIQUE (metadata_hash);
    END IF;
END $$;

-- Latest-only occurrence rows (one active row per source_uri + slot).
CREATE TABLE IF NOT EXISTS asset_metadata_occurrence (
    id UUID PRIMARY KEY,
    catalog_id UUID NOT NULL,
    raw_data_id UUID NOT NULL,
    source_uri TEXT NOT NULL,
    source_version INTEGER,
    first_seen_version INTEGER,
    last_seen_version INTEGER,
    asset_slot_key TEXT NOT NULL,
    asset_node_path TEXT NOT NULL,
    section_path TEXT,
    section_uri TEXT,
    tenant TEXT,
    environment TEXT,
    project TEXT,
    site TEXT,
    geo TEXT,
    locale TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    request_metadata_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE IF EXISTS asset_metadata_occurrence
    ADD COLUMN IF NOT EXISTS first_seen_version INTEGER;
ALTER TABLE IF EXISTS asset_metadata_occurrence
    ADD COLUMN IF NOT EXISTS last_seen_version INTEGER;
ALTER TABLE IF EXISTS asset_metadata_occurrence
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE IF EXISTS asset_metadata_occurrence
    ALTER COLUMN active SET DEFAULT TRUE;

-- Collapse legacy snapshot duplicates before enforcing latest-only uniqueness.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY source_uri, asset_slot_key
               ORDER BY COALESCE(updated_at, created_at) DESC, created_at DESC, id DESC
           ) AS rn
    FROM asset_metadata_occurrence
)
DELETE FROM asset_metadata_occurrence o
USING ranked r
WHERE o.id = r.id
  AND r.rn > 1;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        WHERE c.conname = 'uk_asset_metadata_occurrence_source_version_slot'
          AND c.conrelid = 'asset_metadata_occurrence'::regclass
    ) THEN
        ALTER TABLE asset_metadata_occurrence
            DROP CONSTRAINT uk_asset_metadata_occurrence_source_version_slot;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        WHERE c.conname = 'uk_asset_metadata_occurrence_source_slot'
          AND c.conrelid = 'asset_metadata_occurrence'::regclass
    ) THEN
        ALTER TABLE asset_metadata_occurrence
            ADD CONSTRAINT uk_asset_metadata_occurrence_source_slot
            UNIQUE (source_uri, asset_slot_key);
    END IF;
END $$;

-- Append-only change log for occurrence updates.
CREATE TABLE IF NOT EXISTS asset_metadata_occurrence_audit (
    id UUID PRIMARY KEY,
    raw_data_id UUID NOT NULL,
    source_uri TEXT NOT NULL,
    source_version INTEGER,
    asset_slot_key TEXT NOT NULL,
    event_type TEXT NOT NULL,
    old_catalog_id UUID,
    new_catalog_id UUID,
    old_context_json JSONB,
    new_context_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Stable per-upload count summary to keep activity history intact.
CREATE TABLE IF NOT EXISTS asset_metadata_upload_summary (
    raw_data_id UUID PRIMARY KEY,
    source_uri TEXT NOT NULL,
    source_version INTEGER,
    asset_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for query paths and joins.
CREATE INDEX IF NOT EXISTS idx_asset_metadata_catalog_interactive
    ON asset_metadata_catalog (interactive_path);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_catalog_created_at
    ON asset_metadata_catalog (created_at);

CREATE INDEX IF NOT EXISTS idx_asset_metadata_occurrence_catalog_id
    ON asset_metadata_occurrence (catalog_id);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occurrence_raw_data_id
    ON asset_metadata_occurrence (raw_data_id);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occurrence_source_uri_version
    ON asset_metadata_occurrence (source_uri, source_version);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occurrence_filters
    ON asset_metadata_occurrence (tenant, environment, project, site, geo, locale);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occurrence_section
    ON asset_metadata_occurrence (section_path);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occurrence_active
    ON asset_metadata_occurrence (active);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occurrence_created_at
    ON asset_metadata_occurrence (created_at);

CREATE INDEX IF NOT EXISTS idx_asset_metadata_occ_audit_source_version
    ON asset_metadata_occurrence_audit (source_uri, source_version);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occ_audit_slot
    ON asset_metadata_occurrence_audit (asset_slot_key);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occ_audit_created_at
    ON asset_metadata_occurrence_audit (created_at);
CREATE INDEX IF NOT EXISTS idx_asset_metadata_occ_audit_raw_data_id
    ON asset_metadata_occurrence_audit (raw_data_id);

CREATE INDEX IF NOT EXISTS idx_asset_metadata_upload_summary_source_version
    ON asset_metadata_upload_summary (source_uri, source_version);

COMMIT;
