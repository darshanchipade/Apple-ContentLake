package com.apple.springboot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Floodgate client for Google/Vertex style text embedding predict endpoint.
 */
@Service
public class FloodgateEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(FloodgateEmbeddingService.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String predictPathTemplate;
    private final String modelId;
    private final String userAgent;
    private final String projectToken;
    private final String authCommand;
    private final int timeoutMs;
    private final String taskType;
    private final int outputDimensionality;
    private final int expectedVectorDimension;
    private final boolean autoPadToExpectedDimension;

    public FloodgateEmbeddingService(
            ObjectMapper objectMapper,
            @Value("${embedding.floodgate.base-url:https://floodgate.g.apple.com}") String baseUrl,
            @Value("${embedding.floodgate.predict-path:/api/gemini/v1/publishers/google/models/{embedding_model_id}:predict}") String predictPathTemplate,
            @Value("${embedding.floodgate.model:text-embedding-005}") String modelId,
            @Value("${embedding.floodgate.user-agent:AppleContentLake/1.0}") String userAgent,
            @Value("${embedding.floodgate.project-token:}") String projectToken,
            @Value("${embedding.floodgate.auth.command:}") String authCommand,
            @Value("${embedding.floodgate.timeout-ms:120000}") int timeoutMs,
            @Value("${embedding.floodgate.task-type:RETRIEVAL_DOCUMENT}") String taskType,
            @Value("${embedding.floodgate.output-dimensionality:0}") int outputDimensionality,
            @Value("${app.embedding.vector-dimension:1024}") int expectedVectorDimension,
            @Value("${embedding.floodgate.auto-pad-to-dimension:true}") boolean autoPadToExpectedDimension) {
        this.objectMapper = objectMapper;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.predictPathTemplate = normalizePath(predictPathTemplate);
        this.modelId = modelId != null ? modelId.trim() : "";
        this.userAgent = userAgent != null ? userAgent.trim() : "AppleContentLake/1.0";
        this.projectToken = projectToken != null ? projectToken.trim() : "";
        this.authCommand = authCommand != null ? authCommand.trim() : "";
        this.timeoutMs = Math.max(5000, timeoutMs);
        this.taskType = taskType != null ? taskType.trim() : "RETRIEVAL_DOCUMENT";
        this.outputDimensionality = Math.max(0, outputDimensionality);
        this.expectedVectorDimension = Math.max(1, expectedVectorDimension);
        this.autoPadToExpectedDimension = autoPadToExpectedDimension;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    public String getConfiguredModelId() {
        return modelId;
    }

    public float[] generateEmbedding(String text) throws IOException {
        ensureConfigured();
        String token = fetchTokenFromCommand();
        String endpoint = baseUrl + resolvePredictPath();
        logger.info("Generating embedding using provider='google-vertex' model='{}' endpoint='{}'", modelId, endpoint);

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            ArrayNode instances = payload.putArray("instances");
            ObjectNode instance = objectMapper.createObjectNode();
            instance.put("content", text == null ? "" : text);
            if (!taskType.isBlank()) {
                instance.put("task_type", taskType);
            }
            instances.add(instance);
            if (outputDimensionality > 0) {
                ObjectNode params = payload.putObject("parameters");
                params.put("outputDimensionality", outputDimensionality);
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));

            if (!projectToken.isBlank()) {
                builder.header("X-Floodgate-Project-Token", projectToken);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Floodgate embedding returned HTTP " + response.statusCode() + ": " + clip(response.body(), 600));
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            float[] vector = reconcileEmbeddingDimension(extractEmbeddingVector(responseJson));
            logger.info("Embedding created using provider='google-vertex' model='{}' dimension={}",
                    modelId, vector.length);
            return vector;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Floodgate embedding invoke failed: " + e.getMessage(), e);
        }
    }

    private float[] extractEmbeddingVector(JsonNode root) throws IOException {
        JsonNode values = root.path("predictions").path(0).path("embeddings").path("values");
        if (!values.isArray()) {
            // fallback for slightly different wrapper shapes
            values = root.path("predictions").path(0).path("values");
        }
        if (!values.isArray()) {
            values = root.path("embedding");
        }
        if (!values.isArray()) {
            throw new IOException("Floodgate embedding response missing vector values array.");
        }

        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    private float[] reconcileEmbeddingDimension(float[] vector) throws IOException {
        if (vector.length == expectedVectorDimension) {
            return vector;
        }

        if (vector.length < expectedVectorDimension && autoPadToExpectedDimension) {
            float[] padded = new float[expectedVectorDimension];
            System.arraycopy(vector, 0, padded, 0, vector.length);
            logger.warn(
                    "Embedding dimension {} is smaller than expected {}. Auto-padding with zeros (embedding.floodgate.auto-pad-to-dimension=true).",
                    vector.length, expectedVectorDimension);
            return padded;
        }

        throw new IOException("Embedding dimension mismatch. Received " + vector.length
                + " but app.embedding.vector-dimension is " + expectedVectorDimension
                + ". Update dimension/model configuration to match database vector column.");
    }

    private void ensureConfigured() {
        if (modelId.isBlank()) {
            throw new IllegalStateException("embedding.floodgate.model must be configured.");
        }
        if (authCommand.isBlank()) {
            throw new IllegalStateException(
                    "embedding.floodgate.auth.command must be configured (or set FLOODGATE_EMBEDDING_AUTH_COMMAND / FLOODGATE_AUTH_COMMAND).");
        }
    }

    private String resolvePredictPath() {
        return predictPathTemplate.replace("{embedding_model_id}", modelId);
    }

    private String fetchTokenFromCommand() {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", authCommand)
                    .redirectErrorStream(true)
                    .start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0 || lines.isEmpty()) {
                throw new RuntimeException("Auth command failed with exit " + exit + ".");
            }

            String selected = lines.get(lines.size() - 1);
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("oauth-id") || lower.contains("id-token")) {
                    selected = line;
                    break;
                }
            }

            String[] parts = selected.split("\\s+");
            String token = parts[parts.length - 1].trim();
            if (token.isBlank()) {
                throw new RuntimeException("Auth command returned empty token.");
            }
            return token;
        } catch (Exception e) {
            logger.error("Failed to fetch Floodgate embedding auth token: {}", e.getMessage());
            throw new RuntimeException("Floodgate embedding auth token fetch failed", e);
        }
    }

    private String normalizeBaseUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String normalizePath(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s;
    }

    private String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
