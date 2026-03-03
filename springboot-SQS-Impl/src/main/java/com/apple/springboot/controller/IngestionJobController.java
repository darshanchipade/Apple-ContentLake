package com.apple.springboot.controller;

import com.apple.springboot.model.IngestionJob;
import com.apple.springboot.repository.IngestionJobRepository;
import com.apple.springboot.model.CleansedDataStore;
import com.apple.springboot.model.RawDataStore;
import com.apple.springboot.repository.CleansedDataStoreRepository;
import com.apple.springboot.repository.RawDataStoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/ingestion")
public class IngestionJobController {

    private final IngestionJobRepository ingestionJobRepository;
    private final RawDataStoreRepository rawDataStoreRepository;
    private final CleansedDataStoreRepository cleansedDataStoreRepository;
    private final ObjectMapper objectMapper;

    public IngestionJobController(IngestionJobRepository ingestionJobRepository,
            RawDataStoreRepository rawDataStoreRepository,
            CleansedDataStoreRepository cleansedDataStoreRepository,
            ObjectMapper objectMapper) {
        this.ingestionJobRepository = ingestionJobRepository;
        this.rawDataStoreRepository = rawDataStoreRepository;
        this.cleansedDataStoreRepository = cleansedDataStoreRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieves the chronological history of upload jobs.
     * In a future Persona iteration, this can be filtered by the Auth token.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getUploadHistory(@RequestParam(required = false) String username) {

        List<IngestionJob> jobs = (username != null && !username.isBlank())
                ? ingestionJobRepository.findByUsernameOrderByCreatedAtDesc(username)
                : ingestionJobRepository.findAllByOrderByCreatedAtDesc();

        // Map to match the exact shape expected by the React UI (UploadHistoryItem)
        List<Map<String, Object>> results = jobs.stream().map(job -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", job.getId().toString());
            map.put("name", job.getFileName());
            map.put("size", job.getFileSize());
            map.put("type", "application/json");
            map.put("source", job.getSourceChannel());
            map.put("status", job.getStatus());
            map.put("createdAt", job.getCreatedAt().toInstant().toEpochMilli());
            map.put("username", job.getUsername());

            if (job.getRawDataId() != null) {
                map.put("sourceIdentifier", job.getRawDataId().toString());

                // Dynamically fetch extended metadata
                Optional<RawDataStore> rawOpt = rawDataStoreRepository.findById(job.getRawDataId());
                if (rawOpt.isPresent()) {
                    RawDataStore raw = rawOpt.get();
                    map.put("backendStatus", raw.getStatus());

                    // Parse "tenant|pageId|locale" from LogicalKey
                    String logicalKey = raw.getLogicalKey();
                    if (logicalKey != null && logicalKey.contains("|")) {
                        String[] parts = logicalKey.split("\\|", -1);
                        if (parts.length >= 3) {
                            if (!parts[1].isEmpty())
                                map.put("pageId", parts[1]);
                            if (!parts[2].isEmpty())
                                map.put("locale", parts[2]);
                        }
                    }

                    // Fallback to deeply scanning the sourceMetadata JSON block if missing
                    if (!map.containsKey("locale") || !map.containsKey("pageId")) {
                        try {
                            String metadata = raw.getSourceMetadata();
                            if (metadata != null && !metadata.isEmpty()) {
                                JsonNode root = objectMapper.readTree(metadata);
                                extractMetadata(root, map, new int[] { 0 });
                            }
                        } catch (Exception e) {
                            // ignore parsing constraints
                        }
                    }

                    // Final fallback to filename scanning for locale
                    if (!map.containsKey("locale") && job.getFileName() != null) {
                        String localeFromFilename = inferLocaleFromFilename(job.getFileName());
                        if (localeFromFilename != null) {
                            map.put("locale", localeFromFilename);
                        }
                    }

                    // Find Cleansed Record
                    Optional<CleansedDataStore> cleansedOpt = cleansedDataStoreRepository
                            .findTopByRawDataIdOrderByCleansedAtDesc(raw.getId());
                    if (cleansedOpt.isPresent()) {
                        map.put("cleansedId", cleansedOpt.get().getId().toString());
                        // OVERRIDE: Prioritize CleansedDataStore's final enrichment status over
                        // RawDataStore's CLEANSING_COMPLETE
                        map.put("backendStatus", cleansedOpt.get().getStatus());
                    }
                }
            }

            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }

    private void extractMetadata(JsonNode node, Map<String, Object> map, int[] count) {
        if (node == null || count[0] > 1500 || (map.containsKey("locale") && map.containsKey("pageId"))) {
            return;
        }
        count[0]++;
        if (node.isObject()) {
            if (!map.containsKey("locale")) {
                for (String key : new String[] { "locale", "localeCode", "locale_code", "languageLocale",
                        "language_locale" }) {
                    if (node.hasNonNull(key) && node.get(key).isTextual()) {
                        map.put("locale", node.get(key).asText());
                        break;
                    }
                }
            }
            if (!map.containsKey("pageId")) {
                for (String key : new String[] { "pageId", "page_id", "pageID" }) {
                    if (node.hasNonNull(key) && node.get(key).isTextual()) {
                        map.put("pageId", node.get(key).asText());
                        break;
                    }
                }
            }
            if (map.containsKey("locale") && map.containsKey("pageId")) {
                return;
            }
            node.elements().forEachRemaining(child -> extractMetadata(child, map, count));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> extractMetadata(child, map, count));
        }
    }

    private String inferLocaleFromFilename(String filename) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[_\\-]([a-z]{2}[_\\-][A-Za-z]{2})(?=\\.)")
                .matcher(filename);
        if (m.find()) {
            return m.group(1).replace("_", "-");
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("[_\\-]([a-z]{2})(?=\\.)").matcher(filename);
        if (m2.find()) {
            return m2.group(1);
        }
        return null;
    }
}
