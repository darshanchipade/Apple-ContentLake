-- Asset Finder region/locale reference table
-- Upload-driven locale tracking reference for Asset Finder options.

BEGIN;

CREATE TABLE IF NOT EXISTS asset_region_locale_ref (
    id UUID PRIMARY KEY,
    geo_code TEXT NOT NULL,
    locale_code TEXT,
    display_name TEXT NOT NULL,
    apple_path TEXT NOT NULL,
    source_type TEXT NOT NULL DEFAULT 'APPLE',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    seen_count BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE IF EXISTS asset_region_locale_ref
    ADD COLUMN IF NOT EXISTS source_type TEXT NOT NULL DEFAULT 'APPLE';

ALTER TABLE IF EXISTS asset_region_locale_ref
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE IF EXISTS asset_region_locale_ref
    ADD COLUMN IF NOT EXISTS seen_count BIGINT NOT NULL DEFAULT 1;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        WHERE c.conname = 'uk_asset_region_locale_ref_path_display'
          AND c.conrelid = 'asset_region_locale_ref'::regclass
    ) THEN
        ALTER TABLE asset_region_locale_ref
            ADD CONSTRAINT uk_asset_region_locale_ref_path_display
            UNIQUE (apple_path, display_name);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        WHERE c.conname = 'uk_asset_region_locale_ref_source_locale'
          AND c.conrelid = 'asset_region_locale_ref'::regclass
    ) THEN
        ALTER TABLE asset_region_locale_ref
            ADD CONSTRAINT uk_asset_region_locale_ref_source_locale
            UNIQUE (source_type, locale_code);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_asset_region_locale_ref_geo
    ON asset_region_locale_ref (geo_code);

CREATE INDEX IF NOT EXISTS idx_asset_region_locale_ref_locale
    ON asset_region_locale_ref (locale_code);

CREATE INDEX IF NOT EXISTS idx_asset_region_locale_ref_path
    ON asset_region_locale_ref (apple_path);

CREATE INDEX IF NOT EXISTS idx_asset_region_locale_ref_source_type
    ON asset_region_locale_ref (source_type);

CREATE INDEX IF NOT EXISTS idx_asset_region_locale_ref_active
    ON asset_region_locale_ref (active);

COMMIT;
