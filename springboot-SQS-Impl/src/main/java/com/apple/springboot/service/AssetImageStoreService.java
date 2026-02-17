package com.apple.springboot.service;

import com.apple.springboot.dto.AssetFinderAssetDetailDto;
import com.apple.springboot.dto.AssetFinderExtractionCountResponse;
import com.apple.springboot.dto.AssetFinderFilterRequest;
import com.apple.springboot.dto.AssetFinderOptionsResponse;
import com.apple.springboot.dto.AssetFinderSearchResponse;
import com.apple.springboot.dto.AssetFinderTileDto;
import com.apple.springboot.model.AssetImageStore;
import com.apple.springboot.model.CleansedDataStore;
import com.apple.springboot.model.RawDataStore;
import com.apple.springboot.model.UploadRequestMetadata;
import com.apple.springboot.repository.AssetImageStoreRepository;
import com.apple.springboot.repository.CleansedDataStoreRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

/**
 * Extracts image metadata from uploaded JSON and serves Asset Finder queries.
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
    private static final List<String> DEFAULT_GEOS = List.of("WW", "JP", "KR");
    private static final Map<String, List<String>> GEO_TO_LOCALE = Map.of(
            "WW", List.of("en_US"),
            "JP", List.of("ja_JP"),
            "KR", List.of("ko_KR")
    );

    private final AssetImageStoreRepository assetImageStoreRepository;
    private final CleansedDataStoreRepository cleansedDataStoreRepository;
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
    private volatile Boolean tablePresent;

    /**
     * Creates a service for image extraction and Asset Finder access.
     */
    public AssetImageStoreService(AssetImageStoreRepository assetImageStoreRepository,
                                  CleansedDataStoreRepository cleansedDataStoreRepository,
                                  ObjectMapper objectMapper,
                                  JdbcTemplate jdbcTemplate) {
        this.assetImageStoreRepository = assetImageStoreRepository;
        this.cleansedDataStoreRepository = cleansedDataStoreRepository;
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
        if (!isTablePresent()) {
            return;
        }

        try {
            UploadRequestMetadata requestMetadata = parseRequestMetadata(rawDataStore.getSourceRequestMetadata());
            List<AssetImageStore> extracted = extractAssets(rootNode, rawDataStore, requestMetadata);
            List<AssetImageStore> deduplicated = deduplicateExtractedAssets(extracted);

            // Replace the full snapshot for this source/version to avoid stale collisions on resume/replay.
            if (rawDataStore.getSourceUri() != null && rawDataStore.getVersion() != null) {
                assetImageStoreRepository.deleteBySourceUriAndSourceVersion(
                        rawDataStore.getSourceUri(), rawDataStore.getVersion()
                );
            } else {
                assetImageStoreRepository.deleteByRawDataId(rawDataStore.getId());
            }

            if (!deduplicated.isEmpty()) {
                assetImageStoreRepository.saveAll(deduplicated);
                // Force DB constraint checks inside this guarded block.
                assetImageStoreRepository.flush();
            }
            logger.info("Asset metadata extraction complete for rawDataId {}. Persisted {} image records ({} pre-dedupe).",
                    rawDataStore.getId(), deduplicated.size(), extracted.size());
        } catch (Exception e) {
            logger.warn("Asset metadata extraction failed for rawDataId {}. Continuing ingestion pipeline. Reason: {}",
                    rawDataStore.getId(), e.getMessage());
        }
    }

    /**
     * Removes duplicate rows that would collide on (source_uri, source_version, asset_hash).
     */
    private List<AssetImageStore> deduplicateExtractedAssets(List<AssetImageStore> extracted) {
        if (extracted == null || extracted.isEmpty()) {
            return List.of();
        }
        Map<String, AssetImageStore> deduped = new LinkedHashMap<>();
        for (AssetImageStore item : extracted) {
            if (item == null) continue;
            String key = String.join("|",
                    Optional.ofNullable(item.getSourceUri()).orElse(""),
                    Optional.ofNullable(item.getSourceVersion()).map(String::valueOf).orElse(""),
                    Optional.ofNullable(item.getAssetHash()).orElse("")
            );
            deduped.putIfAbsent(key, item);
        }
        return new ArrayList<>(deduped.values());
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
        sites.addAll(assetImageStoreRepository.findDistinctSites().stream()
                .filter(Objects::nonNull)
                .map(v -> v.toLowerCase(Locale.ROOT))
                .toList());
        String configuredSite = normalizeText(defaultSite);
        if (configuredSite != null) {
            sites.add(configuredSite.toLowerCase(Locale.ROOT));
        }

        response.setTenants(tenants.stream().filter(Objects::nonNull).toList());
        response.setEnvironments(ENVIRONMENTS);
        response.setProjects(new ArrayList<>(projects));
        response.setSites(new ArrayList<>(sites));
        response.setGeos(DEFAULT_GEOS);
        response.setGeoToLocales(GEO_TO_LOCALE);
        return response;
    }

    /**
     * Searches stored image metadata using Asset Finder filters.
     */
    @Transactional(readOnly = true)
    public AssetFinderSearchResponse search(AssetFinderFilterRequest request) {
        AssetFinderFilterRequest safeRequest = request != null ? request : new AssetFinderFilterRequest();
        int page = Math.max(0, Optional.ofNullable(safeRequest.getPage()).orElse(0));
        int size = Math.max(1, Math.min(200, Optional.ofNullable(safeRequest.getSize()).orElse(58)));

        String tenant = normalizeText(safeRequest.getTenant());
        String environment = normalizeText(safeRequest.getEnvironment());
        String project = normalizeText(safeRequest.getProject());
        String site = normalizeText(safeRequest.getSite());
        String geo = normalizeGeo(safeRequest.getGeo());
        String locale = normalizeLocale(safeRequest.getLocale());

        if (locale == null && geo != null) {
            locale = mapGeoToLocale(geo).orElse(null);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AssetImageStore> result = assetImageStoreRepository.search(
                tenant, environment, project, site, geo, locale, pageable
        );

        List<AssetFinderTileDto> tiles = result.getContent().stream()
                .map(this::toTileDto)
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
        return assetImageStoreRepository.findById(id).map(asset -> {
            AssetFinderAssetDetailDto detail = new AssetFinderAssetDetailDto();
            detail.setId(asset.getId());
            detail.setTenant(asset.getTenant());
            detail.setEnvironment(asset.getEnvironment());
            detail.setProject(asset.getProject());
            detail.setSite(asset.getSite());
            detail.setGeo(asset.getGeo());
            detail.setLocale(asset.getLocale());
            detail.setAssetKey(asset.getAssetKey());
            detail.setAssetModel(asset.getAssetModel());
            detail.setSectionPath(asset.getSectionPath());
            detail.setSectionUri(asset.getSectionUri());
            detail.setAssetNodePath(asset.getAssetNodePath());
            detail.setInteractivePath(asset.getInteractivePath());
            detail.setPreviewUri(asset.getPreviewUri());
            detail.setAltText(asset.getAltText());
            detail.setAccessibilityText(asset.getAccessibilityText());
            detail.setViewports(parseJsonObject(asset.getViewportsJson()));
            detail.setMetadata(parseJsonObject(asset.getAssetMetadataJson()));
            return detail;
        });
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
        boolean tableAvailable = isTablePresent();
        response.setTablePresent(tableAvailable);

        long count = 0L;
        if (assetFinderEnabled && tableAvailable && cleansed.getRawDataId() != null) {
            try {
                count = assetImageStoreRepository.countByRawDataId(cleansed.getRawDataId());
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
    private List<AssetImageStore> extractAssets(JsonNode rootNode,
                                                RawDataStore rawDataStore,
                                                UploadRequestMetadata requestMetadata) {
        List<AssetImageStore> results = new ArrayList<>();
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
                               List<AssetImageStore> output) {
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
                        AssetImageStore record = buildAssetRecord(
                                key, value, childJsonPath, sectionContext, rawDataStore, requestMetadata
                        );
                        if (record != null) {
                            output.add(record);
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
     * Builds a persisted record from a discovered asset node.
     */
    private AssetImageStore buildAssetRecord(String assetKey,
                                             JsonNode assetNode,
                                             String jsonPath,
                                             SectionContext sectionContext,
                                             RawDataStore rawDataStore,
                                             UploadRequestMetadata requestMetadata) {
        String assetNodePath = firstNonBlank(textValue(assetNode.get("_path")), jsonPath);
        String previewUri = resolvePreviewUri(assetNode);
        String interactivePath = firstNonBlank(previewUri, resolveUriFromNode(assetNode));
        String altText = extractCopyField(assetNode.get("alt"));
        String accessibilityText = extractCopyField(assetNode.get("accessibilityText"));

        Map<String, Object> viewportMap = extractViewportMap(assetNode);
        Map<String, Object> metadataMap = objectMapper.convertValue(
                assetNode, new TypeReference<Map<String, Object>>() {}
        );

        ResolvedMetadata resolved = resolveMetadata(requestMetadata, assetNodePath, interactivePath);
        if (resolved.locale() == null) {
            resolved = new ResolvedMetadata(
                    resolved.tenant(), resolved.environment(), resolved.project(), resolved.site(),
                    firstNonBlank(resolved.geo(), defaultGeo),
                    firstNonBlank(resolved.locale(), normalizeLocale(defaultLocale))
            );
        }

        AssetImageStore record = new AssetImageStore();
        record.setRawDataId(rawDataStore.getId());
        record.setSourceUri(rawDataStore.getSourceUri());
        record.setSourceVersion(rawDataStore.getVersion());
        record.setTenant(resolved.tenant());
        record.setEnvironment(resolved.environment());
        record.setProject(resolved.project());
        record.setSite(resolved.site());
        record.setGeo(resolved.geo());
        record.setLocale(resolved.locale());
        record.setAssetKey(assetKey);
        record.setAssetModel(textValue(assetNode.get("_model")));
        record.setAssetNodePath(assetNodePath);
        record.setSectionPath(sectionContext.path());
        record.setSectionUri(sectionContext.uri());
        record.setPreviewUri(previewUri);
        record.setInteractivePath(interactivePath);
        record.setAltText(altText);
        record.setAccessibilityText(accessibilityText);
        record.setRequestMetadataJson(serializeJson(requestMetadata != null ? requestMetadata.toMap() : Map.of()));
        record.setViewportsJson(serializeJson(viewportMap));
        record.setAssetMetadataJson(serializeJson(metadataMap));
        record.setAssetHash(hashAsset(rawDataStore, assetKey, assetNodePath, interactivePath, metadataMap));
        record.setUpdatedAt(OffsetDateTime.now());
        return record;
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
     * Converts a model into a lightweight tile DTO.
     */
    private AssetFinderTileDto toTileDto(AssetImageStore item) {
        AssetFinderTileDto tile = new AssetFinderTileDto();
        tile.setId(item.getId());
        tile.setAssetKey(item.getAssetKey());
        tile.setAssetModel(item.getAssetModel());
        tile.setSectionPath(item.getSectionPath());
        tile.setSectionUri(item.getSectionUri());
        tile.setInteractivePath(item.getInteractivePath());
        tile.setPreviewUri(item.getPreviewUri());
        tile.setLocale(item.getLocale());
        tile.setSite(item.getSite());
        tile.setGeo(item.getGeo());
        tile.setAltText(item.getAltText());
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
     * Produces a stable hash for deduplication keys.
     */
    private String hashAsset(RawDataStore rawDataStore,
                             String assetKey,
                             String assetNodePath,
                             String interactivePath,
                             Map<String, Object> metadata) {
        String base = String.join("|",
                Optional.ofNullable(rawDataStore.getSourceUri()).orElse(""),
                Optional.ofNullable(rawDataStore.getVersion()).map(String::valueOf).orElse(""),
                Optional.ofNullable(assetKey).orElse(""),
                Optional.ofNullable(assetNodePath).orElse(""),
                Optional.ofNullable(interactivePath).orElse(""),
                serializeJson(metadata) != null ? serializeJson(metadata) : ""
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(encoded.length * 2);
            for (byte b : encoded) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(base.hashCode());
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
        String country = locale.substring(3).toUpperCase(Locale.ROOT);
        return switch (country) {
            case "JP" -> "JP";
            case "KR" -> "KR";
            default -> "WW";
        };
    }

    /**
     * Maps geo values into default locale values.
     */
    private Optional<String> mapGeoToLocale(String geo) {
        if (geo == null) {
            return Optional.empty();
        }
        List<String> locales = GEO_TO_LOCALE.get(geo.toUpperCase(Locale.ROOT));
        if (locales == null || locales.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(locales.get(0));
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
     * Checks once whether the asset image table exists.
     */
    boolean isTablePresent() {
        Boolean cached = tablePresent;
        if (cached != null) {
            return cached;
        }
        boolean present = false;
        try {
            String reg = jdbcTemplate.queryForObject(
                    "select to_regclass('public.asset_image_store')",
                    String.class
            );
            present = reg != null;
        } catch (Exception ignored) {
            present = false;
        }
        tablePresent = present;
        return present;
    }

    private record SectionContext(String path, String uri) {}

    private record ResolvedMetadata(String tenant,
                                    String environment,
                                    String project,
                                    String site,
                                    String geo,
                                    String locale) {}
}
