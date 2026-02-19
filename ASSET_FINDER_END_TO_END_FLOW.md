# Asset Finder End-to-End Flow (Upload -> Storage -> UI Search)

This document explains the complete Asset Finder flow currently implemented in this repository, from JSON upload to Asset Finder UI results.

---

## 1) High-level architecture

Asset Finder is an additive pipeline on top of ingestion/cleansing:

- **Backend (Spring Boot):**
  - Extracts image metadata from uploaded JSON.
  - Stores data in normalized tables:
    - `asset_metadata_catalog` (canonical metadata)
    - `asset_metadata_occurrence` (versioned occurrence rows)
    - `asset_region_locale_ref` (upload-observed geo/locale reference)
  - Serves options/search/detail/count APIs for UI.

- **Frontend (Next.js):**
  - Uses API proxy routes under `/api/asset-finder/*`.
  - Calls backend endpoints and renders:
    - filter controls
    - asset tiles
    - metadata popup

- **Design principle:** fail-open
  - Asset extraction runs in a guarded block and does not break ingestion/cleansing if extraction fails.

---

## 2) Data model

## 2.1 `raw_data_store` (existing)

Asset Finder uses this existing ingestion table and reads:

- `id` (raw data id)
- `source_uri`
- `version`
- `source_request_metadata` (JSONB, optional upload metadata such as tenant/environment/project/site/geo/locale)

## 2.2 `asset_metadata_catalog` (canonical metadata)

One row per unique metadata hash (`metadata_hash` unique):

- Asset identity/content fields:
  - `asset_key`, `asset_model`, `interactive_path`, `preview_uri`
  - `alt_text`, `accessibility_text`
  - `viewports_json`, `asset_metadata_json`
- Hash and audit:
  - `metadata_hash`
  - `created_at`, `updated_at`

## 2.3 `asset_metadata_occurrence` (versioned occurrences)

One row per source/version/slot occurrence:

- Link fields:
  - `catalog_id` -> canonical row
  - `raw_data_id`
- Versioning and uniqueness:
  - `source_uri`, `source_version`, `asset_slot_key`
  - unique constraint: `(source_uri, source_version, asset_slot_key)`
- Context/filter fields:
  - `tenant`, `environment`, `project`, `site`, `geo`, `locale`
  - `section_path`, `section_uri`, `asset_node_path`
  - `request_metadata_json`

## 2.4 `asset_region_locale_ref` (upload-driven options reference)

Upload-observed geo/locale rows:

- `geo_code`, `locale_code`, `display_name`, `apple_path`
- `source_type` (default `UPLOAD`)
- `active`, `last_seen_at`, `seen_count`
- timestamps

---

## 3) Upload and extraction flow

## 3.1 Upload entry points

The upload flow starts from backend APIs in `DataExtractionController`:

- `POST /api/extract-cleanse-enrich-and-store` (multipart file)
- `POST /api/ingest-json-payload` (raw JSON body)

Optional query params can carry metadata (`tenant`, `environment`, `project`, `site`, `geo`, `locale`), serialized into `raw_data_store.source_request_metadata`.

## 3.2 Ingestion and cleansing handoff

`DataIngestionService.processLoadedContent(...)` parses JSON and then calls:

- `assetImageStoreService.safeExtractAndStore(rootNode, rawDataStore)`

This call is wrapped with try/catch and does not block ingestion if extraction fails.

## 3.3 Recursive asset discovery

`AssetImageStoreService` walks the JSON recursively:

- detects image-like keys (`icon`, any key containing `image`, `thumbnail`, etc.)
- verifies likely asset nodes by URI/viewport/alt/accessibility signals
- extracts:
  - interactive path / preview URI
  - alt/accessibility text
  - viewport map
  - full metadata JSON

## 3.4 Metadata inference and normalization

For each candidate, it resolves:

- `locale` (from request metadata, section/source paths, asset paths, defaults)
- `geo` (from locale or request/default)
- `site` (request metadata, section/source path inference, asset path fallback)
- `tenant`, `environment`, `project`

Recent behavior hardening:

- site inference/search can use `section_path` and `source_uri` context (not only asset URI), avoiding undercount for specific site selections like `ipad`.

## 3.5 Deduplication and persistence

For each run:

1. Build candidate hash values:
   - `metadata_hash` for catalog dedupe
   - `asset_slot_key` for per-source/version slot dedupe
2. Deduplicate in-memory by slot.
3. Replace prior occurrence snapshot for same `(source_uri, source_version)`.
4. Upsert catalog rows by `metadata_hash`.
5. Insert occurrence rows referencing catalog IDs.

This guarantees replay safety and versioned occurrence history.

## 3.6 Region/locale reference updates

During extraction, upload observations are upserted into `asset_region_locale_ref`:

- source type `UPLOAD`
- `last_seen_at` updated
- `seen_count` incremented

---

## 4) Backend Asset Finder APIs

Implemented in `AssetFinderController`:

- `GET /api/asset-finder/options`
- `POST /api/asset-finder/search`
- `GET /api/asset-finder/assets/{id}`
- `GET /api/asset-finder/count/by-cleansed/{id}`

## 4.1 Options

`AssetImageStoreService.getOptions()` builds options from:

1. Distinct occurrence rows (preferred)
2. Fallback `asset_region_locale_ref`
3. Static defaults if needed

Geo values are grouped into business regions (Europe, IN, JP, KR, SEA, WW, CEMEA, ANZ, ALAC-CA), with locale mappings per group.

## 4.2 Search

Search receives tenant/environment/project/site/geo/locale/page/size.

Important behavior:

- page size bounded to max 200.
- UI currently requests `size=200`.
- locale is strongest selector; grouped geo labels are normalized.
- site matching includes:
  - `site`
  - `section_path` path match
  - `source_uri` path match

This is why selected site values now align better with expected counts.

## 4.3 Detail and count

- detail endpoint joins occurrence + catalog for full popup data.
- count endpoint returns extracted row count for a specific cleansed upload id.

---

## 5) Next.js proxy and UI flow

## 5.1 Proxy routes

Next.js routes under `nextjs/src/app/api/asset-finder/*` proxy backend calls:

- `options/route.ts`
  - canonical backend call first
  - fallback paths/derived options fallback logic
- `search/route.ts`
- `assets/[id]/route.ts`
- `count/by-cleansed/[id]/route.ts`

## 5.2 Asset Finder page behavior

`nextjs/src/app/asset-finder/page.tsx`:

1. Loads options from `/api/asset-finder/options`.
2. Initializes filters from options.
3. On Filter click, posts to `/api/asset-finder/search` with:
   - selected filters
   - `page: 0`
   - `size: 200`
4. Renders:
   - `Count: visible/total` (`visible` may be reduced by client-only "Show Locale Specific Assets" toggle)
   - squarish white tiles with interactive path links
   - detail popup on info icon click

---

## 6) Fresh-start SQL setup

For a clean local reset (drop + recreate Asset Finder tables), use:

- `ASSET_FINDER_END_TO_END_FLOW.md` (this document)
- `springboot-SQS-Impl/asset-finder-fresh-start.sql` (single SQL script)

That script recreates:

- `asset_metadata_catalog`
- `asset_metadata_occurrence`
- `asset_region_locale_ref`
- indexes + constraints

---

## 7) Troubleshooting checklist

If counts in UI and DB differ:

1. Confirm backend is on latest branch commit.
2. Log outbound UI payload (tenant/env/project/site/geo/locale/page/size).
3. Check whether UI is showing `visible/total`; `visible` can be lower with locale-specific toggle.
4. Validate DB counts with the same filters used by UI, not only `raw_data_id`.
5. Confirm `source_uri`/`section_path` includes expected site token (`/ipad/`, `/mac/`) where applicable.

---

## 8) Non-impact guarantee (pipeline safety)

Asset Finder extraction is additive:

- runs in a separate guarded step
- errors are logged and swallowed at extraction boundary
- ingestion/cleansing/enrichment/search/chatbot core flows continue as before

This keeps existing pipelines stable while enabling independent asset metadata search.

