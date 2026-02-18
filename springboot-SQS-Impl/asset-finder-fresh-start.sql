-- Asset Finder fresh-start schema
-- Recreates normalized asset metadata + upload-driven region/locale reference.

BEGIN;

-- Fresh start cleanup.
DROP TABLE IF EXISTS asset_metadata_occurrence;
DROP TABLE IF EXISTS asset_metadata_catalog;
DROP TABLE IF EXISTS asset_region_locale_ref;
DROP TABLE IF EXISTS asset_image_store;

-- Keep request metadata capture on raw_data_store.
ALTER TABLE IF EXISTS raw_data_store
    ADD COLUMN IF NOT EXISTS source_request_metadata JSONB;

-- Canonical metadata catalog (deduplicated by metadata_hash).
CREATE TABLE asset_metadata_catalog (
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
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_asset_metadata_catalog_metadata_hash UNIQUE (metadata_hash)
);

-- Versioned occurrence rows referencing catalog entries.
CREATE TABLE asset_metadata_occurrence (
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
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_asset_metadata_occurrence_source_version_slot
        UNIQUE (source_uri, source_version, asset_slot_key)
);

-- Upload-driven region/locale reference.
CREATE TABLE asset_region_locale_ref (
    id UUID PRIMARY KEY,
    geo_code TEXT NOT NULL,
    locale_code TEXT,
    display_name TEXT NOT NULL,
    apple_path TEXT NOT NULL,
    source_type TEXT NOT NULL DEFAULT 'UPLOAD',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    seen_count BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_asset_region_locale_ref_path_display UNIQUE (apple_path, display_name)
);

-- Indexes for query paths and joins.
CREATE INDEX idx_asset_metadata_catalog_interactive
    ON asset_metadata_catalog (interactive_path);

CREATE INDEX idx_asset_metadata_catalog_created_at
    ON asset_metadata_catalog (created_at);

CREATE INDEX idx_asset_metadata_occurrence_catalog_id
    ON asset_metadata_occurrence (catalog_id);

CREATE INDEX idx_asset_metadata_occurrence_raw_data_id
    ON asset_metadata_occurrence (raw_data_id);

CREATE INDEX idx_asset_metadata_occurrence_source_uri_version
    ON asset_metadata_occurrence (source_uri, source_version);

CREATE INDEX idx_asset_metadata_occurrence_filters
    ON asset_metadata_occurrence (tenant, environment, project, site, geo, locale);

CREATE INDEX idx_asset_metadata_occurrence_section
    ON asset_metadata_occurrence (section_path);

CREATE INDEX idx_asset_metadata_occurrence_created_at
    ON asset_metadata_occurrence (created_at);

CREATE INDEX idx_asset_region_locale_ref_geo
    ON asset_region_locale_ref (geo_code);

CREATE INDEX idx_asset_region_locale_ref_locale
    ON asset_region_locale_ref (locale_code);

CREATE INDEX idx_asset_region_locale_ref_path
    ON asset_region_locale_ref (apple_path);

CREATE INDEX idx_asset_region_locale_ref_source_type
    ON asset_region_locale_ref (source_type);

CREATE INDEX idx_asset_region_locale_ref_active
    ON asset_region_locale_ref (active);

COMMIT;
