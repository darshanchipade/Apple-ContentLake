package com.apple.springboot.service;

import com.apple.springboot.model.AssetRegionLocaleRef;
import com.apple.springboot.repository.AssetRegionLocaleRefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Upload-driven locale reference service for Asset Finder options.
 *
 * This service no longer performs Apple website sync or TTL cache refreshes.
 * It only tracks locales observed during upload/extraction and serves options
 * from those observed rows, with safe static fallback defaults.
 */
@Service
public class AssetRegionLocaleService {

    private static final Logger logger = LoggerFactory.getLogger(AssetRegionLocaleService.class);

    public static final String SOURCE_UPLOAD = "UPLOAD";

    private static final Map<String, String> GEO_TO_LOCALE_COUNTRY = Map.ofEntries(
            Map.entry("WW", "US"),
            Map.entry("UK", "GB")
    );

    private static final Map<String, List<String>> FALLBACK_GEO_TO_LOCALES = Map.of(
            "WW", List.of("en_US"),
            "JP", List.of("ja_JP"),
            "KR", List.of("ko_KR")
    );

    private final AssetRegionLocaleRefRepository repository;
    private final JdbcTemplate jdbcTemplate;
    // If schema changes while running, restart app. Until true, keep rechecking.
    private volatile Boolean tablePresent;

    /**
     * Creates a locale service backed by the asset_region_locale_ref table.
     */
    public AssetRegionLocaleService(AssetRegionLocaleRefRepository repository,
                                    JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns geo/locale options from upload-observed rows.
     */
    public RegionOptionsSnapshot getRegionOptionsSnapshot() {
        if (!isTablePresent()) {
            return buildFallbackSnapshot();
        }
        try {
            List<AssetRegionLocaleRef> uploadRows =
                    repository.findByActiveTrueAndSourceTypeOrderByGeoCodeAscLocaleCodeAscDisplayNameAsc(SOURCE_UPLOAD);
            if (uploadRows == null || uploadRows.isEmpty()) {
                return buildFallbackSnapshot();
            }
            RegionOptionsSnapshot snapshot = buildSnapshotFromRows(uploadRows);
            if (snapshot.geos().isEmpty() || snapshot.geoToLocales().isEmpty()) {
                return buildFallbackSnapshot();
            }
            return snapshot;
        } catch (Exception e) {
            logger.warn("Unable to read upload locale reference rows. Falling back to defaults. Reason: {}", e.getMessage());
            return buildFallbackSnapshot();
        }
    }

    /**
     * Returns a best default locale for a given geo code when available.
     */
    public Optional<String> getDefaultLocaleForGeo(String geoCode) {
        String normalizedGeo = normalizeGeo(geoCode);
        if (normalizedGeo == null) {
            return Optional.empty();
        }
        RegionOptionsSnapshot snapshot = getRegionOptionsSnapshot();
        List<String> locales = snapshot.geoToLocales().get(normalizedGeo);
        if (locales == null || locales.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(locales.get(0));
    }

    /**
     * Upserts upload-derived geo/locale observations into reference table.
     */
    public void recordUploadObservations(java.util.Collection<RegionLocaleObservation> observations) {
        if (observations == null || observations.isEmpty() || !isTablePresent()) {
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();

            Map<String, RegionLocaleObservation> normalizedByLocale = new LinkedHashMap<>();
            for (RegionLocaleObservation observation : observations) {
                if (observation == null) {
                    continue;
                }
                String locale = normalizeLocale(observation.localeCode());
                String geo = normalizeGeo(observation.geoCode());
                if (geo == null && locale != null && locale.length() >= 5) {
                    geo = locale.substring(3).toUpperCase(Locale.ROOT);
                }
                if (locale == null || geo == null) {
                    continue;
                }
                String displayName = normalizeText(observation.displayName());
                if (displayName == null) {
                    displayName = "Uploaded locale " + locale;
                }
                String applePath = normalizeStorefrontPath(observation.applePath());
                if (applePath == null) {
                    applePath = buildStorefrontPathFromLocale(locale);
                }
                normalizedByLocale.putIfAbsent(
                        locale,
                        new RegionLocaleObservation(geo, locale, displayName, applePath)
                );
            }

            if (normalizedByLocale.isEmpty()) {
                return;
            }

            List<AssetRegionLocaleRef> existingActiveRows =
                    repository.findByActiveTrueAndSourceTypeOrderByGeoCodeAscLocaleCodeAscDisplayNameAsc(SOURCE_UPLOAD);

            Map<String, AssetRegionLocaleRef> existingByLocale = new LinkedHashMap<>();
            List<AssetRegionLocaleRef> changedRows = new ArrayList<>();

            // Safety cleanup: keep only one active UPLOAD row per locale.
            for (AssetRegionLocaleRef row : existingActiveRows) {
                String locale = normalizeLocale(row.getLocaleCode());
                if (locale == null) {
                    continue;
                }
                AssetRegionLocaleRef prior = existingByLocale.putIfAbsent(locale, row);
                if (prior != null) {
                    row.setActive(false);
                    changedRows.add(row);
                }
            }

            for (RegionLocaleObservation observation : normalizedByLocale.values()) {
                AssetRegionLocaleRef row = existingByLocale.get(observation.localeCode());
                if (row == null) {
                    row = new AssetRegionLocaleRef();
                    row.setSourceType(SOURCE_UPLOAD);
                    row.setGeoCode(observation.geoCode());
                    row.setLocaleCode(observation.localeCode());
                    row.setDisplayName(observation.displayName());
                    row.setApplePath(observation.applePath());
                    row.setActive(true);
                    row.setLastSeenAt(now);
                    row.setSeenCount(1L);
                    changedRows.add(row);
                    continue;
                }

                row.setSourceType(SOURCE_UPLOAD);
                row.setGeoCode(observation.geoCode());
                row.setLocaleCode(observation.localeCode());
                row.setDisplayName(observation.displayName());
                row.setApplePath(observation.applePath());
                row.setActive(true);
                row.setLastSeenAt(now);
                row.setSeenCount(Optional.ofNullable(row.getSeenCount()).orElse(0L) + 1L);
                changedRows.add(row);
            }

            if (!changedRows.isEmpty()) {
                repository.saveAll(changedRows);
            }
        } catch (Exception e) {
            logger.warn("Failed to upsert upload-derived region/locale observations. Continuing. Reason: {}", e.getMessage());
        }
    }

    /**
     * Builds response snapshot grouped by geo code and sorted locale lists.
     */
    private RegionOptionsSnapshot buildSnapshotFromRows(List<AssetRegionLocaleRef> rows) {
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (AssetRegionLocaleRef row : rows) {
            if (row == null || !Boolean.TRUE.equals(row.getActive())) {
                continue;
            }
            String locale = normalizeLocale(row.getLocaleCode());
            if (locale == null) {
                continue;
            }
            String geo = normalizeGeo(row.getGeoCode());
            if (geo == null && locale.length() >= 5) {
                geo = locale.substring(3).toUpperCase(Locale.ROOT);
            }
            if (geo == null) {
                continue;
            }
            grouped.computeIfAbsent(geo, ignored -> new LinkedHashSet<>()).add(locale);
        }

        if (grouped.isEmpty()) {
            return buildFallbackSnapshot();
        }

        List<String> geos = new ArrayList<>(grouped.keySet());
        Collections.sort(geos);

        Map<String, List<String>> geoToLocales = new LinkedHashMap<>();
        for (String geo : geos) {
            List<String> locales = new ArrayList<>(grouped.getOrDefault(geo, new LinkedHashSet<>()));
            Collections.sort(locales);
            if (locales.isEmpty()) {
                String fallbackLocale = fallbackLocaleForGeo(geo);
                if (fallbackLocale != null) {
                    locales.add(fallbackLocale);
                }
            }
            geoToLocales.put(geo, List.copyOf(locales));
        }
        return new RegionOptionsSnapshot(List.copyOf(geos), Map.copyOf(geoToLocales));
    }

    /**
     * Builds static fallback snapshot used when no upload rows exist.
     */
    private RegionOptionsSnapshot buildFallbackSnapshot() {
        List<String> geos = new ArrayList<>(FALLBACK_GEO_TO_LOCALES.keySet());
        Collections.sort(geos);
        Map<String, List<String>> geoToLocales = new LinkedHashMap<>();
        for (String geo : geos) {
            List<String> locales = FALLBACK_GEO_TO_LOCALES.getOrDefault(geo, List.of())
                    .stream()
                    .map(this::normalizeLocale)
                    .filter(Objects::nonNull)
                    .toList();
            geoToLocales.put(geo, locales);
        }
        return new RegionOptionsSnapshot(List.copyOf(geos), Map.copyOf(geoToLocales));
    }

    /**
     * Returns true when the reference table exists in the current schema.
     */
    boolean isTablePresent() {
        if (Boolean.TRUE.equals(tablePresent)) {
            return true;
        }
        boolean present;
        try {
            String reg = jdbcTemplate.queryForObject(
                    "select to_regclass('public.asset_region_locale_ref')",
                    String.class
            );
            present = reg != null;
        } catch (Exception ignored) {
            present = false;
        }
        if (present) {
            tablePresent = true;
        }
        return present;
    }

    /**
     * Normalizes locale values into language_COUNTRY format.
     */
    private String normalizeLocale(String locale) {
        if (!StringUtils.hasText(locale)) {
            return null;
        }
        String normalized = locale.trim().replace('-', '_');
        String[] parts = normalized.split("_");
        if (parts.length != 2) {
            return null;
        }
        String language = parts[0].toLowerCase(Locale.ROOT);
        String country = parts[1].toUpperCase(Locale.ROOT);
        if (language.length() != 2 || country.length() != 2) {
            return null;
        }
        return language + "_" + country;
    }

    /**
     * Normalizes a geo code to uppercase.
     */
    private String normalizeGeo(String geo) {
        if (!StringUtils.hasText(geo)) {
            return null;
        }
        String normalized = geo.trim().toUpperCase(Locale.ROOT);
        return normalized.length() == 2 ? normalized : null;
    }

    /**
     * Normalizes general text values.
     */
    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Normalizes storefront path values.
     */
    private String normalizeStorefrontPath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return null;
        }
        String normalized = rawPath.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    /**
     * Converts locale values into storefront-like paths.
     */
    private String buildStorefrontPathFromLocale(String locale) {
        String normalized = normalizeLocale(locale);
        if (normalized == null || normalized.length() < 5) {
            return "/us/";
        }
        String language = normalized.substring(0, 2).toLowerCase(Locale.ROOT);
        String country = normalized.substring(3).toLowerCase(Locale.ROOT);
        if ("en".equals(language)) {
            return "/" + country + "/";
        }
        return "/" + country + "/" + language + "/";
    }

    /**
     * Returns fallback locale for a geo.
     */
    private String fallbackLocaleForGeo(String geo) {
        String normalizedGeo = normalizeGeo(geo);
        if (normalizedGeo == null) {
            return null;
        }
        List<String> configuredFallback = FALLBACK_GEO_TO_LOCALES.get(normalizedGeo);
        if (configuredFallback != null && !configuredFallback.isEmpty()) {
            return normalizeLocale(configuredFallback.get(0));
        }
        String country = GEO_TO_LOCALE_COUNTRY.getOrDefault(normalizedGeo, normalizedGeo);
        return normalizeLocale("en_" + country);
    }

    /**
     * Immutable region options payload used by Asset Finder options endpoint.
     */
    public record RegionOptionsSnapshot(List<String> geos, Map<String, List<String>> geoToLocales) {}

    /**
     * Upload observation of one geo/locale pair.
     */
    public record RegionLocaleObservation(String geoCode, String localeCode, String displayName, String applePath) {}
}