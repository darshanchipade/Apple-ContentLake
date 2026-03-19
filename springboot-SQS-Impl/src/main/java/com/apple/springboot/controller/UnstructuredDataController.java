package com.apple.springboot.controller;

import com.apple.springboot.dto.UnstructuredIngestionPayload;
import com.apple.springboot.dto.UnstructuredUrlPayload;
import com.apple.springboot.service.HtmlTransformationAdapter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ingest/unstructured")
@Tag(name = "Unstructured Data Ingestion", description = "Endpoints for ingesting raw HTML and URLs for LLM Vectorization")
@RequiredArgsConstructor
public class UnstructuredDataController {

    private static final Logger logger = LoggerFactory.getLogger(UnstructuredDataController.class);

    private final HtmlTransformationAdapter htmlTransformationAdapter;

    @PostMapping("/url")
    @Operation(summary = "Ingest a live Webpage URL", description = "Asynchronously fetches the live DOM, parses semantic structure, and ingests.")
    public ResponseEntity<Map<String, Object>> ingestLiveUrl(@RequestBody UnstructuredUrlPayload payload) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode structuredPayload = htmlTransformationAdapter
                    .processLiveUrl(payload);
            return ResponseEntity.ok(
                    Map.of("status", "ACCEPTED", "message", "Live URL extraction succeeded.", "processId",
                            structuredPayload.get("cleansedId").asText(), "body", structuredPayload));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/raw")
    @Operation(summary = "Ingest raw HTML string payload", description = "Receives explicit HTML string payload from SQS or crawler scraping scripts.")
    public ResponseEntity<Map<String, Object>> ingestRawHtml(@RequestBody UnstructuredIngestionPayload payload) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode structuredPayload = htmlTransformationAdapter
                    .processRawHtml(payload);
            return ResponseEntity.ok(
                    Map.of("status", "ACCEPTED", "message", "Raw HTML extraction succeeded.", "processId",
                            structuredPayload.get("cleansedId").asText(), "body", structuredPayload));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }
}
