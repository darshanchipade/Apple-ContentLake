package com.apple.springboot.service;

import com.apple.springboot.dto.AssetFinderAssetDetailDto;
import com.apple.springboot.dto.AssetFinderExtractionCountResponse;
import com.apple.springboot.dto.AssetFinderFilterRequest;
import com.apple.springboot.dto.AssetFinderOptionsResponse;
import com.apple.springboot.dto.AssetFinderSearchResponse;
import com.apple.springboot.dto.AssetFinderTileDto;
import com.apple.springboot.model.AssetMetadataCatalog;
import com.apple.springboot.model.AssetMetadataOccurrence;
import com.apple.springboot.model.CleansedDataStore;
import com.apple.springboot.model.RawDataStore;
import com.apple.springboot.model.UploadRequestMetadata;
import com.apple.springboot.repository.AssetMetadataCatalogRepository;
import com.apple.springboot.repository.AssetMetadataOccurrenceRepository;
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
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts image metadata from uploaded JSON and serves Asset Finder queries.
 *
 * Normalized design:
 * - asset_metadata_catalog: canonical metadata rows deduplicated by metadata_hash.
 * - asset_metadata_occurrence: per source/version occurrence rows referencing catalog_id.
 */
@Service
public class AssetImageStoreService {

    private static final Logger logger = LoggerFactory.getLogger(AssetImageStoreService.class);

    private static final Pattern LOCALE_PATTERN = Pattern.compile("([a-z]{2})[-_]([A-Z]{2})");
    private static final Pattern TENANT_PATTERN = Pattern.compile("/content/dam/([^/]+)/");
    private static final Pattern SITE_FROM_ASSET_PATH = Pattern.compile("/assets-www/[a-z]{2}[_-][A-Z]{2}/([^/]+)/");
    private static final Pattern SITE_FROM_CONTENT_PATH = Pattern.compile("/live/[a-z]{2}[_-][A-Z]{2}/([^/]+)/");
    private static final Set<String> URI_KEYS = Set.of("uri", "uri1x", "uri2x", "_uri_path", "_uri1x_path", "_uri2x_path");
    private static final List<String> ENVIRONMENTS = List.of("stage", "prod", "qa");
    private static final List<String> DEFAULT_PROJECTS = List.of("rome");
    private static final List<String> DEFAULT_SITES = List.of("ipad", "mac");
    private static final List<String> GEO_GROUP_ORDER = List.of(
            "Europe", "IN", "JP", "KR", "SEA", "WW", "CEMEA", "ANZ", "ALAC-CA"
    );
    private static final Set<String> EUROPE_COUNTRIES = Set.of(
            "AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB",
            "GR", "HR", "HU", "IE", "IS", "IT", "LI", "LT", "LU", "LV", "MT", "NL", "NO",
            "PL", "PT", "RO", "SE", "SI", "SK"
    );
    private static final Set<String> SEA_COUNTRIES = Set.of(
            "SG", "MY", "TH", "VN", "ID", "PH", "BN", "KH", "LA", "MM"
    );
    private static final Set<String> ANZ_COUNTRIES = Set.of("AU", "NZ");
    private static final Set<String> ALAC_CA_COUNTRIES = Set.of(
            "CA", "MX", "AR", "BO", "BR", "CL", "CO", "CR", "DO", "EC",
            "SV", "GT", "HN", "NI", "PA", "PY", "PE", "UY", "VE"
    );
    private static final Set<String> CEMEA_COUNTRIES = Set.of(
            "AE", "SA", "QA", "KW", "OM", "BH", "JO", "IL", "EG", "MA", "TN", "DZ",
            "ZA", "NG", "KE", "UG", "CM", "CI", "BW", "MZ", "MU", "SN", "CF", "GW",
            "GN", "GQ", "ML", "NE", "AM", "AZ", "BY", "GE", "KZ", "KG", "MD", "ME",
            "MK", "RU", "TJ", "TM", "UA", "UZ", "TR"
    );
    private static final List<String> OCCURRENCE_FILTER_TEXT_COLUMNS = List.of(
            "tenant", "environment", "project", "site", "geo", "locale"
    );

    private final AssetMetadataCatalogRepository catalogRepository;
    private final AssetMetadataOccurrenceRepository occurrenceRepository;
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
    private volatile Boolean occurrenceFilterColumnsNormalized;

    /**
     * Creates a service for image extraction and Asset Finder access.
     */
    public AssetImageStoreService(AssetMetadataCatalogRepository catalogRepository,
                                  AssetMetadataOccurrenceRepository occurrenceRepository,
                                  CleansedDataStoreRepository cleansedDataStoreRepository,
                                  AssetRegionLocaleService assetRegionLocaleService,
                                  ObjectMapper objectMapper,
                                  JdbcTemplate jdbcTemplate) {
        this.catalogRepository = catalogRepository;
        this.occurrenceRepository = occurrenceRepository;
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

            // Track observed geo/locale pairs from this upload independently of asset occurrence writes.
            assetRegionLocaleService.recordUploadObservations(
                    buildUploadRegionObservations(deduplicatedBySlot, requestMetadata)
            );

            if (!areTablesPresent()) {
                return;
            }

            // Replace the full occurrence snapshot for this source/version to avoid replay collisions.
            if (rawDataStore.getSourceUri() != null && rawDataStore.getVersion() != null) {
                occurrenceRepository.deleteBySourceUriAndSourceVersion(
                        rawDataStore.getSourceUri(), rawDataStore.getVersion()
                );
            } else {
                occurrenceRepository.deleteByRawDataId(rawDataStore.getId());
            }

            if (!deduplicatedBySlot.isEmpty()) {
                List<AssetMetadataOccurrence> occurrences = new ArrayList<>(deduplicatedBySlot.size());
                for (ExtractedAssetCandidate candidate : deduplicatedBySlot) {
                    AssetMetadataCatalog catalog = upsertCatalog(candidate);
                    AssetMetadataOccurrence occurrence = new AssetMetadataOccurrence();
                    occurrence.setCatalogId(catalog.getId());
                    occurrence.setRawDataId(rawDataStore.getId());
                    occurrence.setSourceUri(rawDataStore.getSourceUri());
                    occurrence.setSourceVersion(rawDataStore.getVersion());
                    occurrence.setAssetSlotKey(candidate.assetSlotKey());
                    occurrence.setAssetNodePath(candidate.assetNodePath());
                    occurrence.setSectionPath(candidate.sectionPath());
                    occurrence.setSectionUri(candidate.sectionUri());
                    occurrence.setTenant(candidate.tenant());
                    occurrence.setEnvironment(candidate.environment());
                    occurrence.setProject(candidate.project());
                    occurrence.setSite(candidate.site());
                    occurrence.setGeo(candidate.geo());
                    occurrence.setLocale(candidate.locale());
                    occurrence.setRequestMetadataJson(candidate.requestMetadataJson());
                    occurrences.add(occurrence);
                }
                occurrenceRepository.saveAll(occurrences);
                // Force DB constraint checks inside this guarded block.
                occurrenceRepository.flush();
            }

            logger.info("Asset metadata extraction complete for rawDataId {}. Persisted {} occurrences ({} pre-dedupe).",
                    rawDataStore.getId(), deduplicatedBySlot.size(), extracted.size());
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
        if (areTablesPresent()) {
            try {
                sites.addAll(occurrenceRepository.findDistinctSites().stream()
                        .filter(Objects::nonNull)
                        .map(v -> v.toLowerCase(Locale.ROOT))
                        .toList());
            } catch (Exception e) {
                logger.warn("Unable to load distinct asset sites; using defaults. Reason: {}", e.getMessage());
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
        AssetRegionLocaleService.RegionOptionsSnapshot regionOptions = resolveRegionOptionsFromOccurrences();
        response.setGeos(regionOptions.geos());
        response.setGeoToLocales(regionOptions.geoToLocales());
        return response;
    }

    /**
     * Builds geo/locale option maps from actual occurrence data first, then fallback reference table.
     */
    private AssetRegionLocaleService.RegionOptionsSnapshot resolveRegionOptionsFromOccurrences() {
        if (!areTablesPresent()) {
            return toGeoGroupedSnapshot(assetRegionLocaleService.getRegionOptionsSnapshot());
        }

        try {
            List<AssetMetadataOccurrenceRepository.GeoLocaleProjection> pairs =
                    occurrenceRepository.findDistinctGeoLocalePairs();
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
            logger.warn("Unable to derive region options from occurrence rows. Falling back to locale reference. Reason: {}",
                    e.getMessage());
            return toGeoGroupedSnapshot(assetRegionLocaleService.getRegionOptionsSnapshot());
        }
    }

    /**
     * Searches stored image metadata using Asset Finder filters.
     */
    @Transactional
    public AssetFinderSearchResponse search(AssetFinderFilterRequest request) {
        AssetFinderFilterRequest safeRequest = request != null ? request : new AssetFinderFilterRequest();
        int page = Math.max(0, Optional.ofNullable(safeRequest.getPage()).orElse(0));
        int size = Math.max(1, Math.min(200, Optional.ofNullable(safeRequest.getSize()).orElse(58)));

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
        String geo = normalizeText(safeRequest.getGeo());
        String locale = normalizeLocale(safeRequest.getLocale());

        if (locale == null && geo != null) {
            locale = mapGeoToLocale(geo).orElse(null);
        }
        if (locale != null) {
            // Locale is the strongest selector and avoids mismatches with grouped geo labels.
            geo = null;
        } else if (geo != null && isConfiguredGeoGroup(geo)) {
            // Grouped geos (Europe/SEA/...) are option labels, not stored raw occurrence values.
            geo = null;
        } else if (geo != null) {
            geo = normalizeGeo(geo);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AssetMetadataOccurrence> result = occurrenceRepository.search(
                tenant, environment, project, site, geo, locale, pageable
        );

        List<AssetMetadataOccurrence> occurrences = result.getContent();
        Map<UUID, AssetMetadataCatalog> catalogs = loadCatalogMap(occurrences);
        List<AssetFinderTileDto> tiles = occurrences.stream()
                .map(occurrence -> toTileDto(occurrence, catalogs.get(occurrence.getCatalogId())))
                .toList();

        AssetFinderSearchResponse response = new AssetFinderSearchResponse();
        response.setCount(result.getTotalElements());
        response.setPage(result.getNumber());
        response.setSize(result.getSize());
        response.setTotalPages(result.getTotalPages());
        response.setItems(tiles);
        return response;
    }

    /**
     * Loads detailed metadata for a single asset tile.
     */
    @Transactional(readOnly = true)
    public Optional<AssetFinderAssetDetailDto> getDetails(UUID id) {
        return occurrenceRepository.findById(id).flatMap(occurrence ->
                catalogRepository.findById(occurrence.getCatalogId()).map(catalog -> {
                    AssetFinderAssetDetailDto detail = new AssetFinderAssetDetailDto();
                    detail.setId(occurrence.getId());
                    detail.setTenant(occurrence.getTenant());
                    detail.setEnvironment(occurrence.getEnvironment());
                    detail.setProject(occurrence.getProject());
                    detail.setSite(occurrence.getSite());
                    detail.setGeo(occurrence.getGeo());
                    detail.setLocale(occurrence.getLocale());
                    detail.setAssetKey(catalog.getAssetKey());
                    detail.setAssetModel(catalog.getAssetModel());
                    detail.setSectionPath(occurrence.getSectionPath());
                    detail.setSectionUri(occurrence.getSectionUri());
                    detail.setAssetNodePath(occurrence.getAssetNodePath());
                    detail.setInteractivePath(toApplePublicUrl(catalog.getInteractivePath()));
                    detail.setPreviewUri(catalog.getPreviewUri());
                    detail.setAltText(catalog.getAltText());
                    detail.setAccessibilityText(catalog.getAccessibilityText());
                    detail.setViewports(parseJsonObject(catalog.getViewportsJson()));
                    detail.setMetadata(parseJsonObject(catalog.getAssetMetadataJson()));
                    return detail;
                })
        );
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
                count = occurrenceRepository.countByRawDataId(cleansed.getRawDataId());
            } catch (Exception e) {
                logger.warn("Unable to count extracted asset rows for rawDataId {}: {}",
                        cleansed.getRawDataId(), e.getMessage());
            }
        }
        response.setAssetCount(count);
        return response;
    }

    /**
     * Extracts all image-like nodes from a JSON payload.
     */
    private List<ExtractedAssetCandidate> extractAssets(JsonNode rootNode,
                                                        RawDataStore rawDataStore,
                                                        UploadRequestMetadata requestMetadata) {
        List<ExtractedAssetCandidate> results = new ArrayList<>();
        collectAssets(rootNode, "#", new SectionContext(null, null), rawDataStore, requestMetadata, results);
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
                               List<ExtractedAssetCandidate> output) {
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
                    if (isImageLikeKey(key) && isLikelyAssetNode(value)) {
                        ExtractedAssetCandidate candidate = buildCandidate(
                                key, value, childJsonPath, sectionContext, rawDataStore, requestMetadata
                        );
                        if (candidate != null) {
                            output.add(candidate);
                        }
                    }
                    collectAssets(value, childJsonPath, sectionContext, rawDataStore, requestMetadata, output);
                } else if (value.isArray()) {
                    collectAssets(value, childJsonPath, sectionContext, rawDataStore, requestMetadata, output);
                }
            });
            return;
        }

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectAssets(node.get(i), jsonPath + "/" + i, currentSection, rawDataStore, requestMetadata, output);
            }
        }
    }

    /**
     * Builds an extracted candidate from a discovered asset node.
     */
    private ExtractedAssetCandidate buildCandidate(String assetKey,
                                                   JsonNode assetNode,
                                                   String jsonPath,
                                                   SectionContext sectionContext,
                                                   RawDataStore rawDataStore,
                                                   UploadRequestMetadata requestMetadata) {
        String assetNodePath = firstNonBlank(textValue(assetNode.get("_path")), jsonPath);
        String previewUri = resolvePreviewUri(assetNode);
        String interactivePath = firstNonBlank(previewUri, resolveUriFromNode(assetNode));
        String publicInteractivePath = toApplePublicUrl(interactivePath);
        String altText = extractCopyField(assetNode.get("alt"));
        String accessibilityText = extractCopyField(assetNode.get("accessibilityText"));

        Map<String, Object> viewportMap = extractViewportMap(assetNode);
        Map<String, Object> metadataMap = objectMapper.convertValue(
                assetNode, new TypeReference<Map<String, Object>>() {}
        );
        // Path belongs to occurrence context; remove from canonical catalog payload.
        Map<String, Object> canonicalCatalogMetadata = new LinkedHashMap<>(metadataMap);
        canonicalCatalogMetadata.remove("_path");

        ResolvedMetadata resolved = resolveMetadata(requestMetadata, assetNodePath, publicInteractivePath);
        if (resolved.locale() == null) {
            resolved = new ResolvedMetadata(
                    resolved.tenant(), resolved.environment(), resolved.project(), resolved.site(),
                    firstNonBlank(resolved.geo(), defaultGeo),
                    firstNonBlank(resolved.locale(), normalizeLocale(defaultLocale))
            );
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
                Optional.ofNullable(metadataJson).orElse("")
        ));
        String slotKey = hashString(String.join("|",
                Optional.ofNullable(assetKey).orElse(""),
                Optional.ofNullable(assetNodePath).orElse(""),
                Optional.ofNullable(sectionContext.path()).orElse(""),
                Optional.ofNullable(sectionContext.uri()).orElse("")
        ));

        String requestMetadataJson = serializeJson(requestMetadata != null ? requestMetadata.toMap() : Map.of());

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
                requestMetadataJson
        );
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
            if (candidate == null) continue;
            deduped.putIfAbsent(candidate.assetSlotKey(), candidate);
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * Builds upload-derived region/locale observations for reference table upserts.
     */
    private List<AssetRegionLocaleService.RegionLocaleObservation> buildUploadRegionObservations(
            List<ExtractedAssetCandidate> candidates,
            UploadRequestMetadata requestMetadata) {
        String requestLocale = requestMetadata != null ? normalizeLocale(requestMetadata.locale()) : null;
        String requestGeo = requestMetadata != null ? normalizeGeo(requestMetadata.geo()) : null;
        if (requestGeo == null && requestLocale != null && requestLocale.length() >= 5) {
            requestGeo = requestLocale.substring(3).toUpperCase(Locale.ROOT);
        }
        if (requestLocale == null && requestGeo != null) {
            requestLocale = mapGeoToLocale(requestGeo).orElseGet(() -> fallbackLocaleFromGeoCode(requestGeo));
        }

        if (candidates == null || candidates.isEmpty()) {
            if (requestGeo == null || requestLocale == null) {
                return List.of();
            }
            return List.of(new AssetRegionLocaleService.RegionLocaleObservation(
                    requestGeo,
                    requestLocale,
                    "Uploaded locale " + requestLocale,
                    toStorefrontPathFromLocale(requestLocale)
            ));
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
                locale = mapGeoToLocale(geo).orElseGet(() -> fallbackLocaleFromGeoCode(geo));
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
                            toStorefrontPathFromLocale(locale)
                    )
            );
        }
        if (requestGeo != null && requestLocale != null) {
            String key = requestGeo + "|" + requestLocale;
            deduped.putIfAbsent(
                    key,
                    new AssetRegionLocaleService.RegionLocaleObservation(
                            requestGeo,
                            requestLocale,
                            "Uploaded locale " + requestLocale,
                            toStorefrontPathFromLocale(requestLocale)
                    )
            );
        }
        if (deduped.isEmpty() && requestGeo != null && requestLocale != null) {
            return List.of(new AssetRegionLocaleService.RegionLocaleObservation(
                    requestGeo,
                    requestLocale,
                    "Uploaded locale " + requestLocale,
                    toStorefrontPathFromLocale(requestLocale)
            ));
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
                || lower.contains("thumbnail");
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
                viewports.put(key, objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {}));
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
        for (String key : List.of("uri", "uri1x", "uri2x", "_uri_path", "_uri1x_path", "_uri2x_path")) {
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
                                             String interactivePath) {
        String requestLocale = requestMetadata != null ? normalizeLocale(requestMetadata.locale()) : null;
        String locale = firstNonBlank(
                requestLocale,
                inferLocale(assetNodePath),
                inferLocale(interactivePath),
                normalizeLocale(defaultLocale)
        );
        String geo = firstNonBlank(
                requestMetadata != null ? normalizeGeo(requestMetadata.geo()) : null,
                geoFromLocale(locale),
                normalizeGeo(defaultGeo)
        );
        String site = firstNonBlank(
                requestMetadata != null ? normalizeText(requestMetadata.site()) : null,
                inferSite(interactivePath),
                inferSite(assetNodePath),
                normalizeText(defaultSite)
        );
        String tenant = firstNonBlank(
                requestMetadata != null ? normalizeText(requestMetadata.tenant()) : null,
                inferTenant(assetNodePath),
                normalizeText(defaultTenant)
        );
        String environment = firstNonBlank(
                requestMetadata != null ? normalizeText(requestMetadata.environment()) : null,
                normalizeText(defaultEnvironment)
        );
        String project = firstNonBlank(
                requestMetadata != null ? normalizeText(requestMetadata.project()) : null,
                normalizeText(defaultProject)
        );
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
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return UploadRequestMetadata.fromMap(map);
        } catch (Exception e) {
            logger.debug("Unable to parse source_request_metadata. Continuing with inferred values. Reason: {}", e.getMessage());
            return UploadRequestMetadata.of(null, null, null, null, null, null);
        }
    }

    /**
     * Converts an occurrence + catalog pair into a tile DTO.
     */
    private AssetFinderTileDto toTileDto(AssetMetadataOccurrence occurrence, AssetMetadataCatalog catalog) {
        AssetFinderTileDto tile = new AssetFinderTileDto();
        tile.setId(occurrence.getId());
        tile.setSectionPath(occurrence.getSectionPath());
        tile.setSectionUri(occurrence.getSectionUri());
        tile.setLocale(occurrence.getLocale());
        tile.setSite(occurrence.getSite());
        tile.setGeo(occurrence.getGeo());
        if (catalog != null) {
            tile.setAssetKey(catalog.getAssetKey());
            tile.setAssetModel(catalog.getAssetModel());
            tile.setInteractivePath(toApplePublicUrl(catalog.getInteractivePath()));
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
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
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
                if (hex.length() == 1) builder.append('0');
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
        Matcher assetsMatcher = SITE_FROM_ASSET_PATH.matcher(path);
        if (assetsMatcher.find()) {
            return normalizeText(assetsMatcher.group(1));
        }
        Matcher contentMatcher = SITE_FROM_CONTENT_PATH.matcher(path);
        if (contentMatcher.find()) {
            return normalizeText(contentMatcher.group(1));
        }
        return null;
    }

    /**
     * Maps locale country values to the geo filter model.
     */
    private String geoFromLocale(String locale) {
        if (locale == null || locale.length() < 5) {
            return null;
        }
        return locale.substring(3).toUpperCase(Locale.ROOT);
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
    private AssetRegionLocaleService.RegionOptionsSnapshot buildGeoGroupedSnapshot(Map<String, ? extends java.util.Collection<String>> rawGeoToLocales) {
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
                Map.copyOf(geoToLocales)
        );
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
        if ("IN".equals(country)) return "IN";
        if ("JP".equals(country)) return "JP";
        if ("KR".equals(country)) return "KR";
        if (ANZ_COUNTRIES.contains(country)) return "ANZ";
        if (SEA_COUNTRIES.contains(country)) return "SEA";
        if (ALAC_CA_COUNTRIES.contains(country)) return "ALAC-CA";
        if (EUROPE_COUNTRIES.contains(country)) return "Europe";
        if (CEMEA_COUNTRIES.contains(country)) return "CEMEA";
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

    /**
     * Prefixes relative asset paths with http://www.apple.com for UI links.
     */
    private String toApplePublicUrl(String rawPath) {
        String normalized = normalizeText(rawPath);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized;
        }
        if (normalized.startsWith("/")) {
            return "http://www.apple.com" + normalized;
        }
        return "http://www.apple.com/" + normalized;
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
                    String.class
            );
            catalogPresent = reg != null;
        } catch (Exception ignored) {
            catalogPresent = false;
        }
        try {
            String reg = jdbcTemplate.queryForObject(
                    "select to_regclass('public.asset_metadata_occurrence')",
                    String.class
            );
            occurrencePresent = reg != null;
        } catch (Exception ignored) {
            occurrencePresent = false;
        }
        boolean present = catalogPresent && occurrencePresent;
        if (present) {
            boolean canNormalizeNow = !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            if (canNormalizeNow) {
                if (ensureOccurrenceFilterColumnsAreText()) {
                    tablesPresent = true;
                }
            } else if (Boolean.TRUE.equals(occurrenceFilterColumnsNormalized)) {
                tablesPresent = true;
            }
        }
        return Boolean.TRUE.equals(tablesPresent);
    }

    /**
     * Ensures legacy BYTEA filter columns are converted to TEXT so lower(...) predicates work.
     */
    private boolean ensureOccurrenceFilterColumnsAreText() {
        if (Boolean.TRUE.equals(occurrenceFilterColumnsNormalized)) {
            return true;
        }
        synchronized (this) {
            if (Boolean.TRUE.equals(occurrenceFilterColumnsNormalized)) {
                return true;
            }
            try {
                for (String column : OCCURRENCE_FILTER_TEXT_COLUMNS) {
                    String udtName = jdbcTemplate.queryForObject(
                            """
                            select udt_name
                            from information_schema.columns
                            where table_schema = 'public'
                              and table_name = 'asset_metadata_occurrence'
                              and column_name = ?
                            """,
                            String.class,
                            column
                    );
                    if (udtName == null || isTextualSqlType(udtName)) {
                        continue;
                    }
                    if ("bytea".equalsIgnoreCase(udtName)) {
                        if (!convertOccurrenceColumnFromByteaToText(column)) {
                            logger.error("Failed converting asset_metadata_occurrence.{} from BYTEA to TEXT.", column);
                            return false;
                        }
                        continue;
                    }
                    logger.error("Unsupported SQL type '{}' for asset_metadata_occurrence.{}; expected TEXT-compatible type.",
                            udtName, column);
                    return false;
                }
                occurrenceFilterColumnsNormalized = true;
                return true;
            } catch (Exception e) {
                logger.error("Unable to normalize asset_metadata_occurrence filter column types. Reason: {}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * Converts a single BYTEA column to TEXT, preferring UTF-8 decode and falling back to escaped bytes.
     */
    private boolean convertOccurrenceColumnFromByteaToText(String column) {
        String utf8Sql = """
                ALTER TABLE asset_metadata_occurrence
                ALTER COLUMN %s TYPE TEXT
                USING CASE WHEN %s IS NULL THEN NULL ELSE convert_from(%s, 'UTF8') END
                """.formatted(column, column, column);
        try {
            jdbcTemplate.execute(utf8Sql);
            logger.info("Converted asset_metadata_occurrence.{} from BYTEA to TEXT using UTF-8 decode.", column);
            return true;
        } catch (Exception utf8Error) {
            logger.warn("UTF-8 conversion failed for asset_metadata_occurrence.{}; retrying with escaped decode. Reason: {}",
                    column, utf8Error.getMessage());
        }

        String escapedSql = """
                ALTER TABLE asset_metadata_occurrence
                ALTER COLUMN %s TYPE TEXT
                USING CASE WHEN %s IS NULL THEN NULL ELSE encode(%s, 'escape') END
                """.formatted(column, column, column);
        try {
            jdbcTemplate.execute(escapedSql);
            logger.info("Converted asset_metadata_occurrence.{} from BYTEA to TEXT using escaped decode.", column);
            return true;
        } catch (Exception escapedError) {
            logger.error("Escaped conversion also failed for asset_metadata_occurrence.{}: {}",
                    column, escapedError.getMessage());
            return false;
        }
    }

    private boolean isTextualSqlType(String udtName) {
        return "text".equalsIgnoreCase(udtName)
                || "varchar".equalsIgnoreCase(udtName)
                || "bpchar".equalsIgnoreCase(udtName);
    }

    private record SectionContext(String path, String uri) {}

    private record ResolvedMetadata(String tenant,
                                    String environment,
                                    String project,
                                    String site,
                                    String geo,
                                    String locale) {}

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
            String requestMetadataJson
    ) {}
}
