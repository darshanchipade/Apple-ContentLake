-- Normalized Asset Finder schema (Option C)
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

-- Versioned occurrence rows referencing catalog entries.
CREATE TABLE IF NOT EXISTS asset_metadata_occurrence (
    id UUID PRIMARY KEY,
    catalog_id UUID NOT NULL,
    raw_data_id UUID NOT NULL,
    source_uri TEXT NOT NULL,
    source_version INTEGER,
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
    request_metadata_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        WHERE c.conname = 'uk_asset_metadata_occurrence_source_version_slot'
          AND c.conrelid = 'asset_metadata_occurrence'::regclass
    ) THEN
        ALTER TABLE asset_metadata_occurrence
            ADD CONSTRAINT uk_asset_metadata_occurrence_source_version_slot
            UNIQUE (source_uri, source_version, asset_slot_key);
    END IF;
END $$;

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

CREATE INDEX IF NOT EXISTS idx_asset_metadata_occurrence_created_at
    ON asset_metadata_occurrence (created_at);

COMMIT;
