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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * Priority model:
 * - Primary: UPLOAD-derived locale/geo rows observed during extraction.
 * - Secondary: APPLE-derived rows synced from choose-country-region page.
 * - Last fallback: static defaults.
 */
@Service
public class AssetRegionLocaleService {

    private static final Logger logger = LoggerFactory.getLogger(AssetRegionLocaleService.class);

    public static final String SOURCE_UPLOAD = "UPLOAD";
    public static final String SOURCE_APPLE = "APPLE";

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
            Map.entry("CA", "en"),
            Map.entry("JP", "ja"),
            Map.entry("KR", "ko"),
            Map.entry("TW", "zh"),
            Map.entry("HK", "zh"),
            Map.entry("MO", "zh"),
            Map.entry("TH", "th"),
            Map.entry("VN", "vi"),
            Map.entry("UA", "uk"),
            Map.entry("UK", "en")
    );

    private static final Map<String, String> GEO_TO_LOCALE_COUNTRY = Map.ofEntries(
            Map.entry("WW", "US"),
            Map.entry("UK", "GB")
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

    @Value("${app.asset-finder.region-sync.enabled:false}")
    private boolean syncEnabled;

    @Value("${app.asset-finder.region-options.upload-only:true}")
    private boolean uploadOnlyOptions;

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
     * Runs periodic sync from Apple region page to keep APPLE rows current.
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
     * Upserts upload-derived geo/locale observations into reference table.
     */
    public void recordUploadObservations(java.util.Collection<RegionLocaleObservation> observations) {
        if (observations == null || observations.isEmpty() || !isTablePresent()) {
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            List<AssetRegionLocaleRef> allRows = repository.findAll();
            Map<String, AssetRegionLocaleRef> existingByKey = new HashMap<>();
            for (AssetRegionLocaleRef row : allRows) {
                if (!SOURCE_UPLOAD.equals(normalizeSourceType(row.getSourceType()))) {
                    continue;
                }
                String key = sourceGeoLocaleKey(SOURCE_UPLOAD, normalizeGeo(row.getGeoCode()), normalizeLocale(row.getLocaleCode()));
                if (key != null) {
                    existingByKey.put(key, row);
                }
            }

            Map<String, RegionLocaleObservation> normalizedObservations = new LinkedHashMap<>();
            for (RegionLocaleObservation observation : observations) {
                if (observation == null) continue;
                String geo = normalizeGeo(observation.geoCode());
                String locale = normalizeLocale(observation.localeCode());

                if (geo == null && locale != null && locale.length() >= 5) {
                    geo = locale.substring(3).toUpperCase(Locale.ROOT);
                }
                if (geo == null || locale == null) {
                    continue;
                }

                String displayName = normalizeDisplayName(observation.displayName());
                if (displayName == null) {
                    displayName = "Uploaded locale " + locale;
                }
                String applePath = normalizeStorefrontPath(observation.applePath());
                if (applePath == null) {
                    applePath = buildStorefrontPathFromLocale(locale);
                }
                String key = sourceGeoLocaleKey(SOURCE_UPLOAD, geo, locale);
                if (key != null) {
                    normalizedObservations.putIfAbsent(key, new RegionLocaleObservation(geo, locale, displayName, applePath));
                }
            }

            if (normalizedObservations.isEmpty()) {
                return;
            }

            List<AssetRegionLocaleRef> changedRows = new ArrayList<>();
            for (RegionLocaleObservation observation : normalizedObservations.values()) {
                String key = sourceGeoLocaleKey(SOURCE_UPLOAD, observation.geoCode(), observation.localeCode());
                AssetRegionLocaleRef row = existingByKey.get(key);
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
                row.setActive(true);
                row.setLastSeenAt(now);
                row.setSeenCount(Optional.ofNullable(row.getSeenCount()).orElse(0L) + 1L);
                // Keep fields fresh when better values are observed.
                if (StringUtils.hasText(observation.displayName())) {
                    row.setDisplayName(observation.displayName());
                }
                if (StringUtils.hasText(observation.applePath())) {
                    row.setApplePath(observation.applePath());
                }
                changedRows.add(row);
            }

            if (!changedRows.isEmpty()) {
                repository.saveAll(changedRows);
                refreshCacheFromDb();
            }
        } catch (Exception e) {
            logger.warn("Failed to upsert upload-derived region/locale observations. Continuing. Reason: {}", e.getMessage());
        }
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

            OffsetDateTime now = OffsetDateTime.now();
            List<AssetRegionLocaleRef> allRows = repository.findAll();
            Map<String, AssetRegionLocaleRef> existingByKey = new HashMap<>();
            for (AssetRegionLocaleRef row : allRows) {
                if (!SOURCE_APPLE.equals(normalizeSourceType(row.getSourceType()))) {
                    continue;
                }
                String key = sourceGeoLocaleKey(SOURCE_APPLE, normalizeGeo(row.getGeoCode()), normalizeLocale(row.getLocaleCode()));
                if (key != null) {
                    existingByKey.put(key, row);
                }
            }

            List<AssetRegionLocaleRef> changedRows = new ArrayList<>();
            for (ParsedRegionEntry entry : parsedEntries) {
                String key = sourceGeoLocaleKey(SOURCE_APPLE, entry.geoCode(), entry.localeCode());
                if (key == null) continue;
                AssetRegionLocaleRef row = existingByKey.get(key);
                if (row == null) {
                    row = new AssetRegionLocaleRef();
                    row.setSourceType(SOURCE_APPLE);
                    row.setGeoCode(entry.geoCode());
                    row.setLocaleCode(entry.localeCode());
                    row.setDisplayName(entry.displayName());
                    row.setApplePath(entry.applePath());
                    row.setActive(true);
                    row.setLastSeenAt(now);
                    row.setSeenCount(1L);
                    changedRows.add(row);
                    continue;
                }

                row.setSourceType(SOURCE_APPLE);
                row.setActive(true);
                row.setLastSeenAt(now);
                row.setSeenCount(Optional.ofNullable(row.getSeenCount()).orElse(0L) + 1L);
                row.setDisplayName(entry.displayName());
                row.setApplePath(entry.applePath());
                changedRows.add(row);
            }

            if (!changedRows.isEmpty()) {
                repository.saveAll(changedRows);
            }
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
            List<AssetRegionLocaleRef> uploadRows =
                    repository.findByActiveTrueAndSourceTypeOrderByGeoCodeAscLocaleCodeAscDisplayNameAsc(SOURCE_UPLOAD);
            List<AssetRegionLocaleRef> appleRows =
                    repository.findByActiveTrueAndSourceTypeOrderByGeoCodeAscLocaleCodeAscDisplayNameAsc(SOURCE_APPLE);

            List<AssetRegionLocaleRef> chosen;
            if (!uploadRows.isEmpty() && uploadOnlyOptions) {
                chosen = uploadRows;
            } else if (!uploadRows.isEmpty()) {
                chosen = new ArrayList<>(uploadRows);
                chosen.addAll(appleRows);
            } else if (!appleRows.isEmpty()) {
                chosen = appleRows;
            } else {
                chosen = repository.findByActiveTrueOrderByGeoCodeAscDisplayNameAscApplePathAsc();
            }

            if (chosen == null || chosen.isEmpty()) {
                return buildFallbackSnapshot();
            }
            RegionOptionsSnapshot snapshot = buildSnapshotFromRows(chosen);
            if (snapshot.geos().isEmpty() || snapshot.geoToLocales().isEmpty()) {
                if (!appleRows.isEmpty() && !Objects.equals(chosen, appleRows)) {
                    RegionOptionsSnapshot appleSnapshot = buildSnapshotFromRows(appleRows);
                    if (!appleSnapshot.geos().isEmpty() && !appleSnapshot.geoToLocales().isEmpty()) {
                        return appleSnapshot;
                    }
                }
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
            String locale = normalizeLocale(row.getLocaleCode());
            if (geo == null || locale == null) {
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
            if (storefrontPath == null) continue;
            String displayName = normalizeDisplayName(matcher.group(2));
            if (displayName == null) continue;
            rawEntries.add(new RawAnchorEntry(storefrontPath, displayName));
            collectExplicitLanguage(storefrontPath, explicitLanguagesByGeo);
        }

        Map<String, ParsedRegionEntry> deduped = new LinkedHashMap<>();
        for (RawAnchorEntry raw : rawEntries) {
            ParsedRegionEntry parsed = mapPathToEntry(raw, explicitLanguagesByGeo);
            if (parsed == null) continue;
            String key = parsed.geoCode() + "|" + parsed.localeCode();
            deduped.putIfAbsent(key, parsed);
        }

        return deduped.values().stream()
                .sorted(Comparator
                        .comparing(ParsedRegionEntry::geoCode)
                        .thenComparing(ParsedRegionEntry::localeCode))
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
            if (geo == null || language == null) return null;
            String locale = normalizeLocale(language + "_" + localeCountryForGeo(geo));
            if (locale == null) return null;
            return new ParsedRegionEntry(geo, locale, raw.displayName(), raw.applePath());
        }

        Matcher langHyphen = COUNTRY_LANGUAGE_HYPHEN_PATTERN.matcher(raw.applePath());
        if (langHyphen.matches()) {
            String geo = normalizeGeo(langHyphen.group(1));
            String language = normalizeLanguage(langHyphen.group(2));
            if (geo == null || language == null) return null;
            String locale = normalizeLocale(language + "_" + localeCountryForGeo(geo));
            if (locale == null) return null;
            return new ParsedRegionEntry(geo, locale, raw.displayName(), raw.applePath());
        }

        Matcher countryOnly = COUNTRY_ONLY_PATTERN.matcher(raw.applePath());
        if (countryOnly.matches()) {
            String geo = normalizeGeo(countryOnly.group(1));
            if (geo == null) return null;
            Set<String> explicitLanguages = explicitLanguagesByGeo.getOrDefault(geo, Set.of());
            String defaultLanguage = resolveDefaultLanguage(geo, explicitLanguages);
            String locale = normalizeLocale(defaultLanguage + "_" + localeCountryForGeo(geo));
            if (locale == null) return null;
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
            addLanguageHint(explicitLanguagesByGeo, normalizeGeo(langPath.group(1)), normalizeLanguage(langPath.group(2)));
            return;
        }

        Matcher langHyphen = COUNTRY_LANGUAGE_HYPHEN_PATTERN.matcher(storefrontPath);
        if (langHyphen.matches()) {
            addLanguageHint(explicitLanguagesByGeo, normalizeGeo(langHyphen.group(1)), normalizeLanguage(langHyphen.group(2)));
        }
    }

    /**
     * Adds one explicit language hint into the in-memory map.
     */
    private void addLanguageHint(Map<String, Set<String>> hints, String geo, String language) {
        if (geo == null || language == null) return;
        hints.computeIfAbsent(geo, ignored -> new LinkedHashSet<>()).add(language);
    }

    /**
     * Derives a default language code for bare geo-only storefront paths.
     */
    private String resolveDefaultLanguage(String geo, Set<String> explicitLanguages) {
        String override = DEFAULT_LANGUAGE_BY_GEO.get(geo);
        if (override != null) {
            return override;
        }
        if (explicitLanguages != null && explicitLanguages.contains("en")) {
            return "en";
        }
        if (explicitLanguages != null && explicitLanguages.contains("ar") && explicitLanguages.size() == 1) {
            // For "/xx-ar/" plus "/xx/", the root URL typically serves English.
            return "en";
        }
        if (explicitLanguages != null && explicitLanguages.size() == 1) {
            return explicitLanguages.iterator().next();
        }
        String isoGuess = geo.toLowerCase(Locale.ROOT);
        if (ISO_LANGUAGE_CODES.contains(isoGuess)
                && LANGUAGE_COUNTRY_COMBINATIONS.contains(isoGuess + "_" + localeCountryForGeo(geo))) {
            return isoGuess;
        }
        return "en";
    }

    /**
     * Normalizes and validates storefront paths that represent region links.
     */
    private String normalizeStorefrontPath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) return null;
        String trimmed = rawPath.trim();
        int queryIndex = trimmed.indexOf('?');
        if (queryIndex >= 0) trimmed = trimmed.substring(0, queryIndex);
        int hashIndex = trimmed.indexOf('#');
        if (hashIndex >= 0) trimmed = trimmed.substring(0, hashIndex);
        if (!trimmed.startsWith("/")) return null;

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }

        if (COUNTRY_ONLY_PATTERN.matcher(normalized).matches()) return normalized;
        if (COUNTRY_LANGUAGE_PATH_PATTERN.matcher(normalized).matches()) return normalized;
        if (COUNTRY_LANGUAGE_HYPHEN_PATTERN.matcher(normalized).matches()) return normalized;
        return null;
    }

    /**
     * Normalizes display labels from HTML anchor text.
     */
    private String normalizeDisplayName(String htmlText) {
        if (!StringUtils.hasText(htmlText)) return null;
        String withoutTags = HTML_TAG_PATTERN.matcher(htmlText).replaceAll(" ");
        String decoded = HtmlUtils.htmlUnescape(withoutTags);
        String normalized = decoded.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Converts locale into storefront path used for upload observations.
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
     * Normalizes a source type label.
     */
    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return SOURCE_APPLE;
        }
        String normalized = sourceType.trim().toUpperCase(Locale.ROOT);
        return (SOURCE_UPLOAD.equals(normalized) || SOURCE_APPLE.equals(normalized)) ? normalized : SOURCE_APPLE;
    }

    /**
     * Builds a key for source + geo + locale matching.
     */
    private String sourceGeoLocaleKey(String sourceType, String geoCode, String localeCode) {
        String source = normalizeSourceType(sourceType);
        String geo = normalizeGeo(geoCode);
        String locale = normalizeLocale(localeCode);
        if (geo == null || locale == null) {
            return null;
        }
        return source + "|" + geo + "|" + locale;
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
     * Maps geo code to country code used in locale format.
     */
    private String localeCountryForGeo(String geo) {
        String normalized = normalizeGeo(geo);
        if (normalized == null) {
            return null;
        }
        return GEO_TO_LOCALE_COUNTRY.getOrDefault(normalized, normalized);
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
        return normalizeLocale(language + "_" + localeCountryForGeo(normalizedGeo));
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

    /**
     * Upload or sync observation of one geo/locale pair.
     */
    public record RegionLocaleObservation(String geoCode, String localeCode, String displayName, String applePath) {}

    private record RawAnchorEntry(String applePath, String displayName) {}

    private record ParsedRegionEntry(String geoCode, String localeCode, String displayName, String applePath) {}

    private record CachedRegionOptions(RegionOptionsSnapshot snapshot, Instant loadedAt) {
        boolean isExpired(long ttlMinutes) {
            long safeTtl = Math.max(1L, ttlMinutes);
            Instant expiresAt = loadedAt.plus(Duration.ofMinutes(safeTtl));
            return Instant.now().isAfter(expiresAt);
        }
    }
}
