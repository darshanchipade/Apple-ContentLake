package com.apple.springboot.controller;

import com.apple.springboot.model.ContentChunkWithDistance;
import com.apple.springboot.model.ParsedQuery;
import com.apple.springboot.model.RefinementChip;
import com.apple.springboot.model.SearchRequest;
import com.apple.springboot.model.SearchResultDto;
import com.apple.springboot.model.SemanticSearchResponseDto;
import com.apple.springboot.model.SemanticSectionResultDto;
import com.apple.springboot.model.MediaItemDto;
import com.apple.springboot.repository.AssetMetadataOccurrenceRepository;
import com.apple.springboot.service.QueryParsingService;
import com.apple.springboot.service.RefinementService;
import com.apple.springboot.service.VectorSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Tag(name = "Search", description = "Vector search and refinement API endpoints")
public class SearchController {
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final int LOG_VALUE_LIMIT = 500;

    private final RefinementService refinementService;
    private final VectorSearchService vectorSearchService;
    private final QueryParsingService queryParsingService;
    private final AssetMetadataOccurrenceRepository assetMetadataOccurrenceRepository;

    /**
     * Wires refinement, vector search, and query parsing services for API
     * endpoints.
     */
    @Autowired
    public SearchController(RefinementService refinementService, VectorSearchService vectorSearchService,
            QueryParsingService queryParsingService,
            AssetMetadataOccurrenceRepository assetMetadataOccurrenceRepository) {
        this.refinementService = refinementService;
        this.vectorSearchService = vectorSearchService;
        this.queryParsingService = queryParsingService;
        this.assetMetadataOccurrenceRepository = assetMetadataOccurrenceRepository;
    }

    /**
     * Returns refinement chip suggestions for a given query string.
     */
    @Operation(summary = "Get refinement chips for a query", description = "Retrieves refinement chips (suggestions) based on the provided query. "
            +
            "These chips can be used to refine search queries.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved refinement chips", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RefinementChip.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping("/refine")
    public List<RefinementChip> getRefinementChips(
            @Parameter(description = "Search query to generate refinement chips for", required = true) @RequestParam String query,
            @Parameter(description = "Maximum number of chips to return (default 15)") @RequestParam(required = false) Integer limit)
            throws IOException {
        ParsedQuery parsedQuery = queryParsingService.parseQuery(query);
        return refinementService.getRefinementChips(parsedQuery, limit);
    }

    /**
     * Executes a vector search using the query and optional filters.
     */
    @Operation(summary = "Vector search endpoint", description = "Performs a vector search based on the provided query and filters. "
            +
            "Returns the top matching content chunks with their metadata. " +
            "Supports filtering by tags, keywords, original field name, and context.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved search results", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchResultDto.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })


    public List<SearchResultDto> search(@RequestBody SearchRequest request) throws IOException {
        ParsedQuery parsedQuery = queryParsingService.parseQuery(request.getQuery());

        log.info(
                "Search request query='{}', parsedQuery='{}', tags={}, keywords={}, role='{}', contextKeys={}",
                clip(request != null ? request.getQuery() : null),
                parsedQuery,
                request != null ? request.getTags() : null,
                request != null ? request.getKeywords() : null,
                request != null ? request.getOriginal_field_name() : null,
                request != null ? contextKeys(request.getContext()) : null);

        String queryStr = parsedQuery.getQuery();
        String fieldName = request.getOriginal_field_name() != null ? request.getOriginal_field_name()
                : parsedQuery.getOriginalFieldName();
        List<String> tags = request.getTags() != null ? request.getTags() : parsedQuery.getTags();
        List<String> keywords = request.getKeywords() != null ? request.getKeywords() : parsedQuery.getKeywords();
        Map<String, Object> context = request.getContext() != null && !request.getContext().isEmpty()
                ? request.getContext()
                : parsedQuery.getContextMap();
        String sectionFilter = parsedQuery.getSectionKeyFilter();

        // In the database schemas, UI properties like "page-title" or "headline" are
        // matched
        // using the "original_field_name" parameter (which matches DB key or _path leaf
        // nodes).
        // If the LLM correctly mapped it to "sectionName" in the context map, we'll
        // extract it
        // and override the fieldName for the VectorSearch backend, while removing it
        // from contextMap
        // to avoid incorrect exact JSONB matching.
        if (context != null && context.containsKey("sectionName") && (fieldName == null || fieldName.isBlank())) {
            fieldName = context.get("sectionName").toString();

            // Create a mutable copy to prevent mutating the original ParsedQuery map
            Map<String, Object> mutableContext = new java.util.HashMap<>(context);
            mutableContext.remove("sectionName"); // Remove to prevent an exact match JSONB query on the vector search
            context = mutableContext;
        }

        List<ContentChunkWithDistance> results = vectorSearchService.search(
                queryStr,
                fieldName,
                200, // limit
                tags,
                keywords,
                context,
                null, // threshold
                sectionFilter // sectionKeyFilter
        );

        // Transform the results into the DTO expected by the frontend
        return results.stream().map(result -> {
            return new SearchResultDto(
                    result.getContentChunk().getConsolidatedEnrichedSection().getCleansedText(),
                    result.getContentChunk().getConsolidatedEnrichedSection().getOriginalFieldName(),
                    result.getContentChunk().getConsolidatedEnrichedSection().getSectionUri());
        }).collect(Collectors.toList());
    }

    /**
     * Pure semantic search endpoint. Accepts a free-form natural language query and returns
     * the most semantically relevant content sections with their cleansed text and associated images.
     * No refinement chips, keyword filters, section filters, or page/role restrictions are applied.
     */
    @Operation(summary = "Semantic (natural language) search",
            description = "Converts the user query to a vector embedding and finds the most relevant " +
                    "enriched content sections. Returns cleansed text and any associated section images. " +
                    "No refinement chips or keyword filters are applied.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved semantic search results",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SearchResultDto.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping("/semantic-search")
    public SemanticSearchResponseDto semanticSearch(@RequestBody SearchRequest request) throws IOException {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return new SemanticSearchResponseDto("", List.of());
        }
        String rawQuery = request.getQuery().trim();
        log.info("Semantic search request query='{}'", clip(rawQuery));

        // Enterprise-grade hybrid semantic search pipeline (dense + lexical + llm rank + pack assembly)
        List<SemanticSectionResultDto> results = vectorSearchService.contextAwareHybridSearch(rawQuery, 20);

        // Collect unique section paths (the parent containers) to look up ALL associated images for these sections
        List<String> sectionPaths = results.stream()
                .flatMap(r -> r.getClusterPaths() != null && !r.getClusterPaths().isEmpty()
                        ? r.getClusterPaths().stream()
                        : java.util.stream.Stream.of(r.getSectionPath()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // Build a map of baseComponentPath -> list of MediaItemDto
        Map<String, List<MediaItemDto>> sectionMedia = new java.util.HashMap<>();
        Map<String, List<MediaItemDto>> pageMedia = new java.util.HashMap<>();
        if (!sectionPaths.isEmpty()) {
            try {
                // Strategy 1: Match by sectionPath (precise — works for JSON API content)
                List<Object[]> imageRows = assetMetadataOccurrenceRepository.findImageUrlsWithUrisBySectionPaths(sectionPaths);

                // Strategy 2: Match by sourceUri (page URL) — fallback for HTML content sections.
                // IMPORTANT: asset_metadata_occurrence.source_uri stores the raw sourceUri from
                // rawDataStore (e.g. 'html-extraction:https://...'), while consolidated_enriched_sections
                // also stores it with the prefix. We query with BOTH forms (prefixed and stripped) to
                // match regardless of which format was ingested.
                List<String> sourceUris = new java.util.ArrayList<>();
                results.stream()
                        .map(SemanticSectionResultDto::getSourceUrl)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .forEach(rawUri -> {
                            sourceUris.add(rawUri); // always include as-is
                            // Also add the stripped version if it has a scheme prefix
                            int colonIdx = rawUri.indexOf(':');
                            if (colonIdx > 0 && !rawUri.startsWith("http")) {
                                String rest = rawUri.substring(colonIdx + 1);
                                if (rest.startsWith("http") && !sourceUris.contains(rest)) {
                                    sourceUris.add(rest);
                                }
                            }
                        });
                if (!sourceUris.isEmpty()) {
                    List<Object[]> sourceUriRows = assetMetadataOccurrenceRepository.findImageUrlsWithUrisBySourceUris(sourceUris);
                    // Merge the two result sets; deduplicate by URL
                    java.util.Set<String> seenUrls = imageRows.stream()
                            .map(r -> r[4] != null ? r[4].toString() : null)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toSet());
                    for (Object[] row : sourceUriRows) {
                        String url = row[4] != null ? row[4].toString() : null;
                        if (url != null && seenUrls.add(url)) {
                            imageRows = new java.util.ArrayList<>(imageRows);
                            imageRows.add(row);
                        }
                    }
                }

                for (Object[] row : imageRows) {
                    // Fall back to sectionPath if sectionUri is unpopulated (typical for AEM JSON content)
                    String imageSectionUri = row[1] != null ? row[1].toString() : (row[0] != null ? row[0].toString() : null);
                    String type = row[2] != null ? row[2].toString() : "image";
                    String label = row[3] != null ? row[3].toString() : "";
                    String url = row[4] != null ? normalizeMediaUrl(row[4].toString()) : null;

                    if (imageSectionUri != null && url != null) {
                        // Compute base component path for this image to match the text blocks
                        String baseComponentPath = VectorSearchService.getBaseComponentPath(imageSectionUri);
                        sectionMedia.computeIfAbsent(baseComponentPath, k -> new java.util.ArrayList<>())
                                .add(new MediaItemDto(type, label, url));
                    }
                }

                // Build page-level media fallback map keyed by source URI.
                // HTML-extracted sections store images by their slot path (e.g. html-content-section[0]/hero/...)
                // which rarely matches the text section's slot path. Bucketing by page URL allows any
                // image from the same page to appear on any section result card as a fallback.
                pageMedia = new java.util.HashMap<>();
                if (!sourceUris.isEmpty()) {
                    List<Object[]> s2Rows = assetMetadataOccurrenceRepository.findImageUrlsWithUrisBySourceUris(sourceUris);
                    for (Object[] row : s2Rows) {
                        // row[5] is the newly exposed o.sourceUri
                        String srcUri = row.length > 5 && row[5] != null ? row[5].toString() : null;
                        String type   = row[2] != null ? row[2].toString() : "image";
                        String label  = row[3] != null ? row[3].toString() : "";
                        String url    = row[4] != null ? normalizeMediaUrl(row[4].toString()) : null;
                        if (srcUri != null && url != null) {
                            pageMedia.computeIfAbsent(srcUri, k -> new java.util.ArrayList<>())
                                     .add(new MediaItemDto(type, label, url));
                            // Also add under stripped form (without "html-extraction:" prefix)
                            int col = srcUri.indexOf(':');
                            if (col > 0 && !srcUri.startsWith("http")) {
                                String stripped = srcUri.substring(col + 1);
                                pageMedia.computeIfAbsent(stripped, k -> new java.util.ArrayList<>())
                                         .add(new MediaItemDto(type, label, url));
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.warn("Unable to fetch section images for semantic search. Continuing without images. Reason: {}", e.getMessage());
            }
        }


        for (SemanticSectionResultDto result : results) {
            String uri = result.getSectionUri();
            // Primary: exact structural match via getBaseComponentPath
            List<MediaItemDto> media = sectionMedia.get(uri);
            
            // Secondary Primary: look for any exact matching image belonging to sibling fragments natively in the UI cluster
            if ((media == null || media.isEmpty()) && result.getClusterPaths() != null) {
                for (String cpath : result.getClusterPaths()) {
                    List<MediaItemDto> cMedia = sectionMedia.get(cpath);
                    if (cMedia != null && !cMedia.isEmpty()) {
                        media = cMedia;
                        break;
                    }
                }
            }

            // Fallback: if no direct image match within the strict AI cluster bounds, show the best images from anywhere on the same page.
            if ((media == null || media.isEmpty())) {
                String pageUrl = result.getSourceUrl();
                if (pageUrl != null) {
                    media = pageMedia.get(pageUrl);
                    if (media == null || media.isEmpty()) {
                        // Try stripped form without "html-extraction:" prefix
                        int col = pageUrl.indexOf(':');
                        if (col > 0 && !pageUrl.startsWith("http")) {
                            media = pageMedia.get(pageUrl.substring(col + 1));
                        }
                    }
                }
            }
            result.setMedia(media != null ? media : List.of());
            
            // Clean up the UI path display for the frontend
            String fullSectionPath = result.getSectionPath();
            if (fullSectionPath != null && fullSectionPath.contains("/content/dam/applecom-cms/live/en_US")) {
                fullSectionPath = fullSectionPath.replace("/content/dam/applecom-cms/live/en_US", "");
            }
            if (fullSectionPath == null || fullSectionPath.isBlank()) fullSectionPath = uri;
            result.setSectionPath(fullSectionPath);
        }

        return new SemanticSearchResponseDto(rawQuery, results);
    }

    /**
     * Trims and truncates values to keep log lines readable.
     */
    private String clip(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= LOG_VALUE_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LOG_VALUE_LIMIT) + "...";
    }

    /**
     * Normalizes media URLs for frontend rendering.
     * - "/assets-www/..." -> "https://www.apple.com/assets-www/..."
     * - "//cdn..."        -> "https://cdn..."
     */
    private String normalizeMediaUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("/")) {
            return "https://www.apple.com" + trimmed;
        }
        return trimmed;
    }

    /**
     * Extracts top-level context keys for safe logging.
     */
    private List<String> contextKeys(java.util.Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return List.of();
        }
        return context.keySet().stream().map(String::valueOf).collect(Collectors.toList());
    }
}