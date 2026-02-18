-- Asset Finder region/locale reference table
-- Sync target for https://www.apple.com/choose-country-region/

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

-- Safety defaults if sync has not run yet.
INSERT INTO asset_region_locale_ref (id, geo_code, locale_code, display_name, apple_path, source_type, active, last_seen_at, seen_count)
SELECT '00000000-0000-0000-0000-0000000000a1'::uuid, 'WW', 'en_US', 'Worldwide (fallback)', '/us/', 'APPLE', TRUE, NOW(), 1
WHERE NOT EXISTS (
    SELECT 1 FROM asset_region_locale_ref WHERE source_type = 'APPLE' AND geo_code = 'WW' AND locale_code = 'en_US'
);

INSERT INTO asset_region_locale_ref (id, geo_code, locale_code, display_name, apple_path, source_type, active, last_seen_at, seen_count)
SELECT '00000000-0000-0000-0000-0000000000a2'::uuid, 'JP', 'ja_JP', 'Japan (fallback)', '/jp/', 'APPLE', TRUE, NOW(), 1
WHERE NOT EXISTS (
    SELECT 1 FROM asset_region_locale_ref WHERE source_type = 'APPLE' AND geo_code = 'JP' AND locale_code = 'ja_JP'
);

INSERT INTO asset_region_locale_ref (id, geo_code, locale_code, display_name, apple_path, source_type, active, last_seen_at, seen_count)
SELECT '00000000-0000-0000-0000-0000000000a3'::uuid, 'KR', 'ko_KR', 'Korea (fallback)', '/kr/', 'APPLE', TRUE, NOW(), 1
WHERE NOT EXISTS (
    SELECT 1 FROM asset_region_locale_ref WHERE source_type = 'APPLE' AND geo_code = 'KR' AND locale_code = 'ko_KR'
);

COMMIT;
