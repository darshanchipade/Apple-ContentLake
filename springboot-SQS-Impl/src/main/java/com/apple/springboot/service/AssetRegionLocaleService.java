package com.apple.springboot.service;

import com.apple.springboot.model.AssetRegionLocaleRef;
import com.apple.springboot.repository.AssetRegionLocaleRefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maintains geo/locale reference data for Asset Finder options.
 *
 * Strategy:
 * - Source of truth: asset_region_locale_ref table.
 * - Sync source: https://www.apple.com/choose-country-region/
 * - Cache: in-memory with TTL.
 * - Fallback: last known DB rows, then static defaults.
 */
@Service
public class AssetRegionLocaleService {

    private static final Logger logger = LoggerFactory.getLogger(AssetRegionLocaleService.class);

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "<a\\b[^>]*?href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern COUNTRY_ONLY_PATTERN = Pattern.compile("^/([a-z]{2})/$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTRY_LANGUAGE_PATH_PATTERN = Pattern.compile("^/([a-z]{2})/([a-z]{2})/$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTRY_LANGUAGE_HYPHEN_PATTERN = Pattern.compile("^/([a-z]{2})-([a-z]{2})/$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> DEFAULT_LANGUAGE_BY_GEO = Map.ofEntries(
            Map.entry("WW", "en"),
            Map.entry("LA", "es"),
            Map.entry("JP", "ja"),
            Map.entry("KR", "ko"),
            Map.entry("TW", "zh"),
            Map.entry("HK", "zh"),
            Map.entry("MO", "zh"),
            Map.entry("TH", "th"),
            Map.entry("VN", "vi"),
            Map.entry("UA", "uk")
    );

    private static final Map<String, List<String>> FALLBACK_GEO_TO_LOCALES = Map.of(
            "WW", List.of("en_US"),
            "JP", List.of("ja_JP"),
            "KR", List.of("ko_KR")
    );

    private static final Set<String> ISO_LANGUAGE_CODES = Set.of(Locale.getISOLanguages());
    private static final Set<String> LANGUAGE_COUNTRY_COMBINATIONS = buildLanguageCountryCombinations();

    private final AssetRegionLocaleRefRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;

    private final Object cacheLock = new Object();
    private volatile CachedRegionOptions cachedRegionOptions;
    // If schema changes while running, restart app. Until true, keep rechecking.
    private volatile Boolean tablePresent;

    @Value("${app.asset-finder.region-sync.enabled:true}")
    private boolean syncEnabled;

    @Value("${app.asset-finder.region-source-url:https://www.apple.com/choose-country-region/}")
    private String regionSourceUrl;

    @Value("${app.asset-finder.region-cache-ttl-minutes:180}")
    private long cacheTtlMinutes;

    @Value("${app.asset-finder.region-sync-read-timeout-ms:20000}")
    private long syncReadTimeoutMs;

    @Value("${app.asset-finder.region-sync-user-agent:AssetFinderRegionSync/1.0}")
    private String syncUserAgent;

    /**
     * Creates a service that syncs/storefront region mappings and serves cached options.
     */
    public AssetRegionLocaleService(AssetRegionLocaleRefRepository repository,
                                    JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Refreshes cache on startup and then attempts an immediate sync.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshCacheFromDb();
        if (syncEnabled) {
            syncFromRemote("startup");
        }
    }

    /**
     * Runs periodic sync from Apple region page to keep reference table current.
     */
    @Scheduled(
            initialDelayString = "${app.asset-finder.region-sync-initial-delay-ms:60000}",
            fixedDelayString = "${app.asset-finder.region-sync-fixed-delay-ms:86400000}"
    )
    public void scheduledSync() {
        if (syncEnabled) {
            syncFromRemote("scheduled");
        }
    }

    /**
     * Returns cached geo/locale options, refreshing from DB when TTL has expired.
     */
    public RegionOptionsSnapshot getRegionOptionsSnapshot() {
        CachedRegionOptions current = cachedRegionOptions;
        if (current != null && !current.isExpired(cacheTtlMinutes)) {
            return current.snapshot();
        }
        synchronized (cacheLock) {
            CachedRegionOptions refreshed = cachedRegionOptions;
            if (refreshed != null && !refreshed.isExpired(cacheTtlMinutes)) {
                return refreshed.snapshot();
            }
            RegionOptionsSnapshot snapshot = loadSnapshotFromDbOrFallback();
            cachedRegionOptions = new CachedRegionOptions(snapshot, Instant.now());
            return snapshot;
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
     * Attempts remote sync and degrades gracefully when unavailable.
     */
    void syncFromRemote(String trigger) {
        if (!isTablePresent()) {
            logger.info("Skipping region sync ({}): asset_region_locale_ref table not available.", trigger);
            return;
        }
        try {
            String html = fetchRemotePage(regionSourceUrl);
            List<ParsedRegionEntry> parsedEntries = parseRegionEntries(html);
            if (parsedEntries.isEmpty()) {
                logger.warn("Region sync ({}) returned no storefront entries. Keeping last known mappings.", trigger);
                refreshCacheFromDb();
                return;
            }
            upsertReferenceRows(parsedEntries);
            refreshCacheFromDb();
            logger.info("Region sync ({}) completed successfully with {} entries.", trigger, parsedEntries.size());
        } catch (Exception e) {
            logger.warn("Region sync ({}) failed. Using last known mappings. Reason: {}", trigger, e.getMessage());
            refreshCacheFromDb();
        }
    }

    /**
     * Refreshes in-memory cache from DB, falling back safely when needed.
     */
    private void refreshCacheFromDb() {
        synchronized (cacheLock) {
            RegionOptionsSnapshot snapshot = loadSnapshotFromDbOrFallback();
            cachedRegionOptions = new CachedRegionOptions(snapshot, Instant.now());
        }
    }

    /**
     * Loads snapshot from DB rows or fallback defaults when table/rows are absent.
     */
    private RegionOptionsSnapshot loadSnapshotFromDbOrFallback() {
        if (!isTablePresent()) {
            return buildFallbackSnapshot();
        }
        try {
            List<AssetRegionLocaleRef> rows = repository.findByActiveTrueOrderByGeoCodeAscDisplayNameAscApplePathAsc();
            if (rows == null || rows.isEmpty()) {
                return buildFallbackSnapshot();
            }
            RegionOptionsSnapshot snapshot = buildSnapshotFromRows(rows);
            if (snapshot.geos().isEmpty() || snapshot.geoToLocales().isEmpty()) {
                return buildFallbackSnapshot();
            }
            return snapshot;
        } catch (Exception e) {
            logger.warn("Unable to read region reference table. Falling back to defaults. Reason: {}", e.getMessage());
            return buildFallbackSnapshot();
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
            String geo = normalizeGeo(row.getGeoCode());
            if (geo == null) {
                continue;
            }
            grouped.computeIfAbsent(geo, ignored -> new LinkedHashSet<>());
            String locale = normalizeLocale(row.getLocaleCode());
            if (locale != null) {
                grouped.get(geo).add(locale);
            }
        }

        // Always keep minimal defaults available for safety.
        FALLBACK_GEO_TO_LOCALES.forEach((geo, locales) -> {
            grouped.computeIfAbsent(geo, ignored -> new LinkedHashSet<>());
            locales.stream()
                    .map(this::normalizeLocale)
                    .filter(Objects::nonNull)
                    .forEach(grouped.get(geo)::add);
        });

        List<String> geos = new ArrayList<>(grouped.keySet());
        Collections.sort(geos);

        Map<String, List<String>> geoToLocales = new LinkedHashMap<>();
        for (String geo : geos) {
            LinkedHashSet<String> rawLocales = grouped.getOrDefault(geo, new LinkedHashSet<>());
            List<String> locales = new ArrayList<>(rawLocales);
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
     * Builds static fallback snapshot used when DB/source data are unavailable.
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
     * Upserts parsed rows and deactivates records not found in the latest source snapshot.
     */
    private void upsertReferenceRows(List<ParsedRegionEntry> parsedEntries) {
        List<AssetRegionLocaleRef> existingRows = repository.findAll();
        Map<String, AssetRegionLocaleRef> existingByKey = new HashMap<>();
        for (AssetRegionLocaleRef row : existingRows) {
            existingByKey.put(rowKey(row.getApplePath(), row.getDisplayName()), row);
        }

        Set<String> seen = new HashSet<>();
        List<AssetRegionLocaleRef> changedRows = new ArrayList<>();

        for (ParsedRegionEntry entry : parsedEntries) {
            String key = rowKey(entry.applePath(), entry.displayName());
            seen.add(key);
            AssetRegionLocaleRef row = existingByKey.get(key);
            boolean isNew = row == null;
            if (isNew) {
                row = new AssetRegionLocaleRef();
            }

            boolean changed = false;
            changed |= assignIfChanged(row::getGeoCode, row::setGeoCode, entry.geoCode());
            changed |= assignIfChanged(row::getLocaleCode, row::setLocaleCode, entry.localeCode());
            changed |= assignIfChanged(row::getDisplayName, row::setDisplayName, entry.displayName());
            changed |= assignIfChanged(row::getApplePath, row::setApplePath, entry.applePath());
            changed |= assignIfChanged(row::getActive, row::setActive, true);

            if (isNew || changed) {
                changedRows.add(row);
            }
        }

        for (AssetRegionLocaleRef row : existingRows) {
            String key = rowKey(row.getApplePath(), row.getDisplayName());
            if (!seen.contains(key) && Boolean.TRUE.equals(row.getActive())) {
                row.setActive(false);
                changedRows.add(row);
            }
        }

        if (!changedRows.isEmpty()) {
            repository.saveAll(changedRows);
        }
    }

    /**
     * Helper to avoid unnecessary persistence updates.
     */
    private <T> boolean assignIfChanged(ValueReader<T> reader, ValueWriter<T> writer, T nextValue) {
        T currentValue = reader.read();
        if (Objects.equals(currentValue, nextValue)) {
            return false;
        }
        writer.write(nextValue);
        return true;
    }

    /**
     * Downloads the Apple country/region page HTML.
     */
    private String fetchRemotePage(String sourceUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sourceUrl))
                .GET()
                .timeout(Duration.ofMillis(Math.max(1000, syncReadTimeoutMs)))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", syncUserAgent)
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Unexpected HTTP status " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Parses storefront links and display names from raw HTML content.
     */
    private List<ParsedRegionEntry> parseRegionEntries(String html) {
        if (!StringUtils.hasText(html)) {
            return List.of();
        }

        List<RawAnchorEntry> rawEntries = new ArrayList<>();
        Map<String, Set<String>> explicitLanguagesByGeo = new HashMap<>();

        Matcher matcher = ANCHOR_PATTERN.matcher(html);
        while (matcher.find()) {
            String storefrontPath = normalizeStorefrontPath(matcher.group(1));
            if (storefrontPath == null) {
                continue;
            }
            String displayName = normalizeDisplayName(matcher.group(2));
            if (displayName == null) {
                continue;
            }
            rawEntries.add(new RawAnchorEntry(storefrontPath, displayName));
            collectExplicitLanguage(storefrontPath, explicitLanguagesByGeo);
        }

        Map<String, ParsedRegionEntry> deduped = new LinkedHashMap<>();
        for (RawAnchorEntry raw : rawEntries) {
            ParsedRegionEntry parsed = mapPathToEntry(raw, explicitLanguagesByGeo);
            if (parsed == null) {
                continue;
            }
            String key = parsed.geoCode() + "|" + Optional.ofNullable(parsed.localeCode()).orElse("")
                    + "|" + parsed.applePath() + "|" + parsed.displayName();
            deduped.putIfAbsent(key, parsed);
        }

        return deduped.values().stream()
                .sorted(Comparator
                        .comparing(ParsedRegionEntry::geoCode)
                        .thenComparing(ParsedRegionEntry::applePath)
                        .thenComparing(ParsedRegionEntry::displayName))
                .toList();
    }

    /**
     * Converts a parsed anchor entry into a normalized table row candidate.
     */
    private ParsedRegionEntry mapPathToEntry(RawAnchorEntry raw,
                                             Map<String, Set<String>> explicitLanguagesByGeo) {
        Matcher langPath = COUNTRY_LANGUAGE_PATH_PATTERN.matcher(raw.applePath());
        if (langPath.matches()) {
            String geo = normalizeGeo(langPath.group(1));
            String language = normalizeLanguage(langPath.group(2));
            if (geo == null || language == null) {
                return null;
            }
            return new ParsedRegionEntry(geo, normalizeLocale(language + "_" + geo), raw.displayName(), raw.applePath());
        }

        Matcher langHyphen = COUNTRY_LANGUAGE_HYPHEN_PATTERN.matcher(raw.applePath());
        if (langHyphen.matches()) {
            String geo = normalizeGeo(langHyphen.group(1));
            String language = normalizeLanguage(langHyphen.group(2));
            if (geo == null || language == null) {
                return null;
            }
            return new ParsedRegionEntry(geo, normalizeLocale(language + "_" + geo), raw.displayName(), raw.applePath());
        }

        Matcher countryOnly = COUNTRY_ONLY_PATTERN.matcher(raw.applePath());
        if (countryOnly.matches()) {
            String geo = normalizeGeo(countryOnly.group(1));
            if (geo == null) {
                return null;
            }
            Set<String> explicitLanguages = explicitLanguagesByGeo.getOrDefault(geo, Set.of());
            String defaultLanguage = resolveDefaultLanguage(geo, explicitLanguages);
            String locale = defaultLanguage != null ? normalizeLocale(defaultLanguage + "_" + geo) : null;
            return new ParsedRegionEntry(geo, locale, raw.displayName(), raw.applePath());
        }

        return null;
    }

    /**
     * Collects explicit language hints from path formats like /ca/fr/ or /bh-ar/.
     */
    private void collectExplicitLanguage(String storefrontPath, Map<String, Set<String>> explicitLanguagesByGeo) {
        Matcher langPath = COUNTRY_LANGUAGE_PATH_PATTERN.matcher(storefrontPath);
        if (langPath.matches()) {
            String geo = normalizeGeo(langPath.group(1));
            String language = normalizeLanguage(langPath.group(2));
            addLanguageHint(explicitLanguagesByGeo, geo, language);
            return;
        }

        Matcher langHyphen = COUNTRY_LANGUAGE_HYPHEN_PATTERN.matcher(storefrontPath);
        if (langHyphen.matches()) {
            String geo = normalizeGeo(langHyphen.group(1));
            String language = normalizeLanguage(langHyphen.group(2));
            addLanguageHint(explicitLanguagesByGeo, geo, language);
        }
    }

    /**
     * Adds one explicit language hint into the in-memory map.
     */
    private void addLanguageHint(Map<String, Set<String>> hints, String geo, String language) {
        if (geo == null || language == null) {
            return;
        }
        hints.computeIfAbsent(geo, ignored -> new LinkedHashSet<>()).add(language);
    }

    /**
     * Derives a default language code for bare geo-only storefront paths.
     */
    private String resolveDefaultLanguage(String geo, Set<String> explicitLanguages) {
        if (explicitLanguages != null && explicitLanguages.contains("en")) {
            return "en";
        }
        if (explicitLanguages != null && explicitLanguages.contains("ar")) {
            // For "/xx-ar/" plus "/xx/", the country root is usually English.
            return "en";
        }
        String override = DEFAULT_LANGUAGE_BY_GEO.get(geo);
        if (override != null) {
            return override;
        }
        if (explicitLanguages != null && explicitLanguages.size() == 1) {
            return explicitLanguages.iterator().next();
        }

        String isoGuess = geo.toLowerCase(Locale.ROOT);
        if (ISO_LANGUAGE_CODES.contains(isoGuess)
                && LANGUAGE_COUNTRY_COMBINATIONS.contains(isoGuess + "_" + geo)) {
            return isoGuess;
        }
        return "en";
    }

    /**
     * Normalizes and validates storefront paths that represent region links.
     */
    private String normalizeStorefrontPath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return null;
        }
        String trimmed = rawPath.trim();
        int queryIndex = trimmed.indexOf('?');
        if (queryIndex >= 0) {
            trimmed = trimmed.substring(0, queryIndex);
        }
        int hashIndex = trimmed.indexOf('#');
        if (hashIndex >= 0) {
            trimmed = trimmed.substring(0, hashIndex);
        }
        if (!trimmed.startsWith("/")) {
            return null;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }

        if (COUNTRY_ONLY_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }
        if (COUNTRY_LANGUAGE_PATH_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }
        if (COUNTRY_LANGUAGE_HYPHEN_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }
        return null;
    }

    /**
     * Normalizes display labels from HTML anchor text.
     */
    private String normalizeDisplayName(String htmlText) {
        if (!StringUtils.hasText(htmlText)) {
            return null;
        }
        String withoutTags = HTML_TAG_PATTERN.matcher(htmlText).replaceAll(" ");
        String decoded = HtmlUtils.htmlUnescape(withoutTags);
        String normalized = decoded.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? null : normalized;
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
     * Builds a stable key for detecting existing rows by path/display combination.
     */
    private String rowKey(String applePath, String displayName) {
        return normalizeLower(applePath) + "|" + normalizeLower(displayName);
    }

    /**
     * Normalizes values for case-insensitive dedupe keys.
     */
    private String normalizeLower(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
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
     * Normalizes a language code to lowercase.
     */
    private String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return normalized.length() == 2 ? normalized : null;
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
        String language = normalizeLanguage(parts[0]);
        String country = normalizeGeo(parts[1]);
        if (language == null || country == null) {
            return null;
        }
        return language + "_" + country;
    }

    /**
     * Chooses a safe fallback locale for geos lacking explicit mappings.
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
        String language = DEFAULT_LANGUAGE_BY_GEO.getOrDefault(normalizedGeo, "en");
        return normalizeLocale(language + "_" + normalizedGeo);
    }

    /**
     * Builds a set of language-country combinations known by java.util.Locale.
     */
    private static Set<String> buildLanguageCountryCombinations() {
        Set<String> combinations = new LinkedHashSet<>();
        for (Locale locale : Locale.getAvailableLocales()) {
            String language = locale.getLanguage();
            String country = locale.getCountry();
            if (language != null && language.length() == 2 && country != null && country.length() == 2) {
                combinations.add(language.toLowerCase(Locale.ROOT) + "_" + country.toUpperCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(combinations);
    }

    /**
     * Immutable region options payload used by Asset Finder options endpoint.
     */
    public record RegionOptionsSnapshot(List<String> geos, Map<String, List<String>> geoToLocales) {}

    private record RawAnchorEntry(String applePath, String displayName) {}

    private record ParsedRegionEntry(String geoCode, String localeCode, String displayName, String applePath) {}

    private record CachedRegionOptions(RegionOptionsSnapshot snapshot, Instant loadedAt) {
        boolean isExpired(long ttlMinutes) {
            long safeTtl = Math.max(1L, ttlMinutes);
            Instant expiresAt = loadedAt.plus(Duration.ofMinutes(safeTtl));
            return Instant.now().isAfter(expiresAt);
        }
    }

    @FunctionalInterface
    private interface ValueReader<T> {
        T read();
    }

    @FunctionalInterface
    private interface ValueWriter<T> {
        void write(T value);
    }
}
