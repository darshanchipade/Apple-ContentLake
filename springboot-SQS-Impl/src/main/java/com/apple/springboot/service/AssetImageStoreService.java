package com.apple.springboot.service;

import com.apple.springboot.dto.AssetFinderAssetDetailDto;
import com.apple.springboot.dto.AssetFinderExtractionCountResponse;
import com.apple.springboot.dto.AssetFinderFilterRequest;
import com.apple.springboot.dto.AssetFinderOptionsResponse;
import com.apple.springboot.dto.AssetFinderSearchResponse;
import com.apple.springboot.dto.AssetFinderTileDto;
import com.apple.springboot.model.AssetMetadataCatalog;
import com.apple.springboot.model.AssetMetadataOccurrence;
import com.apple.springboot.model.AssetMetadataOccurrenceAudit;
import com.apple.springboot.model.AssetMetadataUploadSummary;
import com.apple.springboot.model.CleansedDataStore;
import com.apple.springboot.model.RawDataStore;
import com.apple.springboot.model.UploadRequestMetadata;
import com.apple.springboot.repository.AssetMetadataCatalogRepository;
import com.apple.springboot.repository.AssetMetadataOccurrenceAuditRepository;
import com.apple.springboot.repository.AssetMetadataOccurrenceRepository;
import com.apple.springboot.repository.AssetMetadataUploadSummaryRepository;
import com.apple.springboot.repository.CleansedDataStoreRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts image metadata from uploaded JSON and serves Asset Finder queries.
 *
 * Normalized design (Option 3):
 * - asset_metadata_catalog: canonical metadata rows deduplicated by
 * metadata_hash.
 * - asset_metadata_occurrence: latest-only rows per source_uri + slot.
 * - asset_metadata_occurrence_audit: append-only history of occurrence
 * mutations.
 */
@Service
public class AssetImageStoreService {

    private static final Logger logger = LoggerFactory.getLogger(AssetImageStoreService.class);

    private static final Pattern LOCALE_PATTERN = Pattern.compile("([a-z]{2})[-_]([A-Z]{2})");
    private static final Pattern TENANT_PATTERN = Pattern.compile("/content/dam/([^/]+)/");
    private static final Pattern SITE_FROM_ASSET_PATH = Pattern.compile("/assets-www/[a-z]{2}[_-][A-Z]{2}/([^/]+)/");
    private static final Pattern SITE_FROM_CONTENT_PATH = Pattern.compile("/live/[a-z]{2}[_-][A-Z]{2}/([^/]+)/");
    private static final Pattern SITE_FROM_PUBLIC_PATH = Pattern
            .compile("/[a-z]{2}[_-][A-Z]{2}/([^/]+)(?:/([^/?#]+))?");
    private static final Set<String> URI_KEYS = Set.of("uri", "uri1x", "uri2x", "_uri_path", "_uri1x_path",
            "_uri2x_path", "src", "url", "_path", "file", "link", "assetUrl");
    private static final List<String> ENVIRONMENTS = List.of("stage", "prod", "qa");
    private static final List<String> DEFAULT_PROJECTS = List.of("rome");
    private static final List<String> DEFAULT_SITES = List.of("ipad", "mac", "airpods");
    private static final List<String> DEFAULT_PAGE_CONTEXTS = List.of("overview", "specs", "compare");
    private static final Set<String> PAGE_CONTEXT_TOKENS = Set.of("overview", "specs", "compare");
    private static final List<String> GEO_GROUP_ORDER = List.of(
            "Europe", "IN", "JP", "KR", "SEA", "WW", "CEMEA", "ANZ", "ALAC-CA");
    private static final Set<String> EUROPE_COUNTRIES = Set.of(
            "AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB",
            "GR", "HR", "HU", "IE", "IS", "IT", "LI", "LT", "LU", "LV", "MT", "NL", "NO",
            "PL", "PT", "RO", "SE", "SI", "SK");
    private static final Set<String> SEA_COUNTRIES = Set.of(
            "SG", "MY", "TH", "VN", "ID", "PH", "BN", "KH", "LA", "MM");
    private static final Set<String> ANZ_COUNTRIES = Set.of("AU", "NZ");
    private static final Set<String> ALAC_CA_COUNTRIES = Set.of(
            "CA", "MX", "AR", "BO", "BR", "CL", "CO", "CR", "DO", "EC",
            "SV", "GT", "HN", "NI", "PA", "PY", "PE", "UY", "VE");
    private static final Set<String> CEMEA_COUNTRIES = Set.of(
            "AE", "SA", "QA", "KW", "OM", "BH", "JO", "IL", "EG", "MA", "TN", "DZ",
            "ZA", "NG", "KE", "UG", "CM", "CI", "BW", "MZ", "MU", "SN", "CF", "GW",
            "GN", "GQ", "ML", "NE", "AM", "AZ", "BY", "GE", "KZ", "KG", "MD", "ME",
            "MK", "RU", "TJ", "TM", "UA", "UZ", "TR");

    private final AssetMetadataCatalogRepository catalogRepository;
    private final AssetMetadataOccurrenceRepository occurrenceRepository;
    private final AssetMetadataOccurrenceAuditRepository occurrenceAuditRepository;
    private final AssetMetadataUploadSummaryRepository uploadSummaryRepository;
    private final CleansedDataStoreRepository cleansedDataStoreRepository;
    private final AssetRegionLocaleService assetRegionLocaleService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.asset-finder.enabled:true}")
    private boolean assetFinderEnabled;

    @Value("${app.asset-finder.default-tenant:applecom-cms}")
    private String defaultTenant;

    @Value("${app.asset-finder.default-environment:stage}")
    private String defaultEnvironment;

    @Value("${app.asset-finder.default-project:rome}")
    private String defaultProject;

    @Value("${app.asset-finder.default-site:ipad}")
    private String defaultSite;

    @Value("${app.asset-finder.default-geo:WW}")
    private String defaultGeo;

    @Value("${app.asset-finder.default-locale:en_US}")
    private String defaultLocale;

    // Cached after first check. If schema changes while running, restart app.
    private volatile Boolean tablesPresent;

    /**
     * Creates a service for image extraction and Asset Finder access.
     */
    public AssetImageStoreService(AssetMetadataCatalogRepository catalogRepository,
            AssetMetadataOccurrenceRepository occurrenceRepository,
            AssetMetadataOccurrenceAuditRepository occurrenceAuditRepository,
            AssetMetadataUploadSummaryRepository uploadSummaryRepository,
            CleansedDataStoreRepository cleansedDataStoreRepository,
            AssetRegionLocaleService assetRegionLocaleService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.catalogRepository = catalogRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.occurrenceAuditRepository = occurrenceAuditRepository;
        this.uploadSummaryRepository = uploadSummaryRepository;
        this.cleansedDataStoreRepository = cleansedDataStoreRepository;
        this.assetRegionLocaleService = assetRegionLocaleService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Best-effort extraction and persistence of image metadata for a raw payload.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void safeExtractAndStore(JsonNode rootNode, RawDataStore rawDataStore) {
        if (!assetFinderEnabled) {
            return;
        }
        if (rootNode == null || rawDataStore == null || rawDataStore.getId() == null) {
            return;
        }

        try {
            UploadRequestMetadata requestMetadata = parseRequestMetadata(rawDataStore.getSourceRequestMetadata());
            List<ExtractedAssetCandidate> extracted = extractAssets(rootNode, rawDataStore, requestMetadata);
            List<ExtractedAssetCandidate> deduplicatedBySlot = deduplicateBySlot(extracted);

            // Track observed geo/locale pairs from this upload independently of asset
            // occurrence writes.
            assetRegionLocaleService.recordUploadObservations(
                    buildUploadRegionObservations(deduplicatedBySlot, requestMetadata, rawDataStore.getId()));

            if (!areTablesPresent()) {
                return;
            }

            String sourceUri = rawDataStore.getSourceUri();
            List<AssetMetadataOccurrence> existingForSource = sourceUri != null
                    ? occurrenceRepository.findBySourceUri(sourceUri)
                    : List.of();
            Map<String, AssetMetadataOccurrence> existingBySlot = new LinkedHashMap<>();
            List<AssetMetadataOccurrence> duplicateLegacyRows = new ArrayList<>();
            for (AssetMetadataOccurrence row : existingForSource) {
                if (row != null && row.getAssetSlotKey() != null) {
                    AssetMetadataOccurrence prior = existingBySlot.putIfAbsent(row.getAssetSlotKey(), row);
                    if (prior != null) {
                        duplicateLegacyRows.add(row);
                    }
                }
            }

            List<AssetMetadataOccurrence> rowsToSave = new ArrayList<>();
            List<AssetMetadataOccurrenceAudit> auditRows = new ArrayList<>();
            Set<String> seenSlots = new LinkedHashSet<>();
            int insertCount = 0;
            int updateCount = 0;
            int unchangedCount = 0;
            int deactivateCount = 0;

            for (ExtractedAssetCandidate candidate : deduplicatedBySlot) {
                AssetMetadataCatalog catalog = upsertCatalog(candidate);
                AssetMetadataOccurrence current = existingBySlot.get(candidate.assetSlotKey());
                if (current == null) {
                    AssetMetadataOccurrence created = new AssetMetadataOccurrence();
                    created.setCatalogId(catalog.getId());
                    created.setRawDataId(rawDataStore.getId());
                    created.setSourceUri(rawDataStore.getSourceUri());
                    created.setSourceVersion(rawDataStore.getVersion());
                    created.setFirstSeenVersion(rawDataStore.getVersion());
                    created.setLastSeenVersion(rawDataStore.getVersion());
                    created.setAssetSlotKey(candidate.assetSlotKey());
                    created.setAssetNodePath(candidate.assetNodePath());
                    created.setSectionPath(candidate.sectionPath());
                    created.setSectionUri(candidate.sectionUri());
                    created.setTenant(candidate.tenant());
                    created.setEnvironment(candidate.environment());
                    created.setProject(candidate.project());
                    created.setSite(candidate.site());
                    created.setGeo(candidate.geo());
                    created.setLocale(candidate.locale());
                    created.setRequestMetadataJson(candidate.requestMetadataJson());
                    created.setActive(true);
                    rowsToSave.add(created);
                    auditRows.add(buildAuditRow(rawDataStore, candidate.assetSlotKey(), "INSERT", null, created));
                    insertCount++;
                } else {
                    AssetMetadataOccurrence before = snapshotOccurrence(current);
                    current.setCatalogId(catalog.getId());
                    current.setRawDataId(rawDataStore.getId());
                    current.setSourceUri(rawDataStore.getSourceUri());
                    current.setSourceVersion(rawDataStore.getVersion());
                    current.setLastSeenVersion(rawDataStore.getVersion());
                    if (current.getFirstSeenVersion() == null) {
                        current.setFirstSeenVersion(rawDataStore.getVersion());
                    }
                    current.setAssetNodePath(candidate.assetNodePath());
                    current.setSectionPath(candidate.sectionPath());
                    current.setSectionUri(candidate.sectionUri());
                    current.setTenant(candidate.tenant());
                    current.setEnvironment(candidate.environment());
                    current.setProject(candidate.project());
                    current.setSite(candidate.site());
                    current.setGeo(candidate.geo());
                    current.setLocale(candidate.locale());
                    current.setRequestMetadataJson(candidate.requestMetadataJson());
                    current.setActive(true);
                    rowsToSave.add(current);

                    if (isOccurrenceChanged(before, current)) {
                        auditRows.add(buildAuditRow(rawDataStore, candidate.assetSlotKey(), "UPDATE", before, current));
                        updateCount++;
                    } else {
                        unchangedCount++;
                    }
                }
                seenSlots.add(candidate.assetSlotKey());
            }

            for (AssetMetadataOccurrence existing : existingForSource) {
                if (existing == null || existing.getAssetSlotKey() == null) {
                    continue;
                }
                if (seenSlots.contains(existing.getAssetSlotKey())) {
                    continue;
                }
                if (!Boolean.TRUE.equals(existing.getActive())) {
                    continue;
                }
                AssetMetadataOccurrence before = snapshotOccurrence(existing);
                existing.setActive(false);
                existing.setSourceVersion(rawDataStore.getVersion());
                existing.setLastSeenVersion(rawDataStore.getVersion());
                rowsToSave.add(existing);
                auditRows.add(buildAuditRow(rawDataStore, existing.getAssetSlotKey(), "DELETE", before, existing));
                deactivateCount++;
            }

            for (AssetMetadataOccurrence duplicate : duplicateLegacyRows) {
                if (duplicate == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(duplicate.getActive())) {
                    AssetMetadataOccurrence before = snapshotOccurrence(duplicate);
                    duplicate.setActive(false);
                    duplicate.setSourceVersion(rawDataStore.getVersion());
                    duplicate.setLastSeenVersion(rawDataStore.getVersion());
                    rowsToSave.add(duplicate);
                    auditRows
                            .add(buildAuditRow(rawDataStore, duplicate.getAssetSlotKey(), "DELETE", before, duplicate));
                    deactivateCount++;
                }
            }

            if (!rowsToSave.isEmpty()) {
                occurrenceRepository.saveAll(rowsToSave);
                occurrenceRepository.flush();
            }

            if (!auditRows.isEmpty()) {
                try {
                    occurrenceAuditRepository.saveAll(auditRows);
                } catch (Exception auditError) {
                    logger.warn("Asset occurrence audit persistence failed for rawDataId {}. Continuing. Reason: {}",
                            rawDataStore.getId(), auditError.getMessage());
                }
            }

            try {
                upsertUploadSummary(rawDataStore, deduplicatedBySlot.size());
            } catch (Exception summaryError) {
                logger.warn("Asset upload summary persistence failed for rawDataId {}. Continuing. Reason: {}",
                        rawDataStore.getId(), summaryError.getMessage());
            }

            logger.info(
                    "Asset metadata extraction complete for rawDataId {}. Current rows touched: {} (inserted={}, updated={}, unchanged={}, deactivated={}; pre-dedupe={}).",
                    rawDataStore.getId(), rowsToSave.size(), insertCount, updateCount, unchangedCount, deactivateCount,
                    extracted.size());
        } catch (Exception e) {
            logger.warn("Asset metadata extraction failed for rawDataId {}. Continuing ingestion pipeline. Reason: {}",
                    rawDataStore.getId(), e.getMessage());
        }
    }

    /**
     * Returns Asset Finder filter options (current defaults plus observed data).
     */
    @Transactional(readOnly = true)
    public AssetFinderOptionsResponse getOptions() {
        AssetFinderOptionsResponse response = new AssetFinderOptionsResponse();

        Set<String> tenants = new LinkedHashSet<>();
        tenants.add(normalizeText(defaultTenant));

        Set<String> projects = new LinkedHashSet<>(DEFAULT_PROJECTS);
        String configuredProject = normalizeText(defaultProject);
        if (configuredProject != null) {
            projects.add(configuredProject.toLowerCase(Locale.ROOT));
        }

        Set<String> sites = new LinkedHashSet<>(DEFAULT_SITES);
        Set<String> pageContexts = new LinkedHashSet<>(DEFAULT_PAGE_CONTEXTS);
        Map<String, Set<String>> siteToPageContexts = new LinkedHashMap<>();
        if (areTablesPresent()) {
            try {
                sites.addAll(occurrenceRepository.findDistinctSites().stream()
                        .filter(Objects::nonNull)
                        .map(v -> v.toLowerCase(Locale.ROOT))
                        .toList());
            } catch (Exception e) {
                logger.warn("Unable to load distinct asset sites; using defaults. Reason: {}", e.getMessage());
            }
            try {
                for (AssetMetadataOccurrenceRepository.SitePathProjection row : occurrenceRepository.findDistinctSitePathTuples()) {
                    if (row == null) {
                        continue;
                    }
                    SitePageContext siteAndPage = deriveSiteAndPageContext(
                            row.getSite(),
                            row.getSectionPath(),
                            row.getSectionUri(),
                            row.getSourceUri(),
                            null,
                            null);
                    String siteKey = normalizeLower(siteAndPage.site());
                    if (siteKey == null) {
                        continue;
                    }
                    sites.add(siteKey);
                    String pageKey = normalizeLower(siteAndPage.pageContext());
                    if (pageKey == null) {
                        continue;
                    }
                    pageContexts.add(pageKey);
                    siteToPageContexts.computeIfAbsent(siteKey, ignored -> new LinkedHashSet<>()).add(pageKey);
                }
            } catch (Exception e) {
                logger.warn("Unable to derive page-context options from occurrences; using defaults. Reason: {}", e.getMessage());
            }
        }
        String configuredSite = normalizeText(defaultSite);
        if (configuredSite != null) {
            sites.add(configuredSite.toLowerCase(Locale.ROOT));
        }

        response.setTenants(tenants.stream().filter(Objects::nonNull).toList());
        response.setEnvironments(ENVIRONMENTS);
        response.setProjects(new ArrayList<>(projects));
        response.setSites(new ArrayList<>(sites));
        response.setPageContexts(new ArrayList<>(pageContexts));
        Map<String, List<String>> normalizedSitePageContexts = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : siteToPageContexts.entrySet()) {
            List<String> values = new ArrayList<>(entry.getValue());
            Collections.sort(values);
            normalizedSitePageContexts.put(entry.getKey(), values);
        }
        response.setSiteToPageContexts(normalizedSitePageContexts);
        AssetRegionLocaleService.RegionOptionsSnapshot regionOptions = resolveRegionOptionsFromOccurrences();
        response.setGeos(regionOptions.geos());
        response.setGeoToLocales(regionOptions.geoToLocales());
        return response;
    }

    /**
     * Builds geo/locale option maps from actual occurrence data first, then
     * fallback reference table.
     */
    private AssetRegionLocaleService.RegionOptionsSnapshot resolveRegionOptionsFromOccurrences() {
        if (!areTablesPresent()) {
            return toGeoGroupedSnapshot(assetRegionLocaleService.getRegionOptionsSnapshot());
        }

        try {
            List<AssetMetadataOccurrenceRepository.GeoLocaleProjection> pairs = occurrenceRepository
                    .findDistinctGeoLocalePairs();
            if (pairs == null || pairs.isEmpty()) {
                return toGeoGroupedSnapshot(assetRegionLocaleService.getRegionOptionsSnapshot());
            }

            Map<String, List<String>> rawGeoToLocales = new LinkedHashMap<>();
            for (AssetMetadataOccurrenceRepository.GeoLocaleProjection pair : pairs) {
                if (pair == null) {
                    continue;
                }
                String locale = normalizeLocale(pair.getLocale());
                if (locale == null) {
                    continue;
                }
                String rawGeo = normalizeGeo(pair.getGeo());
                if (rawGeo == null && locale.length() >= 5) {
                    rawGeo = locale.substring(3).toUpperCase(Locale.ROOT);
                }
                if (rawGeo == null) {
                    continue;
                }
                rawGeoToLocales.computeIfAbsent(rawGeo, ignored -> new ArrayList<>()).add(locale);
            }

            AssetRegionLocaleService.RegionOptionsSnapshot grouped = buildGeoGroupedSnapshot(rawGeoToLocales);
            if (grouped.geos().isEmpty() || grouped.geoToLocales().isEmpty()) {
                return toGeoGroupedSnapshot(assetRegionLocaleService.getRegionOptionsSnapshot());
            }
            return grouped;
        } catch (Exception e) {
            logger.warn(
                    "Unable to derive region options from occurrence rows. Falling back to locale reference. Reason: {}",
                    e.getMessage());
            return toGeoGroupedSnapshot(assetRegionLocaleService.getRegionOptionsSnapshot());
        }
    }

    /**
     * Searches stored image metadata using Asset Finder filters.
     */
    @Transactional(readOnly = true)
    public AssetFinderSearchResponse search(AssetFinderFilterRequest request) {
        AssetFinderFilterRequest safeRequest = request != null ? request : new AssetFinderFilterRequest();
        int page = Math.max(0, Optional.ofNullable(safeRequest.getPage()).orElse(0));
        int size = Math.max(1, Math.min(1000, Optional.ofNullable(safeRequest.getSize()).orElse(1000)));

        if (!areTablesPresent()) {
            AssetFinderSearchResponse empty = new AssetFinderSearchResponse();
            empty.setCount(0L);
            empty.setPage(page);
            empty.setSize(size);
            empty.setTotalPages(0);
            empty.setItems(List.of());
            return empty;
        }

        String tenant = normalizeText(safeRequest.getTenant());
        String environment = normalizeText(safeRequest.getEnvironment());
        String project = normalizeText(safeRequest.getProject());
        String site = normalizeText(safeRequest.getSite());
        String pageContext = normalizePageContext(safeRequest.getPageContext());
        String geo = normalizeText(safeRequest.getGeo());
        String locale = normalizeLocale(safeRequest.getLocale());

        if (locale == null && geo != null) {
            locale = mapGeoToLocale(geo).orElse(null);
        }
        if (locale != null) {
            // Locale is the strongest selector and avoids mismatches with grouped geo
            // labels.
            geo = null;
        } else if (geo != null && isConfiguredGeoGroup(geo)) {
            // Grouped geos (Europe/SEA/...) are option labels, not stored raw occurrence
            // values.
            geo = null;
        } else if (geo != null) {
            geo = normalizeGeo(geo);
        }

        Pageable pageable = PageRequest.of(page, size);
        // Site metadata can be noisy/inconsistent in some legacy rows.
        // When site is selected, fetch by other filters first and enforce site by path guard below.
        String querySite = (site != null && !site.isBlank()) ? null : site;
        Page<AssetMetadataOccurrence> result = occurrenceRepository.search(
                tenant, environment, project, querySite, geo, locale, pageable);

        List<AssetMetadataOccurrence> occurrences = result.getContent();
        Map<UUID, AssetMetadataCatalog> catalogs = loadCatalogMap(occurrences);

        // Additional site guard:
        // some legacy rows may have coarse/inaccurate "site" metadata (e.g. defaulted during extraction).
        // When user explicitly selects a site, enforce path-level alignment against section/source/image paths.
        if (site != null && !site.isBlank()) {
            String requestedSite = site.toLowerCase(Locale.ROOT);
            occurrences = occurrences.stream()
                    .filter(occurrence -> {
                        AssetMetadataCatalog catalog = catalogs.get(occurrence.getCatalogId());
                        return matchesSitePath(requestedSite, occurrence, catalog);
                    })
                    .toList();
        }
        if (pageContext != null && !pageContext.isBlank()) {
            String requestedPageContext = pageContext.toLowerCase(Locale.ROOT);
            occurrences = occurrences.stream()
                    .filter(occurrence -> {
                        AssetMetadataCatalog catalog = catalogs.get(occurrence.getCatalogId());
                        return matchesPageContext(requestedPageContext, occurrence, catalog);
                    })
                    .toList();
        }

        List<AssetFinderTileDto> tiles = occurrences.stream()
                .map(occurrence -> toTileDto(occurrence, catalogs.get(occurrence.getCatalogId())))
                .toList();
        long groupedCount = computeGroupedAssetCount(occurrences, catalogs);

        AssetFinderSearchResponse response = new AssetFinderSearchResponse();
        response.setCount(groupedCount);
        response.setPage(result.getNumber());
        response.setSize(result.getSize());
        int groupedTotalPages = result.getSize() > 0
                ? (int) Math.ceil((double) groupedCount / result.getSize())
                : 0;
        response.setTotalPages(groupedTotalPages);
        response.setItems(tiles);
        return response;
    }

    private long computeGroupedAssetCount(List<AssetMetadataOccurrence> occurrences,
            Map<UUID, AssetMetadataCatalog> catalogs) {
        if (occurrences == null || occurrences.isEmpty()) {
            return 0L;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (AssetMetadataOccurrence occurrence : occurrences) {
            AssetMetadataCatalog catalog = occurrence != null ? catalogs.get(occurrence.getCatalogId()) : null;
            keys.add(buildAssetGroupKey(occurrence, catalog));
        }
        return keys.size();
    }

    private String buildAssetGroupKey(AssetMetadataOccurrence occurrence, AssetMetadataCatalog catalog) {
        String sectionAnchor = normalizeLower(firstNonBlank(
                occurrence != null ? occurrence.getSectionPath() : null,
                occurrence != null ? occurrence.getSectionUri() : null));
        String imageIdentity = firstNonBlank(
                imageIdentityFromPath(catalog != null ? catalog.getInteractivePath() : null),
                imageIdentityFromPath(catalog != null ? catalog.getPreviewUri() : null));
        if (sectionAnchor != null && imageIdentity != null) {
            return sectionAnchor + "::" + imageIdentity;
        }
        String nodeAnchor = normalizeLower(occurrence != null ? occurrence.getAssetNodePath() : null);
        if (nodeAnchor != null) {
            return nodeAnchor;
        }
        if (imageIdentity != null) {
            return imageIdentity;
        }
        String assetAnchor = normalizeLower(catalog != null ? catalog.getAssetKey() : null);
        if (sectionAnchor != null && assetAnchor != null) {
            return sectionAnchor + "::" + assetAnchor;
        }
        return occurrence != null && occurrence.getId() != null ? occurrence.getId().toString() : UUID.randomUUID().toString();
    }

    private String imageIdentityFromPath(String path) {
        String normalized = normalizeText(path);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        lower = lower.replaceFirst("^https?://[^/]+", "");
        int queryIdx = lower.indexOf('?');
        if (queryIdx >= 0) {
            lower = lower.substring(0, queryIdx);
        }
        lower = lower.replaceAll("/(small|medium|large)/", "/");
        lower = lower.replaceAll("/{2,}", "/");
        int lastSlash = lower.lastIndexOf('/');
        if (lastSlash < 0) {
            return lower;
        }
        String dir = lower.substring(0, lastSlash + 1);
        String file = lower.substring(lastSlash + 1);
        int dot = file.lastIndexOf('.');
        String ext = dot >= 0 ? file.substring(dot) : "";
        String stem = dot >= 0 ? file.substring(0, dot) : file;
        stem = stem.replaceAll("_2x$", "");
        stem = stem.replaceAll("_[a-f0-9]{7,}$", "");
        return dir + stem + ext;
    }

    private String normalizeLower(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private boolean matchesSitePath(String requestedSite,
            AssetMetadataOccurrence occurrence,
            AssetMetadataCatalog catalog) {
        if (requestedSite == null || requestedSite.isBlank()) {
            return true;
        }
        String marker = "/" + requestedSite + "/";
        return containsSiteMarker(occurrence != null ? occurrence.getSectionPath() : null, marker)
                || containsSiteMarker(occurrence != null ? occurrence.getSectionUri() : null, marker)
                || containsSiteMarker(occurrence != null ? occurrence.getSourceUri() : null, marker)
                || containsSiteMarker(catalog != null ? catalog.getInteractivePath() : null, marker)
                || containsSiteMarker(catalog != null ? catalog.getPreviewUri() : null, marker);
    }

    private boolean matchesPageContext(String requestedPageContext,
            AssetMetadataOccurrence occurrence,
            AssetMetadataCatalog catalog) {
        if (requestedPageContext == null || requestedPageContext.isBlank()) {
            return true;
        }
        String resolved = deriveSiteAndPageContext(
                occurrence != null ? occurrence.getSite() : null,
                occurrence != null ? occurrence.getSectionPath() : null,
                occurrence != null ? occurrence.getSectionUri() : null,
                occurrence != null ? occurrence.getSourceUri() : null,
                catalog != null ? catalog.getInteractivePath() : null,
                catalog != null ? catalog.getPreviewUri() : null).pageContext();
        return requestedPageContext.equalsIgnoreCase(resolved);
    }

    private boolean containsSiteMarker(String value, String marker) {
        if (value == null || marker == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(marker);
    }

    private SitePageContext deriveSiteAndPageContext(String explicitSite,
            String sectionPath,
            String sectionUri,
            String sourceUri,
            String interactivePath,
            String previewUri) {
        String canonicalSite = firstNonBlank(
                normalizeSiteBucket(explicitSite),
                inferSite(sectionPath),
                inferSite(sectionUri),
                inferSite(sourceUri),
                inferSite(interactivePath),
                inferSite(previewUri));

        String pageContext = firstNonBlank(
                inferPageContext(sectionPath),
                inferPageContext(sectionUri),
                inferPageContext(sourceUri),
                inferPageContext(interactivePath),
                inferPageContext(previewUri),
                "overview");
        return new SitePageContext(canonicalSite, pageContext);
    }

    /**
     * Loads detailed metadata for a single asset tile.
     */
    @Transactional(readOnly = true)
    public Optional<AssetFinderAssetDetailDto> getDetails(UUID id) {
        return occurrenceRepository.findById(id)
                .flatMap(occurrence -> catalogRepository.findById(occurrence.getCatalogId()).map(catalog -> {
                    SitePageContext siteAndPage = deriveSiteAndPageContext(
                            occurrence.getSite(),
                            occurrence.getSectionPath(),
                            occurrence.getSectionUri(),
                            occurrence.getSourceUri(),
                            catalog.getInteractivePath(),
                            catalog.getPreviewUri());
                    AssetFinderAssetDetailDto detail = new AssetFinderAssetDetailDto();
                    detail.setId(occurrence.getId());
                    detail.setTenant(occurrence.getTenant());
                    detail.setEnvironment(occurrence.getEnvironment());
                    detail.setProject(occurrence.getProject());
                    detail.setSite(siteAndPage.site());
                    detail.setPageContext(siteAndPage.pageContext());
                    detail.setGeo(occurrence.getGeo());
                    detail.setLocale(occurrence.getLocale());
                    detail.setAssetKey(catalog.getAssetKey());
                    detail.setAssetModel(catalog.getAssetModel());
                    detail.setSectionPath(occurrence.getSectionPath());
                    detail.setSectionUri(occurrence.getSectionUri());
                    detail.setAssetNodePath(occurrence.getAssetNodePath());
                    detail.setInteractivePath(toApplePublicUrl(catalog.getInteractivePath(), occurrence.getSectionUri()));
                    detail.setPreviewUri(catalog.getPreviewUri());
                    detail.setAltText(catalog.getAltText());
                    detail.setAccessibilityText(catalog.getAccessibilityText());
                    detail.setViewports(parseJsonObject(catalog.getViewportsJson()));
                    detail.setMetadata(parseJsonObject(catalog.getAssetMetadataJson()));
                    return detail;
                }));
    }

    /**
     * Returns extracted asset row counts for a cleansed upload record.
     */
    @Transactional(readOnly = true)
    public Optional<AssetFinderExtractionCountResponse> getExtractionCountByCleansedId(UUID cleansedDataStoreId) {
        return cleansedDataStoreRepository.findById(cleansedDataStoreId)
                .map(this::buildExtractionCountResponse);
    }

    /**
     * Builds a count response from a cleansed record.
     */
    private AssetFinderExtractionCountResponse buildExtractionCountResponse(CleansedDataStore cleansed) {
        AssetFinderExtractionCountResponse response = new AssetFinderExtractionCountResponse();
        response.setCleansedDataStoreId(cleansed.getId());
        response.setRawDataId(cleansed.getRawDataId());
        response.setSourceUri(cleansed.getSourceUri());
        response.setSourceVersion(cleansed.getVersion());
        response.setAssetFinderEnabled(assetFinderEnabled);
        boolean tableAvailable = areTablesPresent();
        response.setTablePresent(tableAvailable);

        long count = 0L;
        if (assetFinderEnabled && tableAvailable && cleansed.getRawDataId() != null) {
            try {
                Long summaryCount = null;
                try {
                    summaryCount = uploadSummaryRepository.findById(cleansed.getRawDataId())
                            .map(AssetMetadataUploadSummary::getAssetCount)
                            .orElse(null);
                } catch (Exception ignored) {
                    summaryCount = null;
                }
                count = summaryCount != null
                        ? summaryCount
                        : occurrenceRepository.countByRawDataId(cleansed.getRawDataId());
            } catch (Exception e) {
                logger.warn("Unable to count extracted asset rows for rawDataId {}: {}",
                        cleansed.getRawDataId(), e.getMessage());
            }
        }
        response.setAssetCount(count);
        return response;
    }

    /**
     * Stores per-upload extracted asset counts for stable activity-page history.
     */
    private void upsertUploadSummary(RawDataStore rawDataStore, int extractedCount) {
        if (rawDataStore == null || rawDataStore.getId() == null) {
            return;
        }
        AssetMetadataUploadSummary summary = uploadSummaryRepository.findById(rawDataStore.getId())
                .orElseGet(AssetMetadataUploadSummary::new);
        summary.setRawDataId(rawDataStore.getId());
        summary.setSourceUri(firstNonBlank(rawDataStore.getSourceUri(), "unknown-source"));
        summary.setSourceVersion(rawDataStore.getVersion());
        summary.setAssetCount((long) Math.max(0, extractedCount));
        uploadSummaryRepository.save(summary);
    }

    /**
     * Creates an immutable copy used for change comparison and audit snapshots.
     */
    private AssetMetadataOccurrence snapshotOccurrence(AssetMetadataOccurrence source) {
        if (source == null) {
            return null;
        }
        AssetMetadataOccurrence copy = new AssetMetadataOccurrence();
        copy.setId(source.getId());
        copy.setCatalogId(source.getCatalogId());
        copy.setRawDataId(source.getRawDataId());
        copy.setSourceUri(source.getSourceUri());
        copy.setSourceVersion(source.getSourceVersion());
        copy.setFirstSeenVersion(source.getFirstSeenVersion());
        copy.setLastSeenVersion(source.getLastSeenVersion());
        copy.setAssetSlotKey(source.getAssetSlotKey());
        copy.setAssetNodePath(source.getAssetNodePath());
        copy.setSectionPath(source.getSectionPath());
        copy.setSectionUri(source.getSectionUri());
        copy.setTenant(source.getTenant());
        copy.setEnvironment(source.getEnvironment());
        copy.setProject(source.getProject());
        copy.setSite(source.getSite());
        copy.setGeo(source.getGeo());
        copy.setLocale(source.getLocale());
        copy.setRequestMetadataJson(source.getRequestMetadataJson());
        copy.setActive(source.getActive());
        return copy;
    }

    /**
     * Returns true when latest occurrence state changed in a meaningful way.
     */
    private boolean isOccurrenceChanged(AssetMetadataOccurrence before, AssetMetadataOccurrence after) {
        if (before == null || after == null) {
            return true;
        }
        return !Objects.equals(before.getCatalogId(), after.getCatalogId())
                || !Objects.equals(before.getAssetNodePath(), after.getAssetNodePath())
                || !Objects.equals(before.getSectionPath(), after.getSectionPath())
                || !Objects.equals(before.getSectionUri(), after.getSectionUri())
                || !Objects.equals(before.getTenant(), after.getTenant())
                || !Objects.equals(before.getEnvironment(), after.getEnvironment())
                || !Objects.equals(before.getProject(), after.getProject())
                || !Objects.equals(before.getSite(), after.getSite())
                || !Objects.equals(before.getGeo(), after.getGeo())
                || !Objects.equals(before.getLocale(), after.getLocale())
                || !Objects.equals(before.getRequestMetadataJson(), after.getRequestMetadataJson())
                || !Objects.equals(before.getActive(), after.getActive());
    }

    /**
     * Builds an audit event row for occurrence inserts/updates/deletes.
     */
    private AssetMetadataOccurrenceAudit buildAuditRow(RawDataStore rawDataStore,
            String slotKey,
            String eventType,
            AssetMetadataOccurrence oldRow,
            AssetMetadataOccurrence newRow) {
        AssetMetadataOccurrenceAudit audit = new AssetMetadataOccurrenceAudit();
        audit.setRawDataId(rawDataStore.getId());
        audit.setSourceUri(firstNonBlank(rawDataStore.getSourceUri(), "unknown-source"));
        audit.setSourceVersion(rawDataStore.getVersion());
        audit.setAssetSlotKey(slotKey);
        audit.setEventType(eventType);
        audit.setOldCatalogId(oldRow != null ? oldRow.getCatalogId() : null);
        audit.setNewCatalogId(newRow != null ? newRow.getCatalogId() : null);
        audit.setOldContextJson(oldRow != null ? serializeJson(occurrenceContextMap(oldRow)) : null);
        audit.setNewContextJson(newRow != null ? serializeJson(occurrenceContextMap(newRow)) : null);
        return audit;
    }

    /**
     * Produces a compact context map for audit diff payloads.
     */
    private Map<String, Object> occurrenceContextMap(AssetMetadataOccurrence row) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("rawDataId", row.getRawDataId());
        context.put("sourceUri", row.getSourceUri());
        context.put("sourceVersion", row.getSourceVersion());
        context.put("firstSeenVersion", row.getFirstSeenVersion());
        context.put("lastSeenVersion", row.getLastSeenVersion());
        context.put("assetNodePath", row.getAssetNodePath());
        context.put("sectionPath", row.getSectionPath());
        context.put("sectionUri", row.getSectionUri());
        context.put("tenant", row.getTenant());
        context.put("environment", row.getEnvironment());
        context.put("project", row.getProject());
        context.put("site", row.getSite());
        context.put("geo", row.getGeo());
        context.put("locale", row.getLocale());
        context.put("active", row.getActive());
        return context;
    }

    /**
     * Extracts all image-like nodes from a JSON payload.
     */
    private List<ExtractedAssetCandidate> extractAssets(JsonNode rootNode,
            RawDataStore rawDataStore,
            UploadRequestMetadata requestMetadata) {
        List<ExtractedAssetCandidate> results = new ArrayList<>();
        collectAssets(rootNode, "#", new SectionContext(null, null), rawDataStore, requestMetadata, results, rootNode);
        return results;
    }

    /**
     * Recursively traverses JSON nodes and collects image assets.
     */
    private void collectAssets(JsonNode node,
            String jsonPath,
            SectionContext currentSection,
            RawDataStore rawDataStore,
            UploadRequestMetadata requestMetadata,
            List<ExtractedAssetCandidate> output,
            JsonNode rootNode) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            SectionContext sectionContext = resolveSectionContext(node, currentSection);

            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                String childJsonPath = jsonPath + "/" + escapeJsonPathSegment(key);

                if (value.isObject()) {
                    if ((isImageLikeKey(key) || isAssetModel(value)) && isLikelyAssetNode(value)) {
                        ExtractedAssetCandidate candidate = buildCandidate(
                                key, value, childJsonPath, sectionContext, rawDataStore, requestMetadata, rootNode);
                        if (candidate != null) {
                            output.add(candidate);
                        }
                    }
                    collectAssets(value, childJsonPath, sectionContext, rawDataStore, requestMetadata, output, rootNode);
                } else if (value.isArray()) {
                    collectAssets(value, childJsonPath, sectionContext, rawDataStore, requestMetadata, output, rootNode);
                } else if (value.isTextual() && isImageLikeKey(key)) {
                    String path = value.asText();
                    if (path.startsWith("/content/dam/") || path.startsWith("http") || path.startsWith("/")) {
                         ExtractedAssetCandidate candidate = buildTextualCandidate(
                                key, path, childJsonPath, sectionContext, requestMetadata, rawDataStore);
                         if (candidate != null) {
                             output.add(candidate);
                         }
                    }
                }
            });
            return;
        }

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectAssets(node.get(i), jsonPath + "/" + i, currentSection, rawDataStore, requestMetadata, output, rootNode);
            }
        }
    }

    /**
     * Builds an extracted candidate from a discovered asset node.
     * When the assetNode's `_path` is a JSON Pointer (starts with '#'), it is resolved
     * against the document root to find the actual referenced image node.
     */
    private ExtractedAssetCandidate buildCandidate(String assetKey,
            JsonNode assetNode,
            String jsonPath,
            SectionContext sectionContext,
            RawDataStore rawDataStore,
            UploadRequestMetadata requestMetadata,
            JsonNode documentRoot) {
        String assetNodePath = firstNonBlank(textValue(assetNode.get("_path")), jsonPath);

        // Resolve JSON Pointer references: '#/content/73/icon' -> look up that path in the document root
        JsonNode effectiveNode = assetNode;
        if (assetNodePath != null && assetNodePath.startsWith("#/") && documentRoot != null) {
            try {
                String pointer = assetNodePath.substring(1); // strip leading '#', keep leading '/'
                JsonNode resolved = documentRoot.at(com.fasterxml.jackson.core.JsonPointer.compile(pointer));
                if (resolved != null && !resolved.isMissingNode() && !resolved.isNull() && resolved.isObject()) {
                    effectiveNode = resolved;
                    logger.debug("Resolved JSON Pointer '{}' to node with keys: {}", assetNodePath,
                            resolved.fieldNames().next());
                }
            } catch (Exception e) {
                logger.debug("Could not resolve JSON Pointer '{}': {}", assetNodePath, e.getMessage());
            }
        }

        String previewUri = resolvePreviewUri(effectiveNode);
        String interactivePath = firstNonBlank(previewUri, resolveUriFromNode(effectiveNode));
        String publicInteractivePath = toApplePublicUrl(interactivePath, rawDataStore.getSourceUri());
        String altText = firstNonBlank(extractCopyField(effectiveNode.get("alt")), extractCopyField(assetNode.get("alt")));
        String accessibilityText = firstNonBlank(extractCopyField(effectiveNode.get("accessibilityText")),
                extractCopyField(assetNode.get("accessibilityText")));

        Map<String, Object> viewportMap = extractViewportMap(assetNode);
        Map<String, Object> metadataMap = objectMapper.convertValue(
                assetNode, new TypeReference<Map<String, Object>>() {
                });
        // Path belongs to occurrence context; remove from canonical catalog payload.
        Map<String, Object> canonicalCatalogMetadata = new LinkedHashMap<>(metadataMap);
        canonicalCatalogMetadata.remove("_path");

        ResolvedMetadata resolved = resolveMetadata(
                requestMetadata,
                assetNodePath,
                publicInteractivePath,
                sectionContext.path(),
                rawDataStore.getSourceUri());
        if (resolved.locale() == null) {
            resolved = new ResolvedMetadata(
                    resolved.tenant(), resolved.environment(), resolved.project(), resolved.site(),
                    firstNonBlank(resolved.geo(), defaultGeo),
                    firstNonBlank(resolved.locale(), normalizeLocale(defaultLocale)));
        }

        String metadataJson = serializeJson(canonicalCatalogMetadata);
        String viewportsJson = serializeJson(viewportMap);
        String metadataHash = hashString(String.join("|",
                Optional.ofNullable(assetKey).orElse(""),
                Optional.ofNullable(publicInteractivePath).orElse(""),
                Optional.ofNullable(previewUri).orElse(""),
                Optional.ofNullable(altText).orElse(""),
                Optional.ofNullable(accessibilityText).orElse(""),
                Optional.ofNullable(viewportsJson).orElse(""),
                Optional.ofNullable(metadataJson).orElse("")));
        String slotKey = hashString(String.join("|",
                Optional.ofNullable(assetKey).orElse(""),
                Optional.ofNullable(assetNodePath).orElse(""),
                Optional.ofNullable(sectionContext.path()).orElse(""),
                Optional.ofNullable(sectionContext.uri()).orElse("")));

        SitePageContext siteAndPage = deriveSiteAndPageContext(
                resolved.site(),
                sectionContext.path(),
                sectionContext.uri(),
                rawDataStore.getSourceUri(),
                publicInteractivePath,
                previewUri);
        String requestMetadataJson = buildRequestMetadataJson(
                requestMetadata,
                siteAndPage.site(),
                siteAndPage.pageContext());

        return new ExtractedAssetCandidate(
                rawDataStore.getSourceUri(),
                rawDataStore.getVersion(),
                assetKey,
                textValue(assetNode.get("_model")),
                assetNodePath,
                sectionContext.path(),
                sectionContext.uri(),
                publicInteractivePath,
                previewUri,
                altText,
                accessibilityText,
                viewportsJson,
                metadataJson,
                metadataHash,
                slotKey,
                resolved.tenant(),
                resolved.environment(),
                resolved.project(),
                resolved.site(),
                resolved.geo(),
                resolved.locale(),
                requestMetadataJson);
    }

    /**
     * Deduplicates extracted candidates by slot key.
     */
    private List<ExtractedAssetCandidate> deduplicateBySlot(List<ExtractedAssetCandidate> extracted) {
        if (extracted == null || extracted.isEmpty()) {
            return List.of();
        }
        Map<String, ExtractedAssetCandidate> deduped = new LinkedHashMap<>();
        for (ExtractedAssetCandidate candidate : extracted) {
            if (candidate == null)
                continue;
            deduped.putIfAbsent(candidate.assetSlotKey(), candidate);
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * Builds upload-derived region/locale observations for reference table upserts.
     */
    private List<AssetRegionLocaleService.RegionLocaleObservation> buildUploadRegionObservations(
            List<ExtractedAssetCandidate> candidates,
            UploadRequestMetadata requestMetadata,
            UUID rawDataId) {
        String requestLocale = requestMetadata != null ? normalizeLocale(requestMetadata.locale()) : null;
        String requestGeo = requestMetadata != null ? normalizeGeo(requestMetadata.geo()) : null;
        if (requestGeo == null && requestLocale != null && requestLocale.length() >= 5) {
            requestGeo = requestLocale.substring(3).toUpperCase(Locale.ROOT);
        }
        if (requestLocale == null && requestGeo != null) {
            String finalRequestGeo = requestGeo;
            requestLocale = mapGeoToLocale(requestGeo).orElseGet(() -> fallbackLocaleFromGeoCode(finalRequestGeo));
        }

        if (candidates == null || candidates.isEmpty()) {
            if (requestGeo == null || requestLocale == null) {
                return List.of();
            }
            return List.of(new AssetRegionLocaleService.RegionLocaleObservation(
                    requestGeo,
                    requestLocale,
                    "Uploaded locale " + requestLocale,
                    toStorefrontPathFromLocale(requestLocale),
                    rawDataId));
        }
        Map<String, AssetRegionLocaleService.RegionLocaleObservation> deduped = new LinkedHashMap<>();
        for (ExtractedAssetCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String locale = normalizeLocale(candidate.locale());
            String geo = normalizeGeo(candidate.geo());
            if (geo == null && locale != null && locale.length() >= 5) {
                geo = locale.substring(3).toUpperCase(Locale.ROOT);
            }
            if (locale == null && geo != null) {
                String finalGeo = geo;
                locale = mapGeoToLocale(geo).orElseGet(() -> fallbackLocaleFromGeoCode(finalGeo));
            }
            if (geo == null || locale == null) {
                continue;
            }
            String key = geo + "|" + locale;
            deduped.putIfAbsent(
                    key,
                    new AssetRegionLocaleService.RegionLocaleObservation(
                            geo,
                            locale,
                            "Uploaded locale " + locale,
                            toStorefrontPathFromLocale(locale),
                            rawDataId));
        }
        if (requestGeo != null && requestLocale != null) {
            String key = requestGeo + "|" + requestLocale;
            deduped.putIfAbsent(
                    key,
                    new AssetRegionLocaleService.RegionLocaleObservation(
                            requestGeo,
                            requestLocale,
                            "Uploaded locale " + requestLocale,
                            toStorefrontPathFromLocale(requestLocale),
                            rawDataId));
        }
        if (deduped.isEmpty() && requestGeo != null && requestLocale != null) {
            return List.of(new AssetRegionLocaleService.RegionLocaleObservation(
                    requestGeo,
                    requestLocale,
                    "Uploaded locale " + requestLocale,
                    toStorefrontPathFromLocale(requestLocale),
                    rawDataId));
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * Upserts catalog metadata by metadata hash and returns the row.
     */
    private AssetMetadataCatalog upsertCatalog(ExtractedAssetCandidate candidate) {
        Optional<AssetMetadataCatalog> existing = catalogRepository.findByMetadataHash(candidate.metadataHash());
        if (existing.isPresent()) {
            return existing.get();
        }
        AssetMetadataCatalog catalog = new AssetMetadataCatalog();
        catalog.setMetadataHash(candidate.metadataHash());
        catalog.setAssetKey(candidate.assetKey());
        catalog.setAssetModel(candidate.assetModel());
        catalog.setInteractivePath(candidate.interactivePath());
        catalog.setPreviewUri(candidate.previewUri());
        catalog.setAltText(candidate.altText());
        catalog.setAccessibilityText(candidate.accessibilityText());
        catalog.setViewportsJson(candidate.viewportsJson());
        catalog.setAssetMetadataJson(candidate.assetMetadataJson());
        try {
            return catalogRepository.saveAndFlush(catalog);
        } catch (DataIntegrityViolationException duplicate) {
            // Another transaction inserted the same hash first; load it.
            return catalogRepository.findByMetadataHash(candidate.metadataHash())
                    .orElseThrow(() -> duplicate);
        }
    }

    /**
     * Loads a map of catalog rows keyed by id for the supplied occurrences.
     */
    private Map<UUID, AssetMetadataCatalog> loadCatalogMap(List<AssetMetadataOccurrence> occurrences) {
        if (occurrences == null || occurrences.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = occurrences.stream()
                .map(AssetMetadataOccurrence::getCatalogId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return catalogRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AssetMetadataCatalog::getId, c -> c));
    }

    /**
     * Resolves nearest section context from a node.
     */
    private SectionContext resolveSectionContext(JsonNode node, SectionContext fallback) {
        String model = textValue(node.get("_model"));
        String path = textValue(node.get("_path"));
        if (model != null && model.endsWith("-section")) {
            String sectionPath = firstNonBlank(path, fallback != null ? fallback.path() : null);
            return new SectionContext(sectionPath, sectionPath);
        }
        return fallback;
    }

    /**
     * Determines whether a key likely represents an image/icon asset node.
     */
    private boolean isImageLikeKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("image")
                || lower.contains("icon")
                || lower.contains("thumbnail")
                || lower.contains("figure")
                || lower.contains("picture")
                || lower.contains("img")
                || lower.contains("media")
                || lower.contains("asset")
                || lower.contains("photo")
                || lower.contains("banner")
                || lower.contains("poster")
                || lower.contains("avatar")
                || lower.contains("logo")
                || lower.contains("cover")
                || lower.contains("hero")
                || lower.contains("graphic")
                || lower.contains("glyph")
                || lower.contains("artwork")
                || lower.contains("badge")
                || lower.contains("symbol")
                || lower.contains("illustration")
                || lower.contains("background");
    }
 
    /**
     * Determines whether a node's _model suggests it is an image or icon.
     */
    private boolean isAssetModel(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        String model = textValue(node.get("_model"));
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase(Locale.ROOT);
        return lower.contains("image")
                || lower.contains("icon")
                || lower.contains("graphic")
                || lower.contains("media")
                || lower.contains("illustration")
                || lower.contains("photo")
                || lower.contains("picture");
    }

    /**
     * Determines whether a node contains image-like metadata.
     */
    private boolean isLikelyAssetNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (hasAnyUri(node)) {
            return true;
        }
        if (node.has("alt") || node.has("accessibilityText")) {
            return true;
        }
        var fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith("viewport")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a compact viewport map for detail payloads.
     */
    private Map<String, Object> extractViewportMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> viewports = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            String lower = key.toLowerCase(Locale.ROOT);
            if (value.isObject() && lower.startsWith("viewport")) {
                viewports.put(key, objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
                }));
            }
        });

        if (viewports.isEmpty() && hasAnyUri(node)) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            URI_KEYS.forEach(uriKey -> {
                String value = textValue(node.get(uriKey));
                if (value != null) {
                    fallback.put(uriKey, value);
                }
            });
            if (!fallback.isEmpty()) {
                viewports.put("default", fallback);
            }
        }
        return viewports;
    }

    /**
     * Resolves a preview URI from the most suitable viewport first.
     */
    private String resolvePreviewUri(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }

        JsonNode small = node.get("viewportSmall");
        String fromSmall = resolveUriFromNode(small);
        if (fromSmall != null) {
            return fromSmall;
        }

        for (String key : List.of("viewportMedium", "viewportLarge")) {
            String candidate = resolveUriFromNode(node.get(key));
            if (candidate != null) {
                return candidate;
            }
        }

        String direct = resolveUriFromNode(node);
        if (direct != null) {
            return direct;
        }

        return findFirstUriRecursively(node);
    }

    /**
     * Resolves a URI candidate directly from a single node.
     */
    private String resolveUriFromNode(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        for (String key : List.of("uri", "uri1x", "uri2x", "_uri_path", "_uri1x_path", "_uri2x_path", "src", "url",
                "_path")) {
            String value = textValue(node.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Recursively scans child nodes for the first URI-like value.
     */
    private String findFirstUriRecursively(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            String direct = resolveUriFromNode(node);
            if (direct != null) {
                return direct;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String nested = findFirstUriRecursively(entry.getValue());
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                String nested = findFirstUriRecursively(element);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * Extracts copy text from alt/accessibility nodes.
     */
    private String extractCopyField(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return normalizeText(node.asText());
        }
        if (node.isObject()) {
            String copy = textValue(node.get("copy"));
            if (copy != null) {
                return normalizeText(copy);
            }
        }
        return null;
    }

    /**
     * Resolves effective metadata, preferring request metadata then safe inference.
     */
    private ResolvedMetadata resolveMetadata(UploadRequestMetadata requestMetadata,
            String assetNodePath,
            String interactivePath,
            String sectionPath,
            String sourceUri) {
        String requestLocale = requestMetadata != null ? normalizeLocale(requestMetadata.locale()) : null;
        String locale = firstNonBlank(
                requestLocale,
                inferLocale(sectionPath),
                inferLocale(assetNodePath),
                inferLocale(interactivePath),
                inferLocale(sourceUri),
                normalizeLocale(defaultLocale));
        String geo = firstNonBlank(
                requestMetadata != null ? normalizeGeo(requestMetadata.geo()) : null,
                geoFromLocale(locale),
                normalizeGeo(defaultGeo));
        String site = firstNonBlank(
                requestMetadata != null ? normalizeText(requestMetadata.site()) : null,
                inferSite(sectionPath),
                inferSite(assetNodePath),
                inferSite(sourceUri),
                inferSite(interactivePath),
                normalizeText(defaultSite));
        site = normalizeSiteBucket(site);
        String tenant = firstNonBlank(
                requestMetadata != null ? normalizeText(requestMetadata.tenant()) : null,
                inferTenant(sectionPath),
                inferTenant(assetNodePath),
                inferTenant(sourceUri),
                normalizeText(defaultTenant));
        String environment = firstNonBlank(
                requestMetadata != null ? normalizeText(requestMetadata.environment()) : null,
                normalizeText(defaultEnvironment));
        String project = firstNonBlank(
                requestMetadata != null ? normalizeText(requestMetadata.project()) : null,
                normalizeText(defaultProject));
        return new ResolvedMetadata(tenant, environment, project, site, geo, locale);
    }

    /**
     * Parses request metadata JSON from raw_data_store records.
     */
    private UploadRequestMetadata parseRequestMetadata(String json) {
        if (json == null || json.isBlank()) {
            return UploadRequestMetadata.of(null, null, null, null, null, null);
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return UploadRequestMetadata.fromMap(map);
        } catch (Exception e) {
            logger.debug("Unable to parse source_request_metadata. Continuing with inferred values. Reason: {}",
                    e.getMessage());
            return UploadRequestMetadata.of(null, null, null, null, null, null);
        }
    }

    /**
     * Converts an occurrence + catalog pair into a tile DTO.
     */
    private AssetFinderTileDto toTileDto(AssetMetadataOccurrence occurrence, AssetMetadataCatalog catalog) {
        AssetFinderTileDto tile = new AssetFinderTileDto();
        SitePageContext siteAndPage = deriveSiteAndPageContext(
                occurrence.getSite(),
                occurrence.getSectionPath(),
                occurrence.getSectionUri(),
                occurrence.getSourceUri(),
                catalog != null ? catalog.getInteractivePath() : null,
                catalog != null ? catalog.getPreviewUri() : null);
        tile.setId(occurrence.getId());
        tile.setSectionPath(occurrence.getSectionPath());
        tile.setSectionUri(occurrence.getSectionUri());
        tile.setAssetNodePath(occurrence.getAssetNodePath());
        tile.setLocale(occurrence.getLocale());
        tile.setSite(siteAndPage.site());
        tile.setPageContext(siteAndPage.pageContext());
        tile.setGeo(occurrence.getGeo());
        if (catalog != null) {
            tile.setAssetKey(catalog.getAssetKey());
            tile.setAssetModel(catalog.getAssetModel());
            tile.setInteractivePath(toApplePublicUrl(catalog.getInteractivePath(), occurrence.getSectionUri()));
            tile.setPreviewUri(catalog.getPreviewUri());
            tile.setAltText(catalog.getAltText());
        }
        return tile;
    }

    /**
     * Parses JSON text into a map for API responses.
     */
    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Serializes maps as JSON and degrades safely on errors.
     */
    private String serializeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String buildRequestMetadataJson(UploadRequestMetadata requestMetadata,
            String site,
            String pageContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (requestMetadata != null) {
            payload.putAll(requestMetadata.toMap());
        }
        String siteBucket = normalizeSiteBucket(site);
        if (siteBucket != null) {
            payload.put("site", siteBucket);
        }
        String normalizedPageContext = normalizePageContext(pageContext);
        if (normalizedPageContext != null) {
            payload.put("pageContext", normalizedPageContext);
        }
        return serializeJson(payload);
    }

    /**
     * Returns SHA-256 hex hash for the supplied content.
     */
    private String hashString(String content) {
        String safe = content == null ? "" : content;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(safe.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(encoded.length * 2);
            for (byte b : encoded) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(safe.hashCode());
        }
    }

    /**
     * Infers tenant from a CMS path.
     */
    private String inferTenant(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = TENANT_PATTERN.matcher(path);
        if (matcher.find()) {
            return normalizeText(matcher.group(1));
        }
        return null;
    }

    /**
     * Infers locale from a path.
     */
    private String inferLocale(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = LOCALE_PATTERN.matcher(path);
        if (!matcher.find()) {
            return null;
        }
        String language = matcher.group(1).toLowerCase(Locale.ROOT);
        String country = matcher.group(2).toUpperCase(Locale.ROOT);
        return language + "_" + country;
    }

    /**
     * Infers site/page from known path patterns.
     */
    private String inferSite(String path) {
        if (path == null) {
            return null;
        }

        String normalizedPath = path;
        try {
            if (path.startsWith("http")) {
                normalizedPath = URI.create(path).getPath();
            }
        } catch (Exception ignored) {
        }

        Matcher publicMatcher = SITE_FROM_PUBLIC_PATH.matcher(normalizedPath);
        if (publicMatcher.find()) {
            return normalizeSiteBucket(publicMatcher.group(1));
        }
        Matcher assetsMatcher = SITE_FROM_ASSET_PATH.matcher(normalizedPath);
        if (assetsMatcher.find()) {
            return normalizeSiteBucket(assetsMatcher.group(1));
        }
        Matcher contentMatcher = SITE_FROM_CONTENT_PATH.matcher(normalizedPath);
        if (contentMatcher.find()) {
            return normalizeSiteBucket(contentMatcher.group(1));
        }
        return null;
    }

    /**
     * Infers a subpage context (specs/overview/compare) from known paths.
     */
    private String inferPageContext(String path) {
        String normalized = normalizePathOnly(path);
        if (normalized == null) {
            return null;
        }
        Matcher publicMatcher = SITE_FROM_PUBLIC_PATH.matcher(normalized);
        if (publicMatcher.find()) {
            String directContext = normalizePageContext(publicMatcher.group(2));
            if (directContext != null) {
                return directContext;
            }
        }

        for (String token : PAGE_CONTEXT_TOKENS) {
            if (normalized.contains("/" + token + "/")
                    || normalized.endsWith("/" + token)
                    || normalized.equals(token)) {
                return token;
            }
        }
        return null;
    }

    /**
     * Maps locale country values to the geo filter model.
     */
    private String geoFromLocale(String locale) {
        if (locale == null) {
            return null;
        }
        if (locale.length() == 2) {
            return locale.toUpperCase(Locale.ROOT);
        }
        if (locale.length() >= 5) {
            return locale.substring(3).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * Maps geo values into default locale values.
     */
    private Optional<String> mapGeoToLocale(String geo) {
        String normalized = normalizeText(geo);
        if (normalized == null) {
            return Optional.empty();
        }
        AssetRegionLocaleService.RegionOptionsSnapshot groupedSnapshot = resolveRegionOptionsFromOccurrences();
        Optional<String> matchingGeo = groupedSnapshot.geos().stream()
                .filter(value -> value.equalsIgnoreCase(normalized))
                .findFirst();
        if (matchingGeo.isPresent()) {
            List<String> locales = groupedSnapshot.geoToLocales().get(matchingGeo.get());
            if (locales != null && !locales.isEmpty()) {
                return Optional.of(locales.get(0));
            }
        }
        return assetRegionLocaleService.getDefaultLocaleForGeo(normalized);
    }

    /**
     * Provides a deterministic locale fallback when no mapping exists yet.
     */
    private String fallbackLocaleFromGeoCode(String geo) {
        String normalizedGeo = normalizeGeo(geo);
        if (normalizedGeo == null) {
            return null;
        }
        if (normalizedGeo.length() != 2) {
            return null;
        }
        if ("WW".equals(normalizedGeo)) {
            return "en_US";
        }
        return normalizeLocale("en_" + normalizedGeo);
    }

    /**
     * Returns true when the geo filter value is one of configured group labels.
     */
    private boolean isConfiguredGeoGroup(String geo) {
        if (geo == null) {
            return false;
        }
        return GEO_GROUP_ORDER.stream().anyMatch(group -> group.equalsIgnoreCase(geo));
    }

    /**
     * Normalizes raw geo/locale maps into configured business geo groups.
     */
    private AssetRegionLocaleService.RegionOptionsSnapshot buildGeoGroupedSnapshot(
            Map<String, ? extends java.util.Collection<String>> rawGeoToLocales) {
        if (rawGeoToLocales == null || rawGeoToLocales.isEmpty()) {
            return new AssetRegionLocaleService.RegionOptionsSnapshot(List.of(), Map.of());
        }

        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends java.util.Collection<String>> entry : rawGeoToLocales.entrySet()) {
            String rawGeo = normalizeGeo(entry.getKey());
            java.util.Collection<String> locales = entry.getValue() != null ? entry.getValue() : List.of();

            for (String localeRaw : locales) {
                String locale = normalizeLocale(localeRaw);
                if (locale == null) {
                    continue;
                }
                String geoGroup = geoGroupFromLocale(locale);
                grouped.computeIfAbsent(geoGroup, ignored -> new LinkedHashSet<>()).add(locale);
            }

            // Handle rows that may have geo but no locale values.
            if (locales.isEmpty() && rawGeo != null) {
                String geoGroup = geoGroupFromCountry(rawGeo);
                String fallbackLocale = fallbackLocaleFromGeoCode(rawGeo);
                if (fallbackLocale != null) {
                    grouped.computeIfAbsent(geoGroup, ignored -> new LinkedHashSet<>()).add(fallbackLocale);
                }
            }
        }

        if (grouped.isEmpty()) {
            return new AssetRegionLocaleService.RegionOptionsSnapshot(List.of(), Map.of());
        }

        List<String> geos = sortGeoGroups(grouped.keySet());
        Map<String, List<String>> geoToLocales = new LinkedHashMap<>();
        for (String geo : geos) {
            List<String> locales = new ArrayList<>(grouped.getOrDefault(geo, new LinkedHashSet<>()));
            Collections.sort(locales);
            geoToLocales.put(geo, List.copyOf(locales));
        }
        return new AssetRegionLocaleService.RegionOptionsSnapshot(
                List.copyOf(geos),
                Map.copyOf(geoToLocales));
    }

    /**
     * Converts a raw snapshot into grouped geo labels.
     */
    private AssetRegionLocaleService.RegionOptionsSnapshot toGeoGroupedSnapshot(
            AssetRegionLocaleService.RegionOptionsSnapshot rawSnapshot) {
        Map<String, List<String>> rawMap = rawSnapshot != null && rawSnapshot.geoToLocales() != null
                ? rawSnapshot.geoToLocales()
                : Map.of();
        return buildGeoGroupedSnapshot(rawMap);
    }

    /**
     * Sorts geo groups with configured business order first.
     */
    private List<String> sortGeoGroups(java.util.Collection<String> geoGroups) {
        LinkedHashSet<String> remaining = new LinkedHashSet<>();
        for (String group : geoGroups) {
            if (group != null && !group.isBlank()) {
                remaining.add(group);
            }
        }

        List<String> ordered = new ArrayList<>();
        for (String preferred : GEO_GROUP_ORDER) {
            Optional<String> match = remaining.stream()
                    .filter(value -> value.equalsIgnoreCase(preferred))
                    .findFirst();
            if (match.isPresent()) {
                ordered.add(match.get());
                remaining.remove(match.get());
            }
        }

        List<String> extras = new ArrayList<>(remaining);
        extras.sort(String::compareToIgnoreCase);
        ordered.addAll(extras);
        return ordered;
    }

    /**
     * Maps a locale into one configured geo group.
     */
    private String geoGroupFromLocale(String locale) {
        String normalized = normalizeLocale(locale);
        if (normalized == null || normalized.length() < 5) {
            return "WW";
        }
        return geoGroupFromCountry(normalized.substring(3).toUpperCase(Locale.ROOT));
    }

    /**
     * Maps a country code into one configured geo group.
     */
    private String geoGroupFromCountry(String countryCode) {
        String country = normalizeGeo(countryCode);
        if (country == null) {
            return "WW";
        }
        if ("IN".equals(country))
            return "IN";
        if ("JP".equals(country))
            return "JP";
        if ("KR".equals(country))
            return "KR";
        if (ANZ_COUNTRIES.contains(country))
            return "ANZ";
        if (SEA_COUNTRIES.contains(country))
            return "SEA";
        if (ALAC_CA_COUNTRIES.contains(country))
            return "ALAC-CA";
        if (EUROPE_COUNTRIES.contains(country))
            return "Europe";
        if (CEMEA_COUNTRIES.contains(country))
            return "CEMEA";
        return "WW";
    }

    /**
     * Determines whether a node has direct URI-like keys.
     */
    private boolean hasAnyUri(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        for (String key : URI_KEYS) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns text value or null.
     */
    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return normalizeText(text);
    }

    /**
     * Normalizes generic text for filter-safe persistence.
     */
    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Normalizes locale values to ll_CC when possible.
     */
    private String normalizeLocale(String locale) {
        if (locale == null) {
            return null;
        }
        String trimmed = locale.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        String normalized = trimmed.replace('-', '_');
        if (normalized.length() == 5 && normalized.charAt(2) == '_') {
            String language = normalized.substring(0, 2).toLowerCase(Locale.ROOT);
            String country = normalized.substring(3).toUpperCase(Locale.ROOT);
            return language + "_" + country;
        }
        return normalized;
    }

    /**
     * Normalizes geo to uppercase.
     */
    private String normalizeGeo(String geo) {
        String normalized = normalizeText(geo);
        return normalized != null ? normalized.toUpperCase(Locale.ROOT) : null;
    }

    private String normalizePathOnly(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        String withoutHost = normalized.replaceFirst("^https?://[^/]+", "");
        int queryIdx = withoutHost.indexOf('?');
        if (queryIdx >= 0) {
            withoutHost = withoutHost.substring(0, queryIdx);
        }
        return withoutHost;
    }

    private String normalizePageContext(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return PAGE_CONTEXT_TOKENS.contains(lower) ? lower : null;
    }

    private String normalizeSiteBucket(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        String candidate = normalized.replace('\\', '/').trim();
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isBlank()) {
            return null;
        }
        int slashIdx = candidate.indexOf('/');
        String bucket = slashIdx >= 0 ? candidate.substring(0, slashIdx) : candidate;
        return normalizeLower(bucket);
    }

    /**
     * Prefixes relative asset paths with http://www.apple.com for UI links.
     */
    private String toApplePublicUrl(String rawPath, String sourceUri) {
        String normalized = normalizeText(rawPath);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized;
        }
        
        String baseUrl = "http://www.apple.com";
        if (sourceUri != null && !sourceUri.isBlank()) {
            try {
                URI uri = URI.create(sourceUri);
                String host = uri.getHost();
                if (host != null) {
                    baseUrl = uri.getScheme() + "://" + host;
                }
            } catch (Exception ignored) {
            }
        }

        if (normalized.startsWith("/")) {
            return baseUrl + normalized;
        }
        return baseUrl + "/" + normalized;
    }

    /**
     * Converts locale values into storefront-like paths for region references.
     */
    private String toStorefrontPathFromLocale(String locale) {
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
     * Returns the first non-blank value from candidates.
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Escapes JSON path separators in key names.
     */
    private String escapeJsonPathSegment(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Checks once whether both normalized tables exist.
     */
    boolean areTablesPresent() {
        Boolean cached = tablesPresent;
        if (Boolean.TRUE.equals(cached)) {
            return true;
        }
        boolean catalogPresent = false;
        boolean occurrencePresent = false;
        try {
            String reg = jdbcTemplate.queryForObject(
                    "select to_regclass('public.asset_metadata_catalog')",
                    String.class);
            catalogPresent = reg != null;
        } catch (Exception ignored) {
            catalogPresent = false;
        }
        try {
            String reg = jdbcTemplate.queryForObject(
                    "select to_regclass('public.asset_metadata_occurrence')",
                    String.class);
            occurrencePresent = reg != null;
        } catch (Exception ignored) {
            occurrencePresent = false;
        }
        boolean present = catalogPresent && occurrencePresent;
        if (present) {
            tablesPresent = true;
        }
        return present;
    }

    private record SectionContext(String path, String uri) {
    }

    private record ResolvedMetadata(String tenant,
            String environment,
            String project,
            String site,
            String geo,
            String locale) {
    }

    private record SitePageContext(String site, String pageContext) {
    }

    private ExtractedAssetCandidate buildTextualCandidate(
            String assetKey,
            String url,
            String jsonPath,
            SectionContext sectionContext,
            UploadRequestMetadata requestMetadata,
            RawDataStore rawDataStore) {
        String publicInteractivePath = toApplePublicUrl(url, rawDataStore.getSourceUri());

        ResolvedMetadata resolved = resolveMetadata(
                requestMetadata,
                jsonPath,
                publicInteractivePath,
                sectionContext.path(),
                rawDataStore.getSourceUri());

        if (resolved.locale() == null) {
            resolved = new ResolvedMetadata(
                    resolved.tenant(), resolved.environment(), resolved.project(), resolved.site(),
                    firstNonBlank(resolved.geo(), defaultGeo),
                    firstNonBlank(resolved.locale(), normalizeLocale(defaultLocale)));
        }

        String metadataJson = "{}";
        String viewportsJson = "{}";
        String metadataHash = hashString(String.join("|",
                Optional.ofNullable(assetKey).orElse(""),
                Optional.ofNullable(publicInteractivePath).orElse(""),
                "", "", "", "{}", "{}"));
        String slotKey = hashString(String.join("|",
                Optional.ofNullable(assetKey).orElse(""),
                Optional.ofNullable(jsonPath).orElse(""),
                Optional.ofNullable(sectionContext.path()).orElse(""),
                Optional.ofNullable(sectionContext.uri()).orElse("")));

        SitePageContext siteAndPage = deriveSiteAndPageContext(
                resolved.site(),
                sectionContext.path(),
                sectionContext.uri(),
                rawDataStore.getSourceUri(),
                publicInteractivePath,
                null);
        String requestMetadataJson = buildRequestMetadataJson(
                requestMetadata,
                siteAndPage.site(),
                siteAndPage.pageContext());

        return new ExtractedAssetCandidate(
                rawDataStore.getSourceUri(),
                rawDataStore.getVersion(),
                assetKey,
                "image",
                jsonPath,
                sectionContext.path(),
                sectionContext.uri(),
                publicInteractivePath,
                null,
                null,
                null,
                viewportsJson,
                metadataJson,
                metadataHash,
                slotKey,
                resolved.tenant(),
                resolved.environment(),
                resolved.project(),
                resolved.site(),
                resolved.geo(),
                resolved.locale(),
                requestMetadataJson);
    }

    private record ExtractedAssetCandidate(
            String sourceUri,
            Integer sourceVersion,
            String assetKey,
            String assetModel,
            String assetNodePath,
            String sectionPath,
            String sectionUri,
            String interactivePath,
            String previewUri,
            String altText,
            String accessibilityText,
            String viewportsJson,
            String assetMetadataJson,
            String metadataHash,
            String assetSlotKey,
            String tenant,
            String environment,
            String project,
            String site,
            String geo,
            String locale,
            String requestMetadataJson) {
    }
}