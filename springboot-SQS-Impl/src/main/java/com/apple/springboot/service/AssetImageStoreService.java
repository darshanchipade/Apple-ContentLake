package com.apple.springboot.service;

import com.apple.springboot.dto.*;
import com.apple.springboot.model.AssetImageStore;
import com.apple.springboot.model.RawDataStore;
import com.apple.springboot.model.UploadRequestMetadata;
import com.apple.springboot.repository.AssetImageStoreRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AssetImageStoreService {

    private static final Logger logger = LoggerFactory.getLogger(AssetImageStoreService.class);
    private static final Pattern LOCALE_PATTERN = Pattern.compile("(?<=/)([a-z]{2})[-_]([A-Z]{2})(?=/|$)");

    private final AssetImageStoreRepository assetImageStoreRepository;
    private final AppleRegionService appleRegionService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AssetImageStoreService(AssetImageStoreRepository assetImageStoreRepository,
                                   AppleRegionService appleRegionService,
                                   ObjectMapper objectMapper) {
        this.assetImageStoreRepository = assetImageStoreRepository;
        this.appleRegionService = appleRegionService;
        this.objectMapper = objectMapper;
    }

    public AssetFinderOptionsResponse getFilterOptions() {
        AssetFinderOptionsResponse response = new AssetFinderOptionsResponse();
        response.setTenants(assetImageStoreRepository.findDistinctTenants());
        response.setEnvironments(assetImageStoreRepository.findDistinctEnvironments());
        response.setProjects(assetImageStoreRepository.findDistinctProjects());
        response.setSites(assetImageStoreRepository.findDistinctSites());
        response.setGeos(assetImageStoreRepository.findDistinctGeos());
        response.setLocales(assetImageStoreRepository.findDistinctLocales());
        return response;
    }

    public AssetFinderSearchResponse searchAssets(AssetFinderFilterRequest request) {
        AssetImageStore probe = new AssetImageStore();
        if (request.getTenant() != null && !request.getTenant().isEmpty()) probe.setTenant(request.getTenant());
        if (request.getEnvironment() != null && !request.getEnvironment().isEmpty()) probe.setEnvironment(request.getEnvironment());
        if (request.getProject() != null && !request.getProject().isEmpty()) probe.setProject(request.getProject());
        if (request.getSite() != null && !request.getSite().isEmpty()) probe.setSite(request.getSite());
        if (request.getGeo() != null && !request.getGeo().isEmpty()) probe.setGeo(request.getGeo());
        if (request.getLocale() != null && !request.getLocale().isEmpty()) probe.setLocale(request.getLocale());

        ExampleMatcher matcher = ExampleMatcher.matching()
                .withIgnoreNullValues()
                .withStringMatcher(ExampleMatcher.StringMatcher.EXACT);

        Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), Sort.by("createdAt").descending());
        Page<AssetImageStore> page = assetImageStoreRepository.findAll(Example.of(probe, matcher), pageable);

        AssetFinderSearchResponse response = new AssetFinderSearchResponse();
        response.setTiles(page.getContent().stream().map(this::mapToTileDto).toList());
        response.setTotalCount(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setCurrentPage(page.getNumber());
        return response;
    }

    public Optional<AssetFinderAssetDetailDto> getAssetDetail(UUID id) {
        return assetImageStoreRepository.findById(id).map(this::mapToDetailDto);
    }

    private AssetFinderTileDto mapToTileDto(AssetImageStore asset) {
        AssetFinderTileDto dto = new AssetFinderTileDto();
        dto.setId(asset.getId());
        dto.setAssetKey(asset.getAssetKey());
        dto.setPreviewUri(asset.getPreviewUri());
        dto.setAssetNodePath(asset.getAssetNodePath());
        dto.setAltText(asset.getAltText());
        return dto;
    }

    private AssetFinderAssetDetailDto mapToDetailDto(AssetImageStore asset) {
        AssetFinderAssetDetailDto dto = new AssetFinderAssetDetailDto();
        dto.setId(asset.getId());
        dto.setAssetKey(asset.getAssetKey());
        dto.setAssetNodePath(asset.getAssetNodePath());
        dto.setSectionPath(asset.getSectionPath());
        dto.setSectionKey(asset.getSectionKey());
        dto.setPreviewUri(asset.getPreviewUri());
        dto.setAltText(asset.getAltText());
        dto.setAccessibilityText(asset.getAccessibilityText());
        dto.setViewports(asset.getViewportsJson());
        dto.setAssetMetadata(asset.getAssetMetadataJson());
        return dto;
    }

    public void extractAndStoreAssets(String jsonPayload, RawDataStore rawData) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonPayload);
            UploadRequestMetadata metadata = deriveMetadata(rootNode, rawData);

            List<AssetImageStore> assets = new ArrayList<>();
            findAssetsRecursive(rootNode, "", "", metadata, rawData, assets);

            if (!assets.isEmpty()) {
                assetImageStoreRepository.saveAll(assets);
                logger.info("Successfully extracted and stored {} assets for raw data ID: {}", assets.size(), rawData.getId());
            }
        } catch (Exception e) {
            logger.error("Failed to extract image assets for raw data ID: {}. Error: {}", rawData.getId(), e.getMessage(), e);
        }
    }

    private UploadRequestMetadata deriveMetadata(JsonNode rootNode, RawDataStore rawData) {
        UploadRequestMetadata metadata = new UploadRequestMetadata();

        // Try to load from stored request metadata first
        String reqMetaStr = rawData.getSourceRequestMetadata();
        if (reqMetaStr != null && !reqMetaStr.isEmpty()) {
            try {
                JsonNode reqMeta = objectMapper.readTree(reqMetaStr);
                metadata.setTenant(reqMeta.path("tenant").asText(null));
                metadata.setEnvironment(reqMeta.path("environment").asText(null));
                metadata.setProject(reqMeta.path("project").asText("Rome"));
                metadata.setSite(reqMeta.path("site").asText(null));
                metadata.setGeo(reqMeta.path("geo").asText(null));
                metadata.setLocale(reqMeta.path("locale").asText(null));
            } catch (Exception e) {
                logger.warn("Failed to parse request metadata for raw data ID: {}", rawData.getId());
            }
        }

        // Fallback or supplement from path
        String path = rootNode.path("_path").asText(rawData.getSourceUri());
        String[] segments = path.startsWith("/") ? path.substring(1).split("/") : path.split("/");

        if (metadata.getTenant() == null && segments.length >= 3) {
            metadata.setTenant(segments[2]);
        }
        if (metadata.getEnvironment() == null && segments.length >= 4) {
            metadata.setEnvironment(segments[3]);
        }
        if (segments.length >= 5) {
            String localeSegment = segments[4];
            AppleRegionService.RegionInfo regionInfo = appleRegionService.getRegionInfo(localeSegment);

            if (regionInfo != null) {
                if (metadata.getLocale() == null) metadata.setLocale(regionInfo.locale);
                if (metadata.getGeo() == null) metadata.setGeo(regionInfo.geo);
            } else if (metadata.getLocale() == null) {
                metadata.setLocale(localeSegment);
                if (metadata.getGeo() == null) {
                    Matcher matcher = LOCALE_PATTERN.matcher("/" + localeSegment + "/");
                    if (matcher.find()) {
                        metadata.setGeo(matcher.group(2));
                    }
                }
            }
        }
        if (metadata.getSite() == null && segments.length >= 6) {
            metadata.setSite(segments[5]);
        }

        if (metadata.getProject() == null) {
            metadata.setProject("Rome");
        }

        return metadata;
    }

    private void findAssetsRecursive(JsonNode node, String currentPath, String sectionPath, UploadRequestMetadata metadata, RawDataStore rawData, List<AssetImageStore> results) {
        if (node.isObject()) {
            final String nodePath = node.path("_path").asText(currentPath);
            final String model = node.path("_model").asText();

            final String finalNextSectionPath;
            if (model != null && model.endsWith("-section")) {
                finalNextSectionPath = nodePath;
            } else {
                finalNextSectionPath = sectionPath;
            }

            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();

                if (isImageAsset(key, value)) {
                    results.add(createAssetEntry(key, value, nodePath, finalNextSectionPath, metadata, rawData));
                } else {
                    findAssetsRecursive(value, nodePath, finalNextSectionPath, metadata, rawData, results);
                }
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                findAssetsRecursive(node.get(i), currentPath, sectionPath, metadata, rawData, results);
            }
        }
    }

    private boolean isImageAsset(String key, JsonNode node) {
        if (!node.isObject()) return false;
        String k = key.toLowerCase();
        return k.contains("image") || k.contains("icon") || node.has("uri") || node.has("_uri_path");
    }

    private AssetImageStore createAssetEntry(String key, JsonNode node, String nodePath, String sectionPath, UploadRequestMetadata metadata, RawDataStore rawData) {
        AssetImageStore asset = new AssetImageStore();
        asset.setRawDataId(rawData.getId());
        asset.setSourceUri(rawData.getSourceUri());
        asset.setSourceVersion(rawData.getVersion());

        asset.setTenant(metadata.getTenant());
        asset.setEnvironment(metadata.getEnvironment());
        asset.setProject(metadata.getProject());
        asset.setSite(metadata.getSite());
        asset.setGeo(metadata.getGeo());
        asset.setLocale(metadata.getLocale());

        asset.setAssetKey(key);
        asset.setAssetNodePath(node.path("_path").asText(nodePath));
        asset.setSectionPath(sectionPath);
        if (sectionPath != null && !sectionPath.isEmpty()) {
            String[] parts = sectionPath.split("/");
            asset.setSectionKey(parts.length > 0 ? parts[parts.length - 1] : "");
        }

        // Find a suitable preview URI
        String previewUri = node.path("uri").asText("");
        if (previewUri.isEmpty()) {
            previewUri = node.path("_uri_path").asText("");
        }
        if (previewUri.isEmpty() && node.has("viewportSmall")) {
            previewUri = node.path("viewportSmall").path("uri").asText("");
        }
        asset.setPreviewUri(previewUri);

        asset.setAltText(node.path("alt").asText(""));
        asset.setAccessibilityText(node.path("accessibilityText").path("copy").asText(""));

        asset.setViewportsJson(objectMapper.convertValue(extractViewports(node), new TypeReference<Map<String, Object>>() {}));
        asset.setAssetMetadataJson(objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {}));

        asset.setAssetHash(calculateHash(node.toString()));

        return asset;
    }

    private Map<String, Object> extractViewports(JsonNode node) {
        Map<String, Object> viewports = new HashMap<>();
        if (node.has("viewportSmall")) viewports.put("viewportSmall", node.get("viewportSmall"));
        if (node.has("viewportMedium")) viewports.put("viewportMedium", node.get("viewportMedium"));
        if (node.has("viewportLarge")) viewports.put("viewportLarge", node.get("viewportLarge"));
        return viewports;
    }

    private String calculateHash(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
