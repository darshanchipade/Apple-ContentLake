package com.apple.springboot.service;

import com.apple.springboot.model.*;
import com.apple.springboot.repository.CleansedDataStoreRepository;
import com.apple.springboot.repository.ContentHashRepository;
import com.apple.springboot.repository.RawDataStoreRepository;
import com.apple.springboot.repository.EnrichedContentElementRepository;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.common.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Service
public class DataIngestionService {

    private static final Logger logger = LoggerFactory.getLogger(DataIngestionService.class);

    private final RawDataStoreRepository rawDataStoreRepository;

    private ContentHashingService contentHashingService;

    // Treat these as extractable content fields
    /** Bedrock compact-list keys (string arrays) and legacy keys must stay in sync with HtmlTransformationAdapter prompt. */
    private static final Set<String> CONTENT_FIELD_KEYS = Set.of(
            "copy", "disclaimers", "text", "url", "headline", "subheadline",
            "topic", "title", "eyebrow", "caption", "label", "cta",
            "description", "summary", "heading", "features", "list", "bullets", "lines",
            "items", "fullFeaturesList", "notes", "subtitle", "violator",
            "price", "pricing", "legal", "button", "link", "href", 
            "additionalCopy", "continuation"
    );
    private static final Set<String> ICON_NODE_KEYS = Set.of("icon");
    private static final Set<String> ICON_META_KEYS = Set.of("_path", "_uri_path");
    private static final Set<String> IMAGE_META_KEYS = Set.of(
            "_path", "_model", "_id", "_uri1x_path", "_uri2x_path", "_uri_path");
    private static final Pattern LOCALE_PATTERN = Pattern.compile("(?<=/)([a-z]{2})[-_]([A-Z]{2})(?=/|$)");
    private static final String USAGE_REF_DELIM = " ::ref:: ";
    private static final Map<String, String> EVENT_KEYWORDS = Map.of(
            "valentine", "Valentine day",
            "father's day", "Fathers day",
            "tax", "Tax day",
            "christmas", "Christmas",
            "mothers", "Mothers day");

    /** Analytics attribute names for region slug extraction (priority order, aligned with HtmlTransformationAdapter). */
    private static final List<String> ANALYTICS_REGION_ATTRS = List.of(
            "data-analytics-activitymap-region-id",
            "data-analytics-gallery-id",
            "data-analytics-section-engagement",
            "data-analytics-region");

    private final CleansedDataStoreRepository cleansedDataStoreRepository;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final String jsonFilePath;
    private final S3StorageService s3StorageService;
    private final String defaultS3BucketName;
    private final ContentHashRepository contentHashRepository;
    private final ItemVersionHashStore itemVersionHashStore;
    // private final BedrockQueueService bedrockQueueService;
    // private final ConsolidateDataQueueService pushToBedrockQueueService;
    private final BedrockEnrichmentService enrichmentProcessor;
    private final EnrichedContentElementRepository enrichedContentElementRepository;
    private final ContextUpdateService contextUpdateService;
    private final AssetImageStoreService assetImageStoreService;
    private final Set<String> excludedItemTypes;

    // Configurable behavior flags
    @Value("${app.ingestion.keep-blank-after-cleanse:true}")
    private boolean keepBlankAfterCleanse;

    // If true, bypass change filter and return all extracted items
    @Value("${app.ingestion.return-all-items:false}")
    private boolean returnAllItems;

    // If true, include contextHash in change detection
    @Value("${app.ingestion.consider-context-change:false}")
    private boolean considerContextChange;

    // If false, fall back to (sourcePath,itemType) when usagePath not found
    @Value("${app.ingestion.strict-usage-path:false}")
    private boolean strictUsagePath;

    // If true, log debug counters for found vs kept
    @Value("${app.ingestion.debug-counters:true}")
    private boolean debugCountersEnabled;

    // If true, persist per-item hashes per (sourceUri, version) into
    // item_version_hashes
    @Value("${app.ingestion.persist-version-hashes:true}")
    private boolean persistVersionHashes;

    // If true, prefer item_version_hashes for delta comparison (fallback remains
    // intact)
    @Value("${app.ingestion.use-persisted-version-hashes:true}")
    private boolean usePersistedVersionHashes;

    private final com.apple.springboot.repository.IngestionJobRepository ingestionJobRepository;

    // Copy cleansing patterns
    private static final Pattern NBSP_PATTERN = Pattern.compile("\\{%nbsp%\\}");
    private static final Pattern SOSUMI_PATTERN = Pattern.compile("\\{%sosumi type=\"[^\"]+\" metadata=\"\\d+\"%\\}");
    private static final Pattern BR_PATTERN = Pattern.compile("\\{%br%\\}");
    private static final Pattern URL_PATTERN = Pattern
            .compile(":\\s*\\[[^\\]]+\\]\\(\\{%url metadata=\"\\d+\" destination-type=\"[^\"]+\"%\\}\\)");
    // private static final Pattern WJ_PATTERN =
    // Pattern.compile("\\(\\{%wj%\\}\\)");
    private static final Pattern NESTED_URL_PATTERN = Pattern.compile(
            ":\\[\\s*:\\[[^\\]]+\\]\\(\\{%url metadata=\"\\d+\" destination-type=\"[^\"]+\"%\\}\\)\\]\\(\\{%wj%\\}\\)");
    private static final Pattern METADATA_PATTERN = Pattern.compile("\\{% metadata=\"\\d+\" %\\}");

    /**
     * Constructs the service with required repositories and config values.
     */
    public DataIngestionService(RawDataStoreRepository rawDataStoreRepository,
            CleansedDataStoreRepository cleansedDataStoreRepository,
            ContentHashRepository contentHashRepository,
            ItemVersionHashStore itemVersionHashStore,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${app.json.file.path}") String jsonFilePath,
            S3StorageService s3StorageService,
            @Value("${app.s3.bucket-name}") String defaultS3BucketName,
            ContextUpdateService contextUpdateService,
            AssetImageStoreService assetImageStoreService,
            @Value("${app.ingestion.excluded-item-types:}") String excludedItemTypesProperty,
            EnrichedContentElementRepository enrichedContentElementRepository,
            com.apple.springboot.repository.IngestionJobRepository ingestionJobRepository) {
        this.rawDataStoreRepository = rawDataStoreRepository;
        this.cleansedDataStoreRepository = cleansedDataStoreRepository;
        this.contentHashRepository = contentHashRepository;
        this.itemVersionHashStore = itemVersionHashStore;
        this.contextUpdateService = contextUpdateService;
        this.assetImageStoreService = assetImageStoreService;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.jsonFilePath = jsonFilePath;
        this.s3StorageService = s3StorageService;
        this.defaultS3BucketName = defaultS3BucketName;
        this.excludedItemTypes = parseExcludedItemTypes(excludedItemTypesProperty);
        // this.bedrockQueueService = null; // Assuming these are not final or will be
        // uncommented
        // this.pushToBedrockQueueService = null; // Assuming these are not final or
        // will be uncommented
        this.enrichmentProcessor = null; // Initialize to null as per instruction
        this.enrichedContentElementRepository = enrichedContentElementRepository;
        this.ingestionJobRepository = ingestionJobRepository;
    }

    @PostConstruct
    public void backfillMissingIngestionJobs() {
        try {
            List<RawDataStore> rawRecords = rawDataStoreRepository.findAll();
            for (RawDataStore raw : rawRecords) {
                // If an ingestion job doesn't exist for this raw_data_id, create it
                if (ingestionJobRepository.findAllByOrderByCreatedAtDesc().stream()
                        .noneMatch(job -> job.getRawDataId() != null && job.getRawDataId().equals(raw.getId()))) {

                    com.apple.springboot.model.IngestionJob job = new com.apple.springboot.model.IngestionJob();
                    job.setRawDataId(raw.getId());
                    job.setUsername("SystemUser");
                    job.setFileName(raw.getSourceUri() != null
                            ? raw.getSourceUri().substring(raw.getSourceUri().lastIndexOf('/') + 1)
                            : "Legacy Upload");
                    job.setFileSize(0L);
                    job.setSourceChannel(
                            raw.getSourceUri() != null && raw.getSourceUri().startsWith("file-") ? "Local" : "API");

                    // Map raw status
                    job.setStatus(raw.getStatus() != null && raw.getStatus().contains("ERROR") ? "error" : "success");

                    job.setCreatedAt(raw.getReceivedAt() != null ? raw.getReceivedAt() : OffsetDateTime.now());
                    job.setUpdatedAt(OffsetDateTime.now());
                    ingestionJobRepository.save(job);
                    logger.info("Backfilled missing IngestionJob for RawDataStore ID: {}", raw.getId());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to backfill missing ingestion jobs: {}", e.getMessage());
        }
    }

    private static class S3ObjectDetails {
        final String bucketName;
        final String fileKey;

        /**
         * Holds parsed bucket and key values for an S3 URI.
         */
        S3ObjectDetails(String bucketName, String fileKey) {
            this.bucketName = bucketName;
            this.fileKey = fileKey;
        }
    }

    /**
     * Parses an S3 URI into bucket and key components.
     */
    private S3ObjectDetails parseS3Uri(String s3Uri) throws IllegalArgumentException {
        if (s3Uri == null || !s3Uri.startsWith("s3://")) {
            throw new IllegalArgumentException("Invalid S3 URI format: Must start with s3://. Received: " + s3Uri);
        }
        String pathPart = s3Uri.substring("s3://".length());
        if (pathPart.startsWith("///")) {
            String key = pathPart.substring(2);
            if (key.startsWith("/"))
                key = key.substring(1);
            if (key.isEmpty())
                throw new IllegalArgumentException("Invalid S3 URI: Key is empty for default bucket URI " + s3Uri);
            return new S3ObjectDetails(defaultS3BucketName, key);
        } else {
            int firstSlashIndex = pathPart.indexOf('/');
            if (firstSlashIndex == -1 || firstSlashIndex == 0 || firstSlashIndex == pathPart.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid S3 URI format. Expected s3://bucket/key or s3:///key. Received: " + s3Uri);
            }
            String bucket = pathPart.substring(0, firstSlashIndex);
            String key = pathPart.substring(firstSlashIndex + 1);
            if (key.isEmpty())
                throw new IllegalArgumentException(
                        "Invalid S3 URI: Key is empty for bucket '" + bucket + "' in URI " + s3Uri);
            return new S3ObjectDetails(bucket, key);
        }
    }

    /**
     * Loads JSON from configured path and processes it.
     * Handles both S3 and classpath loading strategies.
     */
    @Transactional
    public CleansedDataStore ingestAndCleanseSingleFile() throws JsonProcessingException {
        try {
            return ingestAndCleanseSingleFile(this.jsonFilePath);
        } catch (IOException | RuntimeException e) {
            logger.error("Error processing default jsonFilePath '{}': {}. Creating error record.", this.jsonFilePath,
                    e.getMessage(), e);
            String sourceUri;
            if (!this.jsonFilePath.startsWith("s3://") && !this.jsonFilePath.startsWith("classpath:")) {
                sourceUri = "classpath:" + this.jsonFilePath;
            } else {
                sourceUri = this.jsonFilePath;
            }
            String finalSourceUri = sourceUri;
            RawDataStore rawData = rawDataStoreRepository.findBySourceUri(finalSourceUri).orElseGet(() -> {
                RawDataStore newRawData = new RawDataStore();
                newRawData.setSourceUri(finalSourceUri);
                return newRawData;
            });
            if (rawData.getReceivedAt() == null)
                rawData.setReceivedAt(OffsetDateTime.now());
            rawData.setStatus("FILE_PROCESSING_ERROR");
            rawData.setRawContentText("Error processing file: " + e.getMessage());
            rawDataStoreRepository.save(rawData);
            return createAndSaveErrorCleansedDataStore(rawData, "FILE_ERROR", "ERROR FROM FILE",
                    "FileProcessingError: " + e.getMessage());
        }
    }

    /**
     * Handles ingestion for a specific identifier (s3:// or classpath:).
     * Performs validation, raw storage, deduplication, and cleansing.
     */
    @Transactional
    public CleansedDataStore ingestAndCleanseSingleFile(String identifier) throws IOException {
        logger.info("Starting ingestion and cleansing for identifier: {}", identifier);
        String rawJsonContent;
        String sourceUriForDb = identifier;
        RawDataStore rawDataStore = new RawDataStore();
        rawDataStore.setSourceUri(sourceUriForDb);
        rawDataStore.setReceivedAt(OffsetDateTime.now());

        if (identifier.startsWith("s3://")) {
            logger.info("Identifier is an S3 URI: {}", sourceUriForDb);
            try {
                S3ObjectDetails s3Details = parseS3Uri(sourceUriForDb);
                rawJsonContent = s3StorageService.downloadFileContent(s3Details.bucketName, s3Details.fileKey);
                // setting up source content type
                if (s3Details.fileKey.endsWith(".json")) {
                    rawDataStore.setSourceContentType("application/json");
                } else {
                    rawDataStore.setSourceContentType("application/octet-stream");
                }

                try {
                    JsonNode rootNode = objectMapper.readTree(rawJsonContent);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode).remove("_model");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode).remove("_path");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode).remove("copy");
                    rawDataStore.setSourceMetadata(objectMapper.writeValueAsString(rootNode));
                } catch (JsonProcessingException e) {
                    logger.error("Error processing JSON payload to extract metadata", e);
                }
                if (rawJsonContent == null) {
                    logger.warn("File not found or content is null from S3 URI: {}.", sourceUriForDb);
                    rawDataStore.setStatus("S3_FILE_NOT_FOUND_OR_EMPTY");
                    rawDataStoreRepository.save(rawDataStore);
                    return createAndSaveErrorCleansedDataStore(rawDataStore, "S3_FILE_NOT_FOUND_OR_EMPTY", "S3 ERROR",
                            "S3Error: File not found or content was null at " + sourceUriForDb);
                }
                logger.info("Successfully downloaded content from S3 URI: {}", sourceUriForDb);
                rawDataStore.setStatus("S3_CONTENT_RECEIVED");
            } catch (IllegalArgumentException e) {
                logger.error("Invalid S3 URI format for identifier: '{}'. Error: {}", identifier, e.getMessage());
                rawDataStore.setStatus("INVALID_S3_URI");
                rawDataStore.setRawContentText("Invalid S3 URI: " + e.getMessage());
                rawDataStoreRepository.save(rawDataStore);
                return createAndSaveErrorCleansedDataStore(rawDataStore, "INVALID_S3_URI", "INVALID S3",
                        "InvalidS3URI: " + e.getMessage());
            } catch (Exception e) {
                logger.error("Failed to download S3 content for URI: '{}'. Error: {}", sourceUriForDb, e.getMessage(),
                        e);
                rawDataStore.setStatus("S3_DOWNLOAD_FAILED");
                rawDataStore.setRawContentText("Error fetching S3 content: " + e.getMessage());
                rawDataStoreRepository.save(rawDataStore);
                return createAndSaveErrorCleansedDataStore(rawDataStore, "S3_DOWNLOAD_FAILED", "S3ERROR",
                        "S3DownloadError: " + e.getMessage());
            }
        } else {
            sourceUriForDb = identifier.startsWith("classpath:") ? identifier : "classpath:" + identifier;
            rawDataStore.setSourceUri(sourceUriForDb);
            rawDataStore.setSourceContentType("application/json");
            logger.info("Identifier is a classpath resource: {}", sourceUriForDb);
            Resource resource = resourceLoader.getResource(sourceUriForDb);
            if (!resource.exists()) {
                logger.error("Classpath resource not found: {}", sourceUriForDb);
                rawDataStore.setStatus("CLASSPATH_FILE_NOT_FOUND");
                rawDataStoreRepository.save(rawDataStore);
                return createAndSaveErrorCleansedDataStore(rawDataStore, "CLASSPATH_FILE_NOT_FOUND", "FILE NOT FOUND",
                        "ClasspathError: File not found at " + sourceUriForDb);
            }
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                rawJsonContent = FileCopyUtils.copyToString(reader);
            } catch (IOException e) {
                logger.error("Failed to read raw JSON file from classpath: {}", sourceUriForDb, e);
                rawDataStore.setStatus("CLASSPATH_READ_ERROR");
                rawDataStore.setRawContentText("Error reading classpath file: " + e.getMessage());
                rawDataStoreRepository.save(rawDataStore);
                return createAndSaveErrorCleansedDataStore(rawDataStore, "CLASSPATH_READ_ERROR", "READ ERROR",
                        "IOError: " + e.getMessage());
            }
            try {
                JsonNode rootNode = objectMapper.readTree(rawJsonContent);
                ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode).remove("_model");
                ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode).remove("_path");
                ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode).remove("copy");
                rawDataStore.setSourceMetadata(objectMapper.writeValueAsString(rootNode));
            } catch (JsonProcessingException e) {
                logger.error("Error processing JSON payload to extract metadata", e);
            }
            rawDataStore.setStatus("CLASSPATH_CONTENT_RECEIVED");
        }

        if (rawJsonContent == null || rawJsonContent.trim().isEmpty()) {
            logger.warn("Raw JSON content from {} is effectively empty after loading.", sourceUriForDb);
            rawDataStore.setRawContentText(rawJsonContent);
            rawDataStore.setStatus("EMPTY_CONTENT_LOADED");
            RawDataStore savedForEmpty = rawDataStoreRepository.save(rawDataStore);
            return createAndSaveErrorCleansedDataStore(savedForEmpty, "EMPTY_CONTENT_LOADED", "Error",
                    "ContentError: Loaded content was empty.");
        }
        String contextJson = null;
        try {
            Resource contextResource = resourceLoader.getResource("classpath:context-config.json");
            if (contextResource.exists()) {
                try (Reader reader = new InputStreamReader(contextResource.getInputStream(), StandardCharsets.UTF_8)) {
                    contextJson = FileCopyUtils.copyToString(reader);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read context-config.json, continuing without it.", e);
        }
        String contentHash = calculateContentHash(rawJsonContent, contextJson);
        Optional<RawDataStore> existingRawDataOpt = rawDataStoreRepository.findBySourceUriAndContentHash(sourceUriForDb,
                contentHash);

        if (existingRawDataOpt.isPresent()) {
            RawDataStore existingRawData = existingRawDataOpt.get();
            logger.info("Duplicate content detected for source: {}. Using existing raw_data_id: {}", sourceUriForDb,
                    existingRawData.getId());
            Optional<CleansedDataStore> existingCleansedData = cleansedDataStoreRepository
                    .findByRawDataId(existingRawData.getId());
            if (existingCleansedData.isPresent()) {
                logger.info("Found existing cleansed data for raw_data_id: {}. Skipping processing.",
                        existingRawData.getId());
                return existingCleansedData.get();
            } else {
                logger.info("No existing cleansed data for raw_data_id: {}. Proceeding with processing.",
                        existingRawData.getId());
                return processLoadedContent(rawJsonContent, existingRawData);
            }
        }

        rawDataStore.setRawContentText(rawJsonContent);
        rawDataStore.setRawContentBinary(rawJsonContent.getBytes(StandardCharsets.UTF_8));
        rawDataStore.setContentHash(contentHash);

        // Versioning logic
        Optional<RawDataStore> latestVersionOpt = rawDataStoreRepository
                .findTopBySourceUriOrderByVersionDesc(sourceUriForDb);
        if (!latestVersionOpt.isEmpty()) {
            RawDataStore latestVersion = latestVersionOpt.get();
            if (latestVersion.getLatest()) {
                latestVersion.setLatest(false);
                rawDataStoreRepository.save(latestVersion);
            }
            rawDataStore.setVersion(latestVersion.getVersion() + 1);
        } else {
            rawDataStore.setVersion(1);
        }

        RawDataStore savedRawDataStore = rawDataStoreRepository.save(rawDataStore);
        logger.info("Processed raw data with ID: {} for source: {} with status: {}", savedRawDataStore.getId(),
                sourceUriForDb, savedRawDataStore.getStatus());

        return processLoadedContent(rawJsonContent, savedRawDataStore);
    }

    /**
     * Ingests a raw JSON payload and persists the cleansed result.
     */
    @Transactional
    public CleansedDataStore ingestAndCleanseJsonPayload(String jsonPayload, String sourceIdentifier)
            throws JsonProcessingException {
        return ingestAndCleanseJsonPayload(jsonPayload, sourceIdentifier,
                UploadRequestMetadata.of(null, null, null, null, null, null));
    }

    /**
     * Ingests a raw JSON payload with optional upload metadata and persists the
     * cleansed result.
     */
    @Transactional
    public CleansedDataStore ingestAndCleanseJsonPayload(String jsonPayload,
            String sourceIdentifier,
            UploadRequestMetadata uploadMetadata) throws JsonProcessingException {
        RawDataStore rawDataStore = findOrCreateRawDataStore(jsonPayload, sourceIdentifier, uploadMetadata);
        if (rawDataStore == null) {
            return null;
        }
        return processLoadedContent(jsonPayload, rawDataStore);
    }

    /**
     * Replays the cleansing pipeline for an existing CleansedDataStore record
     * without re-uploading
     * the original payload. This allows downstream steps (cleansing/enrichment) to
     * continue to use
     * the original source metadata (e.g. file-upload/S3 identifiers) instead of
     * creating a new API
     * payload entry.
     *
     * @param cleansedDataStoreId existing CleansedDataStore identifier.
     * @return the refreshed CleansedDataStore entry after reprocessing.
     */
    @Transactional
    public CleansedDataStore resumeFromExistingCleansedId(UUID cleansedDataStoreId) throws JsonProcessingException {
        if (cleansedDataStoreId == null) {
            throw new IllegalArgumentException("CleansedDataStore id must be provided to resume processing.");
        }

        CleansedDataStore existingCleansed = cleansedDataStoreRepository.findById(cleansedDataStoreId)
                .orElseThrow(
                        () -> new IllegalArgumentException("No CleansedDataStore found for id " + cleansedDataStoreId));

        UUID rawDataId = existingCleansed.getRawDataId();
        if (rawDataId == null) {
            throw new IllegalStateException(
                    "CleansedDataStore " + cleansedDataStoreId + " is missing a rawDataId reference.");
        }

        RawDataStore rawDataStore = rawDataStoreRepository.findById(rawDataId)
                .orElseThrow(() -> new IllegalStateException("RawDataStore " + rawDataId
                        + " referenced by cleansed record " + cleansedDataStoreId + " was not found."));

        String rawJsonContent = rawDataStore.getRawContentText();
        if ((rawJsonContent == null || rawJsonContent.isBlank()) && rawDataStore.getRawContentBinary() != null) {
            rawJsonContent = new String(rawDataStore.getRawContentBinary(), StandardCharsets.UTF_8);
        }

        if (rawJsonContent == null || rawJsonContent.isBlank()) {
            throw new IllegalStateException(
                    "RawDataStore " + rawDataId + " does not contain stored JSON content to resume processing.");
        }

        logger.info("Resuming ingestion pipeline for CleansedDataStore {} using RawDataStore {}", cleansedDataStoreId,
                rawDataId);
        // return processLoadedContent(rawJsonContent, rawDataStore);
        return processLoadedContent(rawJsonContent, rawDataStore, existingCleansed);
    }

    /**
     * Refreshes an existing cleansed record with newly processed items.
     */
    private CleansedDataStore refreshExistingCleansedRecord(
            CleansedDataStore existing,
            List<Map<String, Object>> items,
            RawDataStore rawData) {

        existing.setCleansedItems(items);
        existing.setCleansedAt(OffsetDateTime.now());
        existing.setStatus("CLEANSED_PENDING_ENRICHMENT");
        existing.setVersion(rawData.getVersion());
        rawData.setStatus("CLEANSING_COMPLETE");
        rawDataStoreRepository.save(rawData);
        return cleansedDataStoreRepository.save(existing);
    }

    /**
     * Finds an existing RawDataStore or creates a new one for the payload.
     */
    private RawDataStore findOrCreateRawDataStore(String jsonPayload,
            String sourceIdentifier,
            UploadRequestMetadata uploadMetadata) {
        if (jsonPayload == null || jsonPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "JSON payload cannot be null or empty for sourceIdentifier " + sourceIdentifier);
        }

        String newContentHash = calculateCanonicalPayloadHash(jsonPayload);

        // System-Wide Payload Hash Deduplication
        Optional<RawDataStore> existingMatchingContent = rawDataStoreRepository.findByContentHash(newContentHash);
        if (existingMatchingContent.isPresent()) {
            RawDataStore existingRawData = existingMatchingContent.get();
            logger.info(
                    "Ingested content globally matches existing raw data ID {} (originally from {}). Skipping new version for {}.",
                    existingRawData.getId(), existingRawData.getSourceUri(), sourceIdentifier);
            backfillPayloadColumnsIfMissing(existingRawData, jsonPayload, uploadMetadata);
            RawDataStore saved = rawDataStoreRepository.save(existingRawData);
            logIngestionJob(saved, jsonPayload, sourceIdentifier, "SUCCESS_DEDUPLICATED");
            return saved;
        }

        RawDataStore rawDataStore = new RawDataStore();
        rawDataStore.setSourceUri(sourceIdentifier);

        String logicalKey = deriveLogicalKey(jsonPayload, sourceIdentifier);
        rawDataStore.setLogicalKey(logicalKey);

        rawDataStore.setReceivedAt(OffsetDateTime.now());
        rawDataStore.setStatus(resolveUploadStatus(sourceIdentifier));
        rawDataStore.setContentHash(newContentHash);
        rawDataStore.setLatest(true);
        populatePayloadColumns(rawDataStore, jsonPayload, uploadMetadata);

        Optional<RawDataStore> latestVersionOpt = rawDataStoreRepository
                .findTopByLogicalKeyOrderByVersionDesc(logicalKey);
        if (latestVersionOpt.isPresent()) {
            RawDataStore latestVersion = latestVersionOpt.get();
            int nextVersion = Optional.ofNullable(latestVersion.getVersion()).orElse(0) + 1;
            rawDataStore.setVersion(nextVersion);
            if (Boolean.TRUE.equals(latestVersion.isLatest())) {
                latestVersion.setLatest(false);
                rawDataStoreRepository.save(latestVersion);
            }
        } else {
            rawDataStore.setVersion(1);
        }

        logger.info("No matching content found for sourceIdentifier {}. Persisting version {}.", sourceIdentifier,
                rawDataStore.getVersion());
        RawDataStore saved = rawDataStoreRepository.save(rawDataStore);
        logIngestionJob(saved, jsonPayload, sourceIdentifier, saved.getStatus());
        return saved;
    }

    private void logIngestionJob(RawDataStore rawDataStore, String jsonPayload, String sourceIdentifier,
            String status) {
        try {
            com.apple.springboot.model.IngestionJob job = new com.apple.springboot.model.IngestionJob();
            job.setRawDataId(rawDataStore.getId());
            job.setUsername("SystemUser"); // Placeholder for persona

            String safeName = "API Payload";
            if (sourceIdentifier != null) {
                if (sourceIdentifier.startsWith("file-upload:")) {
                    safeName = sourceIdentifier.substring("file-upload:".length());
                } else if (sourceIdentifier.startsWith("s3://")) {
                    safeName = sourceIdentifier.substring(sourceIdentifier.lastIndexOf('/') + 1);
                } else {
                    safeName = sourceIdentifier;
                }
            }
            job.setFileName(safeName);
            job.setFileSize((long) jsonPayload.getBytes(StandardCharsets.UTF_8).length);
            job.setSourceChannel(
                    sourceIdentifier != null && sourceIdentifier.startsWith("file-upload:") ? "Local" : "API");

            // Map raw DB status to UI status visually
            if (status.contains("ERROR")) {
                job.setStatus("error");
            } else {
                job.setStatus("success");
            }

            job.setCreatedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            ingestionJobRepository.save(job);
        } catch (Exception e) {
            logger.error("Failed to append IngestionJob tracking row", e);
        }
    }

    /**
     * Populates raw data storage fields from the payload.
     */
    private void populatePayloadColumns(RawDataStore rawDataStore,
            String jsonPayload,
            UploadRequestMetadata uploadMetadata) {
        rawDataStore.setRawContentText(jsonPayload);
        rawDataStore.setRawContentBinary(jsonPayload.getBytes(StandardCharsets.UTF_8));
        rawDataStore.setSourceContentType("application/json");
        rawDataStore.setSourceMetadata(extractSourceMetadata(toJsonString(canonicalizePayloadForHashAndExtraction(jsonPayload))));
        applyUploadRequestMetadata(rawDataStore, uploadMetadata);
    }

    /**
     * Backfills missing raw payload columns on existing records.
     */
    private void backfillPayloadColumnsIfMissing(RawDataStore rawDataStore,
            String jsonPayload,
            UploadRequestMetadata uploadMetadata) {
        if (rawDataStore.getRawContentText() == null) {
            rawDataStore.setRawContentText(jsonPayload);
        }
        if (rawDataStore.getRawContentBinary() == null) {
            rawDataStore.setRawContentBinary(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }
        if (rawDataStore.getSourceContentType() == null) {
            rawDataStore.setSourceContentType("application/json");
        }
        if (rawDataStore.getSourceMetadata() == null) {
            rawDataStore.setSourceMetadata(extractSourceMetadata(toJsonString(canonicalizePayloadForHashAndExtraction(jsonPayload))));
        }
        if (rawDataStore.getSourceRequestMetadata() == null || rawDataStore.getSourceRequestMetadata().isBlank()) {
            applyUploadRequestMetadata(rawDataStore, uploadMetadata);
        }
    }

    /**
     * Chooses an initial status based on the payload source identifier.
     */
    private String resolveUploadStatus(String sourceIdentifier) {
        if (sourceIdentifier == null) {
            return "API_PAYLOAD_RECEIVED";
        }
        if (sourceIdentifier.startsWith("file-upload:")) {
            return "FILE_UPLOAD_RECEIVED";
        }
        if (sourceIdentifier.startsWith("classpath:")) {
            return "CLASSPATH_CONTENT_RECEIVED";
        }
        return "API_PAYLOAD_RECEIVED";
    }

    /**
     * Strips content fields and returns remaining metadata as JSON.
     */
    private String extractSourceMetadata(String jsonPayload) {
        if (jsonPayload == null) {
            return null;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(jsonPayload);
            if (rootNode instanceof ObjectNode objectNode) {
                objectNode.remove("_model");
                objectNode.remove("_path");
                objectNode.remove("copy");
                return objectMapper.writeValueAsString(objectNode);
            }
            return objectMapper.writeValueAsString(rootNode);
        } catch (JsonProcessingException e) {
            logger.error("Error processing JSON payload to extract metadata", e);
            return null;
        }
    }

    /**
     * Persists request metadata as JSON when supplied by the upload request.
     */
    private void applyUploadRequestMetadata(RawDataStore rawDataStore, UploadRequestMetadata uploadMetadata) {
        if (rawDataStore == null || uploadMetadata == null || uploadMetadata.isEmpty()) {
            return;
        }
        try {
            rawDataStore.setSourceRequestMetadata(objectMapper.writeValueAsString(uploadMetadata.toMap()));
        } catch (JsonProcessingException e) {
            logger.warn("Unable to serialize upload request metadata for source {}. Continuing without it.",
                    rawDataStore.getSourceUri(), e);
        }
    }

    /**
     * Processes raw content and returns a cleansed data record.
     */
    private CleansedDataStore processLoadedContent(String rawJsonContent,
            RawDataStore rawDataStore) throws JsonProcessingException {
        return processLoadedContent(rawJsonContent, rawDataStore, null);
    }

    /**
     * Processes raw content and optionally refreshes an existing cleansed record.
     */
    private CleansedDataStore processLoadedContent(String rawJsonContent,
            RawDataStore rawDataStore,
            @Nullable CleansedDataStore existingCleansed) throws JsonProcessingException {
        try {
            JsonNode rootNode = canonicalizePayloadForHashAndExtraction(rawJsonContent);
            // Asset extraction is additive and fail-open; it must never break text
            // ingestion.
            try {
                assetImageStoreService.safeExtractAndStore(rootNode, rawDataStore);
            } catch (Exception assetError) {
                logger.warn("Asset metadata extraction failed for raw_data_id {}. Continuing ingestion. Reason: {}",
                        rawDataStore.getId(), assetError.getMessage());
            }
            List<Map<String, Object>> allExtractedItems = new ArrayList<>();
            Envelope rootEnvelope = new Envelope();
            rootEnvelope.setSourcePath(rawDataStore.getSourceUri());
            rootEnvelope.setUsagePath(rawDataStore.getSourceUri());
            rootEnvelope.setProvenance(new HashMap<>());

            IngestionCounters counters = new IngestionCounters();
            findAndExtractRecursive(rootNode, "#", rootEnvelope, new Facets(), allExtractedItems, counters);

            // Treat version=1 as a "fresh" run for this sourceUri and return all items.
            // This guards against cases where supporting tables (like content_hashes) were
            // not cleared,
            // but the ingestion run itself is brand new (e.g., DB truncation of
            // raw/cleansed tables).
            boolean forceFullRun = rawDataStore.getVersion() != null && rawDataStore.getVersion() == 1;
            if (forceFullRun && !returnAllItems) {
                logger.info(
                        "Version=1 detected for source {}. Forcing full cleanse output (ignoring delta filter for this run).",
                        rawDataStore.getSourceUri());
            }

            List<Map<String, Object>> itemsToProcess;
            if (returnAllItems || forceFullRun) {
                // Still persist the latest observed hashes so subsequent runs can correctly
                // compute deltas.
                persistItemHashes(allExtractedItems);
                // Also persist hashes for this specific version (optional; safe fallback if
                // table missing).
                persistItemVersionHashes(allExtractedItems, rawDataStore);
                itemsToProcess = allExtractedItems;
            } else {
                itemsToProcess = filterForChangedItemsAgainstPreviousRaw(allExtractedItems, rawDataStore);
                // Always persist latest hashes for future runs.
                persistItemHashes(allExtractedItems);
                // Also persist hashes for this specific version (optional; safe fallback if
                // table missing).
                persistItemVersionHashes(allExtractedItems, rawDataStore);
            }

            if (debugCountersEnabled) {
                long keptPreFilterCopy = counters.copyKept;
                long keptPreFilterAnalytics = counters.analyticsKept;
                long keptPostFilterCopy = itemsToProcess.stream().filter(i -> !isAnalyticsItem(i)).count();
                long keptPostFilterAnalytics = itemsToProcess.stream().filter(this::isAnalyticsItem).count();
                logger.info(
                        "Counters: copy found={}, kept(after-cleanse)={}, kept(after-filter)={}; analytics found={}, kept(after-cleanse)={}, kept(after-filter)={}; blank-kept(copy)={}, blank-kept(analytics)={}",
                        counters.copyFound, keptPreFilterCopy, keptPostFilterCopy,
                        counters.analyticsFound, keptPreFilterAnalytics, keptPostFilterAnalytics,
                        counters.copyBlankKept, counters.analyticsBlankKept);
            }

            if (itemsToProcess.isEmpty()) {
                logger.info("No new or updated content to process for raw_data_id: {}", rawDataStore.getId());
                rawDataStore.setStatus("PROCESSED_NO_CHANGES");
                rawDataStoreRepository.save(rawDataStore);
                CleansedDataStore found = existingCleansed != null
                        ? existingCleansed
                        : cleansedDataStoreRepository
                                .findTopByRawDataIdOrderByCleansedAtDesc(rawDataStore.getId())
                                .orElse(null);

                if (found != null) {
                    return found;
                }

                CleansedDataStore emptyStore = new CleansedDataStore();
                emptyStore.setRawDataId(rawDataStore.getId());
                emptyStore.setSourceUri(rawDataStore.getSourceUri());
                emptyStore.setVersion(rawDataStore.getVersion());
                emptyStore.setCleansedAt(java.time.OffsetDateTime.now());
                emptyStore.setStatus("PROCESSED_NO_CHANGES");
                emptyStore.setCleansedItems(java.util.Collections.emptyList());
                return cleansedDataStoreRepository.save(emptyStore);
            }

            return existingCleansed != null
                    ? refreshExistingCleansedRecord(existingCleansed, itemsToProcess, rawDataStore)
                    : createCleansedDataStore(itemsToProcess, rawDataStore);
        } catch (Exception e) {
            rawDataStore.setStatus("EXTRACTION_ERROR");
            rawDataStoreRepository.save(rawDataStore);
            if (existingCleansed != null) {
                throw e;
            }
            return createAndSaveErrorCleansedDataStore(
                    rawDataStore, "EXTRACTION_FAILED", "ExtractionError: " + e.getMessage(), "Failed");
        }
    }

    /**
     * Filters items down to those that changed versus stored content hashes.
     */
    private List<Map<String, Object>> filterForChangedItems(List<Map<String, Object>> allItems) {
        if (allItems == null || allItems.isEmpty()) {
            return Collections.emptyList();
        }

        // PERF: bulk-load existing hashes to avoid N queries per run.
        Set<String> sourcePaths = new HashSet<>();
        for (Map<String, Object> item : allItems) {
            String sourcePath = (String) item.get("sourcePath");
            if (sourcePath != null) {
                sourcePaths.add(sourcePath);
            }
        }

        Map<String, ContentHash> exactIndex = new HashMap<>();
        Map<String, ContentHash> legacyIndex = new HashMap<>();
        if (!sourcePaths.isEmpty()) {
            List<ContentHash> existing = contentHashRepository.findAllBySourcePathIn(new ArrayList<>(sourcePaths));
            for (ContentHash hash : existing) {
                if (hash.getSourcePath() == null || hash.getItemType() == null) {
                    continue;
                }
                String legacyKey = legacyKey(hash.getSourcePath(), hash.getItemType());
                legacyIndex.put(legacyKey, hash);
                exactIndex.put(exactKey(hash.getSourcePath(), hash.getItemType(), hash.getUsagePath()), hash);
            }
        }

        List<Map<String, Object>> changedItems = new ArrayList<>();
        Map<String, ContentHash> hashesToPersist = new LinkedHashMap<>();

        for (Map<String, Object> item : allItems) {
            String sourcePath = (String) item.get("sourcePath");
            String itemType = (String) item.get("itemType");
            String usagePath = (String) item.get("usagePath");
            String newContentHash = (String) item.get("contentHash");
            String newContextHash = (String) item.get("contextHash");

            if (sourcePath == null || itemType == null)
                continue;

            // The exact record for this (sourcePath,itemType,usagePath).
            ContentHash exactExisting = exactIndex.get(exactKey(sourcePath, itemType, usagePath));

            // For delta detection we may also consider legacy matches if usagePath isn't
            // stable.
            ContentHash matchForComparison = exactExisting;
            boolean usedLegacyFallback = false;
            if (matchForComparison == null && !strictUsagePath) {
                usedLegacyFallback = true;
                String lk = legacyKey(sourcePath, itemType);
                matchForComparison = legacyIndex.get(lk);
                if (matchForComparison != null) {
                    logger.debug(
                            "Change detection fallback matched by (sourcePath,itemType) without usagePath for {} :: {}",
                            sourcePath, itemType);
                }
            }

            boolean skipEnrichment = Boolean.TRUE.equals(item.get("skipEnrichment"));

            boolean contentChanged = matchForComparison == null
                    || !Objects.equals(matchForComparison.getContentHash(), newContentHash);
            boolean contextChanged = considerContextChange && (matchForComparison == null
                    || !Objects.equals(matchForComparison.getContextHash(), newContextHash));

            if (skipEnrichment) {
                // For skipped items, contentHash might be unstable due to blanking.
                // Rely on contextHash (which contains originalValue) for delta syncs.
                if (matchForComparison == null
                        || !Objects.equals(matchForComparison.getContextHash(), newContextHash)) {
                    // Even if it's new to this specific sourcePath, check globally before allowing
                    // it
                    String cleansedText = (String) item.get("cleansedContent");
                    String fieldName = (String) item.get("originalFieldName");
                    if (fieldName != null && cleansedText != null &&
                            enrichedContentElementRepository
                                    .existsByItemOriginalFieldNameAndCleansedText(fieldName, cleansedText)) {
                        // Exists globally in another locale/path, skip it
                        continue;
                    }
                    changedItems.add(item);
                }
            } else if (contentChanged || contextChanged) {
                changedItems.add(item);
            }

            // Always persist/update the hash for the *current* usagePath.
            // IMPORTANT: never overwrite a different usagePath row just because legacy
            // fallback matched.
            ContentHash hashToSave = exactExisting != null
                    ? exactExisting
                    : new ContentHash(sourcePath, itemType, usagePath, null, null);
            hashToSave.setContentHash(newContentHash);
            hashToSave.setContextHash(newContextHash);
            hashesToPersist.put(exactKey(sourcePath, itemType, usagePath), hashToSave);
        }

        if (!hashesToPersist.isEmpty()) {
            contentHashRepository.saveAll(hashesToPersist.values());
        }

        return changedItems;
    }

    /**
     * Computes delta items by comparing against the previous raw JSON version for
     * this sourceUri.
     * This avoids false "no changes" results when the content_hashes table is
     * out-of-sync or shared.
     */
    private List<Map<String, Object>> filterForChangedItemsAgainstPreviousRaw(List<Map<String, Object>> allItems,
            RawDataStore currentRaw) {
        if (allItems == null || allItems.isEmpty()) {
            return Collections.emptyList();
        }
        if (currentRaw == null || currentRaw.getSourceUri() == null || currentRaw.getVersion() == null) {
            // Fall back to existing behavior
            return filterForChangedItems(allItems);
        }

        Integer version = currentRaw.getVersion();
        if (version <= 1) {
            return allItems;
        }

        String lookupKey = currentRaw.getLogicalKey() != null ? currentRaw.getLogicalKey() : currentRaw.getSourceUri();
        Optional<RawDataStore> prevOpt = currentRaw.getLogicalKey() != null
                ? rawDataStoreRepository.findTopByLogicalKeyAndVersionLessThanOrderByVersionDesc(lookupKey, version)
                : rawDataStoreRepository.findTopBySourceUriAndVersionLessThanOrderByVersionDesc(lookupKey, version);

        if (prevOpt.isEmpty()) {
            return filterForChangedItems(allItems);
        }

        // Prefer persisted per-item hashes for the previous version (fast + does not
        // depend on raw JSON retention).
        if (usePersistedVersionHashes) {
            Integer prevVersion = prevOpt.get().getVersion();
            List<ItemVersionHash> prevHashes = itemVersionHashStore.safeLoad(prevOpt.get().getSourceUri(), prevVersion);
            if (prevHashes != null && !prevHashes.isEmpty()) {
                Map<String, ItemVersionHash> prevIndex = new HashMap<>(Math.max(16, prevHashes.size() * 2));
                for (ItemVersionHash h : prevHashes) {
                    if (h == null || h.getSourcePath() == null || h.getItemType() == null)
                        continue;
                    prevIndex.put(exactKey(h.getSourcePath(), h.getItemType(), h.getUsagePath()), h);
                }

                List<Map<String, Object>> changed = new ArrayList<>();
                for (Map<String, Object> item : allItems) {
                    String sourcePath = (String) item.get("sourcePath");
                    String itemType = (String) item.get("itemType");
                    String usagePath = (String) item.get("usagePath");
                    if (sourcePath == null || itemType == null)
                        continue;

                    ItemVersionHash prev = prevIndex.get(exactKey(sourcePath, itemType, usagePath));
                    String newContentHash = (String) item.get("contentHash");
                    String newContextHash = (String) item.get("contextHash");
                    String oldContentHash = prev != null ? prev.getContentHash() : null;
                    String oldContextHash = prev != null ? prev.getContextHash() : null;

                    boolean skipEnrichment = Boolean.TRUE.equals(item.get("skipEnrichment"));

                    boolean contentChanged = prev == null || !Objects.equals(oldContentHash, newContentHash);
                    boolean contextChanged = considerContextChange
                            && (prev == null || !Objects.equals(oldContextHash, newContextHash));

                    if (skipEnrichment) {
                        // For skipped items, contentHash might be unstable due to blanking.
                        // Rely on contextHash (which contains originalValue) for delta syncs.
                        if (prev == null || !Objects.equals(oldContextHash, newContextHash)) {
                            // Even if it's new to this specific sourcePath, check globally before allowing
                            // it
                            String cleansedText = (String) item.get("cleansedContent");
                            String fieldName = (String) item.get("originalFieldName");
                            if (fieldName != null && cleansedText != null &&
                                    enrichedContentElementRepository
                                            .existsByItemOriginalFieldNameAndCleansedText(fieldName, cleansedText)) {
                                // Exists globally in another locale/path, skip it
                                continue;
                            }
                            changed.add(item);
                        }
                    } else if (contentChanged || contextChanged) {
                        changed.add(item);
                    }
                }

                return changed;
            }
        }

        String prevRaw = prevOpt.get().getRawContentText();
        if ((prevRaw == null || prevRaw.isBlank()) && prevOpt.get().getRawContentBinary() != null) {
            prevRaw = new String(prevOpt.get().getRawContentBinary(), StandardCharsets.UTF_8);
        }
        if (prevRaw == null || prevRaw.isBlank()) {
            return filterForChangedItems(allItems);
        }

        List<Map<String, Object>> prevItems = extractItemsFromRaw(prevRaw, currentRaw.getSourceUri());
        Map<String, Map<String, Object>> prevIndex = new HashMap<>(Math.max(16, prevItems.size() * 2));
        for (Map<String, Object> item : prevItems) {
            String sourcePath = (String) item.get("sourcePath");
            String itemType = (String) item.get("itemType");
            String usagePath = (String) item.get("usagePath");
            if (sourcePath == null || itemType == null)
                continue;
            prevIndex.put(exactKey(sourcePath, itemType, usagePath), item);
        }

        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> item : allItems) {
            String sourcePath = (String) item.get("sourcePath");
            String itemType = (String) item.get("itemType");
            String usagePath = (String) item.get("usagePath");
            if (sourcePath == null || itemType == null)
                continue;

            Map<String, Object> prev = prevIndex.get(exactKey(sourcePath, itemType, usagePath));
            String newContentHash = (String) item.get("contentHash");
            String newContextHash = (String) item.get("contextHash");
            String oldContentHash = prev != null ? (String) prev.get("contentHash") : null;
            String oldContextHash = prev != null ? (String) prev.get("contextHash") : null;

            boolean skipEnrichment = Boolean.TRUE.equals(item.get("skipEnrichment"));

            boolean contentChanged = prev == null || !Objects.equals(oldContentHash, newContentHash);
            boolean contextChanged = considerContextChange
                    && (prev == null || !Objects.equals(oldContextHash, newContextHash));

            if (skipEnrichment) {
                if (prev == null || !Objects.equals(oldContextHash, newContextHash)) {
                    changed.add(item);
                }
            } else if (contentChanged || contextChanged) {
                changed.add(item);
            }
        }

        return changed;
    }

    /**
     * Extracts items from a raw JSON payload for delta comparison.
     */
    private List<Map<String, Object>> extractItemsFromRaw(String rawJsonContent, String sourceUri) {
        try {
            JsonNode rootNode = objectMapper.readTree(rawJsonContent);
            List<Map<String, Object>> extracted = new ArrayList<>();
            Envelope rootEnvelope = new Envelope();
            rootEnvelope.setSourcePath(sourceUri);
            rootEnvelope.setUsagePath(sourceUri);
            rootEnvelope.setProvenance(new HashMap<>());
            findAndExtractRecursive(rootNode, "#", rootEnvelope, new Facets(), extracted, new IngestionCounters());
            return extracted;
        } catch (Exception e) {
            logger.warn("Failed to extract previous raw items for delta comparison: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Persists item hashes for the current run (for troubleshooting and optional
     * consumers).
     * Delta computation should not depend on this being perfectly in-sync.
     */
    private void persistItemHashes(List<Map<String, Object>> allItems) {
        if (allItems == null || allItems.isEmpty()) {
            return;
        }
        List<ContentHash> toSave = new ArrayList<>(allItems.size());
        for (Map<String, Object> item : allItems) {
            if (item == null)
                continue;
            String sourcePath = (String) item.get("sourcePath");
            String itemType = (String) item.get("itemType");
            String usagePath = (String) item.get("usagePath");
            String contentHash = (String) item.get("contentHash");
            String contextHash = (String) item.get("contextHash");
            if (sourcePath == null || itemType == null)
                continue;
            // usagePath is part of the entity key; normalize null to empty for safety
            String up = (usagePath == null) ? "" : usagePath;
            ContentHash ch = new ContentHash(sourcePath, itemType, up, contentHash, contextHash);
            toSave.add(ch);
        }
        if (!toSave.isEmpty()) {
            contentHashRepository.saveAll(toSave);
        }
    }

    /**
     * Persists per-item hashes per (sourceUri, version). This is additive and
     * best-effort:
     * - If the backing table isn't present yet, we swallow the failure and keep the
     * pipeline running.
     * - Delta comparison will fall back to the previous-raw comparison path.
     */
    private void persistItemVersionHashes(List<Map<String, Object>> allItems, RawDataStore rawDataStore) {
        if (!persistVersionHashes) {
            return;
        }
        if (rawDataStore == null || rawDataStore.getSourceUri() == null || rawDataStore.getVersion() == null) {
            return;
        }
        if (allItems == null || allItems.isEmpty()) {
            return;
        }

        List<ItemVersionHash> toSave = new ArrayList<>(allItems.size());
        for (Map<String, Object> item : allItems) {
            if (item == null)
                continue;
            String sourcePath = (String) item.get("sourcePath");
            String itemType = (String) item.get("itemType");
            String usagePath = (String) item.get("usagePath");
            String contentHash = (String) item.get("contentHash");
            String contextHash = (String) item.get("contextHash");
            if (sourcePath == null || itemType == null || contentHash == null)
                continue;

            String up = (usagePath == null) ? "" : usagePath;
            toSave.add(new ItemVersionHash(
                    rawDataStore.getSourceUri(),
                    rawDataStore.getVersion(),
                    sourcePath,
                    itemType,
                    up,
                    contentHash,
                    contextHash));
        }

        if (!toSave.isEmpty()) {
            itemVersionHashStore.safeSaveAll(toSave);
        }
    }

    /**
     * Builds a stable key that includes usagePath for hash lookups.
     */
    private String exactKey(String sourcePath, String itemType, String usagePath) {
        return sourcePath + "\u0001" + itemType + "\u0001" + (usagePath == null ? "" : usagePath);
    }

    /**
     * Builds a legacy key without usagePath for backward compatibility.
     */
    private String legacyKey(String sourcePath, String itemType) {
        return sourcePath + "\u0001" + itemType;
    }

    /**
     * Creates and persists a new CleansedDataStore entry.
     */
    private CleansedDataStore createCleansedDataStore(List<Map<String, Object>> items, RawDataStore rawData) {
        CleansedDataStore cleansedDataStore = new CleansedDataStore();
        cleansedDataStore.setRawDataId(rawData.getId());
        cleansedDataStore.setSourceUri(rawData.getSourceUri());
        cleansedDataStore.setCleansedAt(OffsetDateTime.now());
        cleansedDataStore.setCleansedItems(items);
        cleansedDataStore.setVersion(rawData.getVersion());
        cleansedDataStore.setStatus("CLEANSED_PENDING_ENRICHMENT");
        rawData.setStatus("CLEANSING_COMPLETE");
        rawDataStoreRepository.save(rawData);
        return cleansedDataStoreRepository.save(cleansedDataStore);
    }

    /**
     * Traverses the JSON tree and extracts content items with context.
     */
    private void findAndExtractRecursive(JsonNode currentNode, String parentFieldName, Envelope parentEnvelope,
            Facets parentFacets, List<Map<String, Object>> results, IngestionCounters counters) {
        if (currentNode.isObject()) {
            Envelope currentEnvelope = buildCurrentEnvelope(currentNode, parentEnvelope);
            Facets currentFacets = buildCurrentFacets(currentNode, parentFacets);

            // Section detection logic
            String modelName = currentEnvelope.getModel();
            if (modelName != null && modelName.endsWith("-section")) {
                String sectionPath = currentEnvelope.getSourcePath();
                currentFacets.put("sectionModel", modelName);
                currentFacets.put("sectionPath", sectionPath);

                if (sectionPath != null) {
                    String[] pathParts = sectionPath.split("/");
                    if (pathParts.length > 0) {
                        currentFacets.put("sectionKey", pathParts[pathParts.length - 1]);
                    }
                }
                // Extract analyticsRegionSlug from analyticsAttributes (aligned with HTML region slug logic)
                String analyticsSlug = extractAnalyticsRegionSlug(currentNode);
                if (analyticsSlug != null && !analyticsSlug.isBlank()) {
                    currentFacets.put("analyticsRegionSlug", analyticsSlug);
                }
            }

            currentNode.fields().forEachRemaining(entry -> {
                String fieldKey = entry.getKey();
                JsonNode fieldValue = entry.getValue();
                // Grouped HTML view is persisted for observability only. Do not traverse it for
                // cleansing/enrichment extraction, otherwise items from `content` would be double-counted.
                if ("contentGrouped".equals(fieldKey)) {
                    return;
                }
                String fragmentPath = currentEnvelope.getSourcePath();
                String containerPath = (parentEnvelope != null
                        && parentEnvelope.getSourcePath() != null
                        && !parentEnvelope.getSourcePath().equals(fragmentPath))
                                ? parentEnvelope.getSourcePath()
                                : null;
                String usagePath = (containerPath != null)
                        ? containerPath + USAGE_REF_DELIM + fragmentPath
                        : fragmentPath;

                if (CONTENT_FIELD_KEYS.contains(fieldKey)) {
                    if (fieldValue.isTextual()) {
                        currentEnvelope.setUsagePath(usagePath);
                        // If the key is "copy", use the parent's name. Otherwise, use the key itself.
                        String effectiveFieldName = fieldKey.equals("copy") ? parentFieldName : fieldKey;
                        processContentField(fieldValue.asText(), effectiveFieldName, currentEnvelope, currentFacets,
                                results, counters, false);// copy object
                    } else if (fieldValue.isObject() && fieldValue.has("copy") && fieldValue.get("copy").isTextual()) {
                        Envelope contentEnv = buildCurrentEnvelope(fieldValue, currentEnvelope);
                        contentEnv.setUsagePath(usagePath);
                        processContentField(fieldValue.get("copy").asText(), fieldKey, contentEnv, currentFacets,
                                results, counters, false);

                        // text object
                    } else if (fieldValue.isObject() && fieldValue.has("text") && fieldValue.get("text").isTextual()) {
                        Envelope contentEnv = buildCurrentEnvelope(fieldValue, currentEnvelope);
                        contentEnv.setUsagePath(usagePath);
                        processContentField(fieldValue.get("text").asText(), fieldKey, contentEnv, currentFacets,
                                results, counters, false);

                        // url object (string value)
                    } else if (fieldValue.isObject() && fieldValue.has("url") && fieldValue.get("url").isTextual()) {
                        Envelope contentEnv = buildCurrentEnvelope(fieldValue, currentEnvelope);
                        contentEnv.setUsagePath(usagePath);
                        // Store the URL with the literal "url" role so that search can pair it with the CTA
                        processContentField(fieldValue.get("url").asText(), "url", contentEnv, currentFacets,
                                results, counters, false);
                    }
                    // else if (fieldValue.isObject() && fieldValue.has("copy") &&
                    // fieldValue.get("copy").isTextual()) {
                    // currentEnvelope.setUsagePath(usagePath);
                    // // This is a nested content fragment. Use the outer envelope's field name
                    // (fieldKey).
                    // // If this object is under a URL, it would have been returned above. Here we
                    // are safe.
                    // processContentField(fieldValue.get("copy").asText(), fieldKey,
                    // currentEnvelope, currentFacets, results, counters, false);
                    // } else if (fieldValue.isObject() && fieldValue.has("text") &&
                    // fieldValue.get("text").isTextual()) {
                    // currentEnvelope.setUsagePath(usagePath);
                    // processContentField(fieldValue.get("text").asText(), fieldKey,
                    // currentEnvelope, currentFacets, results, counters, false);
                    // } else if (fieldValue.isObject() && fieldValue.has("url") &&
                    // fieldValue.get("url").isTextual()) {
                    // currentEnvelope.setUsagePath(usagePath);
                    // processContentField(fieldValue.get("url").asText(), fieldKey,
                    // currentEnvelope, currentFacets, results, counters, false);
                    // }
                    else if (fieldValue.isArray()) {
                        if ("disclaimers".equals(fieldKey)) {
                            int groupIndex = 0;
                            for (JsonNode element : fieldValue) {
                                if (element.isObject() && element.has("items") && element.get("items").isArray()) {
                                    int itemIndex = 0;
                                    for (JsonNode item : element.get("items")) {
                                        if (item.isObject() && item.has("copy") && item.get("copy").isTextual()) {
                                            currentEnvelope.setUsagePath(usagePath);
                                            String uniqueFieldName = "disclaimer[" + groupIndex + "][" + itemIndex
                                                    + "]";
                                            processContentField(item.get("copy").asText(), uniqueFieldName,
                                                    currentEnvelope, currentFacets, results, counters, false);
                                        }
                                        itemIndex++;
                                    }
                                }
                                groupIndex++;
                            }
                        } else {
                            int idx = 0;
                            for (JsonNode element : fieldValue) {
                                if (element.isTextual()) {
                                    currentEnvelope.setUsagePath(usagePath);
                                    processContentField(element.asText(), fieldKey + "[" + idx + "]", currentEnvelope,
                                            currentFacets, results, counters, false);
                                } else if (element.isObject() && element.has("copy")
                                        && element.get("copy").isTextual()) {
                                    currentEnvelope.setUsagePath(usagePath);
                                    processContentField(element.get("copy").asText(), fieldKey + "[" + idx + "]",
                                            currentEnvelope, currentFacets, results, counters, false);
                                } else if (element.isObject() || element.isArray()) {
                                    currentEnvelope.setUsagePath(usagePath);
                                    findAndExtractRecursive(element, fieldKey + "[" + idx + "]", currentEnvelope,
                                            currentFacets, results, counters);
                                }
                                idx++;
                            }
                        }
                    } else {
                        currentEnvelope.setUsagePath(usagePath);
                        findAndExtractRecursive(fieldValue, fieldKey, currentEnvelope, currentFacets, results,
                                counters);
                    }
                } else if (fieldKey.toLowerCase().contains("analytics")) {
                    currentEnvelope.setUsagePath(usagePath);
                    processAnalyticsNode(fieldValue, fieldKey, currentEnvelope, currentFacets, results, counters);
                } else if (fieldValue.isObject() || fieldValue.isArray()) {
                    currentEnvelope.setUsagePath(usagePath);
                    findAndExtractRecursive(fieldValue, fieldKey, currentEnvelope, currentFacets, results, counters);
                }
            });
        } else if (currentNode.isArray()) {
            for (int i = 0; i < currentNode.size(); i++) {
                JsonNode arrayElement = currentNode.get(i);
                Facets newFacets = new Facets();
                newFacets.putAll(parentFacets);
                newFacets.put("sectionIndex", String.valueOf(i));
                // When the array contains section-like elements (e.g. content.sections[] for AEM
                // JSON, or content[] for HTML-derived JSON), use the section element's envelope
                // as the parent. This ensures sectionPath is stored at section-level (e.g.
                // banner-card-section) rather than at the content parent (webpage-content),
                // so search only fetches that section's fragments, not the whole page.
                Envelope effectiveParent = parentEnvelope;
                if (arrayElement != null && arrayElement.isObject()) {
                    String model = arrayElement.has("_model") && arrayElement.get("_model").isTextual()
                            ? arrayElement.get("_model").asText() : null;
                    boolean isSection = model != null
                            && (model.endsWith("-section") || "html-content-section".equals(model));
                    if (isSection && arrayElement.has("_path") && arrayElement.get("_path").isTextual()) {
                        effectiveParent = buildCurrentEnvelope(arrayElement, parentEnvelope);
                    }
                }
                findAndExtractRecursive(arrayElement, parentFieldName, effectiveParent, newFacets, results, counters);
            }
        }
    }

    /**
     * Builds an envelope object by inheriting and overriding parent context.
     */
    private Envelope buildCurrentEnvelope(JsonNode currentNode, Envelope parentEnvelope) {
        Envelope currentEnvelope = new Envelope();
        // Prefer JSON `_path` when it's a real CMS/content path, but do NOT let it
        // override the
        // ingestion source identifier when `_path` is itself a synthetic source label
        // (e.g. "file-upload:...").
        // This keeps derived keys (content_hashes / item_version_hashes) consistent
        // with Raw/Cleansed stores.
        String parentPath = parentEnvelope != null ? parentEnvelope.getSourcePath() : null;
        String path = parentPath;
        if (currentNode != null && currentNode.has("_path")) {
            String candidate = currentNode.get("_path").asText(null);
            if (candidate != null && !candidate.isBlank() && !candidate.startsWith("file-upload:")) {
                path = candidate;
            }
        }
        currentEnvelope.setSourcePath(path);
        currentEnvelope.setModel(currentNode.path("_model").asText(parentEnvelope.getModel()));
        currentEnvelope.setUsagePath(currentNode.path("_usagePath").asText(parentEnvelope.getUsagePath()));

        if (currentNode.has("_provenance")) {
            try {
                Map<String, String> provenanceMap = objectMapper.convertValue(currentNode.get("_provenance"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                        });
                currentEnvelope.setProvenance(provenanceMap);
            } catch (IllegalArgumentException e) {
                logger.warn("Could not parse _provenance field as a Map for path: {}", path, e);
                currentEnvelope.setProvenance(parentEnvelope != null ? parentEnvelope.getProvenance() : null);
            }
        } else {
            currentEnvelope.setProvenance(parentEnvelope != null ? parentEnvelope.getProvenance() : null);
        }

        // Inheritance Block: Safely pull Locale contexts linearly from Parent Envelopes
        if (parentEnvelope != null) {
            currentEnvelope.setLocale(parentEnvelope.getLocale());
            currentEnvelope.setLanguage(parentEnvelope.getLanguage());
            currentEnvelope.setCountry(parentEnvelope.getCountry());
        }

        // Direct Node Override: Safely pull explicit `locale` JSON overrides from nodes
        if (currentNode != null && currentNode.has("locale")) {
            String localeStr = currentNode.get("locale").asText();
            if (localeStr != null && !localeStr.isBlank()) {
                currentEnvelope.setLocale(localeStr);
                String[] parts = localeStr.split("[_\\-]");
                if (parts.length >= 2) {
                    currentEnvelope.setLanguage(parts[0]);
                    currentEnvelope.setCountry(parts[1]);
                }
            }
        }

        // URL Semantic Fallback: Pass string math against URL path slugs
        if (path != null && currentEnvelope.getLocale() == null) {
            Matcher matcher = LOCALE_PATTERN.matcher(path);
            // cover /en_US/, /en_US, /en-US/, and /en-US.
            if (matcher.find()) {
                String language = matcher.group(1); // "en"
                String country = matcher.group(2); // "US"
                String locale = language + "_" + country;
                currentEnvelope.setLocale(locale);
                currentEnvelope.setLanguage(language);
                currentEnvelope.setCountry(country);
            }
            List<String> pathSegments = Arrays.asList(path.split("/"));
            currentEnvelope.setPathHierarchy(pathSegments);
            if (!pathSegments.isEmpty()) {
                currentEnvelope.setSectionName(pathSegments.get(pathSegments.size() - 1));
            }
            currentEnvelope.setPathHierarchy(Arrays.asList(path.split("/")));
        }
        return currentEnvelope;
    }

    /**
     * Builds a facets map for the current node, inheriting parent values.
     */
    private Facets buildCurrentFacets(JsonNode currentNode, Facets parentFacets) {
        Facets currentFacets = new Facets();
        currentFacets.putAll(parentFacets);
        currentFacets.remove("copy"); // Remove generic copy if it exists
        currentFacets.remove("text"); // Avoid duplicating extracted text
        currentFacets.remove("url"); // Avoid duplicating extracted url text
        currentNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String key = entry.getKey();
            if (value.isValueNode() && !key.startsWith("_")) {
                currentFacets.put(key, value.asText());
            } else if (ICON_NODE_KEYS.contains(key) && value.isObject()) {
                enrichFacetsWithIconProperties(value, key, currentFacets);
            } else if (isImageNodeKey(key) && value.isObject()) {
                enrichFacetsWithImageProperties(value, key, currentFacets);
            }
        });
        return currentFacets;
    }

    /**
     * Extracts a canonical analytics region slug from a section node's analyticsAttributes (or similar).
     * Uses the same priority as HtmlTransformationAdapter: activitymap-region-id → gallery-id →
     * section-engagement (strip "name:" prefix) → region. Slugifies for consistency with HTML path.
     */
    private String extractAnalyticsRegionSlug(JsonNode sectionNode) {
        if (sectionNode == null || !sectionNode.isObject())
            return null;
        String raw = scanAnalyticsForRegionValue(sectionNode);
        // HTML-derived JSON often stores region in _path (html-content-section[N]/<slug>/fieldKey)
        // rather than analyticsAttributes. Use path fallback to keep facets consistent across sources.
        if (raw == null || raw.isBlank()) {
            raw = extractRegionFromHtmlPath(sectionNode);
        }
        if (raw == null || raw.isBlank()) return null;
        return raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /**
     * Extracts region slug from HTML-derived paths:
     *   .../html-content-section[12]/why-apple/headline
     * When no region is present, shape is usually:
     *   .../html-content-section[12]/headline
     */
    private String extractRegionFromHtmlPath(JsonNode node) {
        String fromPath = extractRegionFromSinglePath(node.path("_path").asText(null));
        if (fromPath != null && !fromPath.isBlank()) return fromPath;

        String usagePath = node.path("_usagePath").asText(null);
        if (usagePath == null || usagePath.isBlank()) return null;
        // usagePath may contain "container ::ref:: fragment"; inspect each side.
        for (String part : usagePath.split("\\Q" + USAGE_REF_DELIM + "\\E")) {
            String fromUsagePart = extractRegionFromSinglePath(part);
            if (fromUsagePart != null && !fromUsagePart.isBlank()) return fromUsagePart;
        }
        return null;
    }

    private String extractRegionFromSinglePath(String path) {
        if (path == null || path.isBlank()) return null;
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg != null && seg.startsWith("html-content-section[")) {
                // Need at least two remaining segments to safely infer a region:
                // [region, fieldKey]. If only one remains, it's likely just fieldKey.
                if (i + 2 < segments.length) {
                    String candidate = segments[i + 1];
                    if (candidate != null && !candidate.isBlank()) return candidate;
                }
                return null;
            }
        }
        return null;
    }

    /**
     * Scans analytics containers (analyticsAttributes, analytics, metadata) and returns
     * the first region value in priority order (activitymap → gallery → section-engagement → region).
     */
    private String scanAnalyticsForRegionValue(JsonNode node) {
        Map<String, String> found = new java.util.LinkedHashMap<>();
        for (String containerKey : List.of("analyticsAttributes", "analytics", "metadata")) {
            JsonNode arr = node.get(containerKey);
            if (arr == null || !arr.isArray())
                continue;
            for (JsonNode el : arr) {
                if (!el.isObject())
                    continue;
                String name = el.path("name").asText(null);
                if (name == null || name.isBlank())
                    name = el.path("key").asText(null);
                if (name == null || name.isBlank())
                    continue;
                for (String attr : ANALYTICS_REGION_ATTRS) {
                    if (!attr.equalsIgnoreCase(name))
                        continue;
                    String val = el.path("value").asText(null);
                    if (val == null || val.isBlank())
                        continue;
                    if ("data-analytics-section-engagement".equalsIgnoreCase(name)
                            && val.toLowerCase(java.util.Locale.ROOT).startsWith("name:"))
                        val = val.substring(5).trim();
                    found.putIfAbsent(attr, val);
                    break;
                }
            }
        }
        for (String attr : ANALYTICS_REGION_ATTRS) {
            String val = found.get(attr);
            if (val != null && !val.isBlank())
                return val;
        }
        return null;
    }

    /**
     * Processes a content field into a cleansed item with context.
     */
    private void processContentField(String content, String fieldKey, Envelope envelope, Facets facets,
            List<Map<String, Object>> results, IngestionCounters counters, boolean isAnalytics) {
        boolean skipEnrichment = isExcluded(fieldKey);
        String cleansedContent = cleanseCopyText(content);
        if (isAnalytics)
            counters.analyticsFound++;
        else
            counters.copyFound++;

        boolean isBlankAfterCleanse = cleansedContent == null || cleansedContent.isBlank();
        boolean keep = cleansedContent != null && (!isBlankAfterCleanse || keepBlankAfterCleanse);
        if (keep && isBlankAfterCleanse) {
            if (isAnalytics)
                counters.analyticsBlankKept++;
            else
                counters.copyBlankKept++;
        }

        if (keep) {
            // facets.putAll(facets);
            facets.put("cleansedCopy", cleansedContent);

            String lowerCaseContent = cleansedContent.toLowerCase();
            for (Map.Entry<String, String> entry : EVENT_KEYWORDS.entrySet()) {
                if (lowerCaseContent.contains(entry.getKey())) {
                    facets.put("eventType", entry.getValue());
                    break;
                }
            }
            EnrichmentContext finalContext = new EnrichmentContext(envelope, facets);
            Map<String, Object> item = new HashMap<>();
            item.put("sourcePath", envelope.getSourcePath());
            item.put("itemType", fieldKey);
            item.put("originalFieldName", fieldKey);
            item.put("model", envelope.getModel());
            item.put("usagePath", envelope.getUsagePath());
            item.put("cleansedContent", cleansedContent);
            item.put("contentHash", calculateContentHash(cleansedContent, null));
            item.put("skipEnrichment", skipEnrichment);
            item.put("originalValue", content);
            item.put("cleansedValue", cleansedContent);
            try {
                item.put("context",
                        objectMapper.convertValue(finalContext, new com.fasterxml.jackson.core.type.TypeReference<>() {
                        }));
                // Ensure stable property and map ordering when hashing
                item.put("contextHash", calculateContentHash(objectMapper.writeValueAsString(finalContext), null));
            } catch (JsonProcessingException e) {
                logger.error("Failed to process context for hashing", e);
            }
            results.add(item);
            if (isAnalytics)
                counters.analyticsKept++;
            else
                counters.copyKept++;
        }
    }

    /**
     * Determines whether an extracted item is an analytics value.
     */
    private boolean isAnalyticsItem(Map<String, Object> item) {
        String type = (String) item.get("itemType");
        return type != null && type.toLowerCase().contains("analytics");
    }

    /**
     * Recursively extracts analytics fields from nested structures.
     */
    private void processAnalyticsNode(JsonNode node, String fieldKey, Envelope env, Facets facets,
            List<Map<String, Object>> results, IngestionCounters counters) {
        if (node == null || node.isNull())
            return;

        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            processContentField(node.asText(), fieldKey, env, facets, results, counters, true);
            return;
        }

        if (node.isObject()) {
            Envelope analyticsEnvelope = buildCurrentEnvelope(node, env);
            JsonNode valueNode = node.get("value");
            if (valueNode != null && !valueNode.isNull()) {
                processContentField(valueNode.asText(), fieldKey, analyticsEnvelope, facets, results, counters, true);
            }
            for (String k : List.of("items", "children", "child")) {
                JsonNode arr = node.get(k);
                if (arr != null && arr.isArray()) {
                    int idx = 0;
                    for (JsonNode child : arr) {
                        String childFieldKey = fieldKey + "[" + k + "][" + idx + "]";
                        processAnalyticsNode(child, childFieldKey, analyticsEnvelope, facets, results, counters);
                        idx++;
                    }
                }
            }
            node.fields().forEachRemaining(e -> {
                if (!List.of("items", "children", "child", "value", "_id", "_model", "_path", "contentGrouped")
                        .contains(e.getKey())) {
                    String childFieldKey = fieldKey + "[" + e.getKey() + "]";
                    processAnalyticsNode(e.getValue(), childFieldKey, analyticsEnvelope, facets, results, counters);
                }
            });
            return;
        }

        if (node.isArray()) {
            int i = 0;
            for (JsonNode el : node) {
                if (el.isObject()) {
                    Envelope elEnv = buildCurrentEnvelope(el, env);
                    String name = el.path("name").asText(null);
                    String val = el.path("value").asText(null);
                    if (val != null && !val.isBlank()) {
                        String key = (name != null && !name.isBlank()) ? "analytics[" + name + "]"
                                : "analytics[" + i + "]";
                        processContentField(val, key, elEnv, facets, results, counters, true);
                    }
                } else if (el.isTextual()) {
                    processContentField(el.asText(), "analytics[" + i + "]", env, facets, results, counters, true);
                }
                i++;
            }
        }
    }

    /**
     * Computes a SHA-256 hash for content and optional context.
     */
    private String calculateContentHash(String content, String context) {
        if (content == null)
            return null; // Allow hashing of empty strings to differentiate from null
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            if (context != null && !context.isEmpty()) {
                digest.update(context.getBytes(StandardCharsets.UTF_8));
            }
            byte[] encodedhash = digest.digest();
            return bytesToHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Computes a payload-level dedup hash from canonical flat content.
     * If `contentGrouped` exists (HTML additive view), ignore it so grouped-shape
     * changes do not create new RawDataStore versions.
     */
    private String calculateCanonicalPayloadHash(String jsonPayload) {
        if (jsonPayload == null) {
            return null;
        }
        try {
            JsonNode canonical = canonicalizePayloadForHashAndExtraction(jsonPayload);
            return calculateContentHash(objectMapper.writeValueAsString(canonical), null);
        } catch (Exception e) {
            logger.debug("Unable to canonicalize payload for dedup hash, using raw payload hash. Reason: {}",
                    e.getMessage());
        }
        return calculateContentHash(jsonPayload, null);
    }

    /**
     * Produces canonical flat payload used by dedup and cleansing extraction.
     * Supports grouped-first payloads by projecting contentGrouped[*].items[*] into
     * content[] while preserving page metadata.
     */
    private JsonNode canonicalizePayloadForHashAndExtraction(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(jsonPayload);
            if (!(node instanceof ObjectNode rootObj)) {
                return node;
            }
            ObjectNode canonical = rootObj.deepCopy();
            if (canonical.has("content") && canonical.get("content").isArray()) {
                canonical.remove("contentGrouped");
                return canonical;
            }

            JsonNode grouped = canonical.get("contentGrouped");
            if (grouped == null || !grouped.isArray()) {
                return canonical;
            }

            ArrayNode projected = objectMapper.createArrayNode();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (JsonNode group : grouped) {
                JsonNode items = group.path("items");
                if (!items.isArray()) {
                    continue;
                }
                for (JsonNode item : items) {
                    if (!item.isObject()) {
                        continue;
                    }
                    String key = item.path("_path").asText(null);
                    if (key == null || key.isBlank()) {
                        key = item.toString();
                    }
                    if (seen.add(key)) {
                        projected.add(item.deepCopy());
                    }
                }
            }

            canonical.set("content", projected);
            canonical.remove("contentGrouped");
            return canonical;
        } catch (Exception e) {
            logger.debug("Unable to canonicalize grouped payload for extraction/hash. Reason: {}", e.getMessage());
            try {
                return objectMapper.readTree(jsonPayload);
            } catch (Exception ignored) {
                return objectMapper.createObjectNode();
            }
        }
    }

    private String toJsonString(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return node.toString();
        }
    }

    /**
     * Converts a byte array into a hexadecimal string.
     */
    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1)
                hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Creates and persists an error cleansed record with the supplied status.
     */
    private CleansedDataStore createAndSaveErrorCleansedDataStore(RawDataStore rawDataStore, String cleansedStatus,
            String errorMessage, String specificErrorMessage) {
        CleansedDataStore errorCleansedData = new CleansedDataStore();
        if (rawDataStore != null) {
            errorCleansedData.setRawDataId(rawDataStore.getId());
            errorCleansedData.setSourceUri(rawDataStore.getSourceUri());
        }
        errorCleansedData.setCleansedItems(Collections.emptyList());
        errorCleansedData.setStatus(cleansedStatus);
        errorCleansedData.setCleansingErrors(Map.of("error", errorMessage));
        errorCleansedData.setCleansedAt(OffsetDateTime.now());
        return cleansedDataStoreRepository.save(errorCleansedData);
    }

    /**
     * Removes placeholders and HTML tags from copy text.
     */
    private static String cleanseCopyText(String text) {
        if (text == null)
            return null;
        String cleansed = text;
        // Targeted replacements based on requested patterns
        cleansed = NBSP_PATTERN.matcher(cleansed).replaceAll(" ");
        cleansed = BR_PATTERN.matcher(cleansed).replaceAll(" ");
        cleansed = SOSUMI_PATTERN.matcher(cleansed).replaceAll(" ");
        // Remove nested URL macro patterns first to avoid partial leftovers
        cleansed = NESTED_URL_PATTERN.matcher(cleansed).replaceAll(" ");
        // Then remove regular URL macro patterns
        cleansed = URL_PATTERN.matcher(cleansed).replaceAll(" ");
        // Remove metadata macro
        cleansed = METADATA_PATTERN.matcher(cleansed).replaceAll(" ");

        // Strip HTML tags
        cleansed = cleansed.replaceAll("<[^>]+?>", " ");
        
        // Decode structural HTML entities (e.g. &nbsp;, &amp;, &#8209;)
        cleansed = org.jsoup.parser.Parser.unescapeEntities(cleansed, false);
        
        // Normalize unicode spacing and typography safety bounds
        cleansed = cleansed.replace('\u00A0', ' ');      // Non-breaking space
        cleansed = cleansed.replace('\u2011', '-');      // Non-breaking hyphen
        cleansed = cleansed.replace('\u202F', ' ');      // Narrow no-break space
        
        // Collapse whitespace
        cleansed = cleansed.replaceAll("\\s+", " ").trim();
        return cleansed;
    }

    /**
     * Returns true if the key represents an image-related node.
     */
    private boolean isImageNodeKey(String key) {
        if (key == null)
            return false;
        String lower = key.toLowerCase();
        return lower.contains("image") || lower.contains("backgroundimage") || lower.contains("icon")
                || lower.contains("graphic") || lower.contains("media") || lower.contains("poster")
                || lower.contains("banner") || lower.contains("logo") || lower.contains("thumbnail")
                || lower.contains("asset") || lower.contains("symbol");
    }

    /**
     * Flattens icon metadata into the facets map.
     */
    private void enrichFacetsWithIconProperties(JsonNode iconNode, String prefix, Facets targetFacets) {
        if (iconNode == null || iconNode.isNull())
            return;
        iconNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (key.startsWith("_") && !ICON_META_KEYS.contains(key))
                return;
            JsonNode value = entry.getValue();
            String facetKey = (prefix == null || prefix.isBlank()) ? key : prefix + "." + key;
            if (value.isValueNode()) {
                targetFacets.put(facetKey, value.asText());
            } else if (value.isObject()) {
                enrichFacetsWithIconProperties(value, facetKey, targetFacets);
            }
        });
    }

    /**
     * Flattens image metadata into the facets map.
     */
    private void enrichFacetsWithImageProperties(JsonNode imageNode, String prefix, Facets targetFacets) {
        if (imageNode == null || imageNode.isNull())
            return;
        imageNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (key.startsWith("_") && !IMAGE_META_KEYS.contains(key))
                return;
            JsonNode value = entry.getValue();
            String facetKey = (prefix == null || prefix.isBlank()) ? key : prefix + "." + key;
            if (value.isValueNode()) {
                targetFacets.put(facetKey, value.asText());
            } else if (value.isObject()) {
                enrichFacetsWithImageProperties(value, facetKey, targetFacets);
            } else if (value.isArray()) {
                int idx = 0;
                for (JsonNode element : value) {
                    String arrayKey = facetKey + "[" + idx + "]";
                    if (element.isValueNode()) {
                        targetFacets.put(arrayKey, element.asText());
                    } else if (element.isObject()) {
                        enrichFacetsWithImageProperties(element, arrayKey, targetFacets);
                    }
                    idx++;
                }
            }
        });
    }

    /**
     * Parses excluded item type prefixes from configuration.
     */
    private Set<String> parseExcludedItemTypes(String property) {
        if (property == null || property.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(property.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    /**
     * Determines whether a field should be excluded from enrichment.
     */
    private boolean isExcluded(String fieldKey) {
        if (fieldKey == null || excludedItemTypes.isEmpty()) {
            return false;
        }
        String lower = fieldKey.toLowerCase();
        return excludedItemTypes.stream().anyMatch(lower::startsWith);
    }

    private static class IngestionCounters {
        long copyFound = 0;
        long copyKept = 0;
        long analyticsFound = 0;
        long analyticsKept = 0;
        long copyBlankKept = 0;
        long analyticsBlankKept = 0;
    }

    /**
     * Attempts to derive a Logical Business Key from the payload to group versions
     * regardless of the transport channel.
     */
    private String deriveLogicalKey(String jsonPayload, String fallbackSource) {
        if (jsonPayload == null)
            return fallbackSource;
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            JsonNode context = root.path("context");
            if (!context.isMissingNode()) {
                JsonNode envelope = context.path("envelope");
                if (!envelope.isMissingNode()) {
                    String tenant = envelope.path("tenant").asText("");
                    String pageId = envelope.path("pageId").asText("");
                    String locale = envelope.path("locale").asText("");
                    if (!tenant.isEmpty() || !pageId.isEmpty() || !locale.isEmpty()) {
                        return String.join("|", tenant, pageId, locale);
                    }
                }
            }
            // Fallback for flat metadata
            String tenant = root.path("tenant").asText("");
            String pageId = root.path("pageId").asText("");
            String locale = root.path("locale").asText("");
            if (!tenant.isEmpty() || !pageId.isEmpty() || !locale.isEmpty()) {
                return String.join("|", tenant, pageId, locale);
            }
        } catch (Exception e) {
            logger.warn("Could not parse logical key from JSON payload, falling back to source URI.", e);
        }
        return fallbackSource;
    }
}