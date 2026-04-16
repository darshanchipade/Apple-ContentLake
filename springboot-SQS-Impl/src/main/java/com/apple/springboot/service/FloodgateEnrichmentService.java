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
import java.util.Objects;

/**
 *  Floodgate client for Anthropic messages API.
 * Higher-level parsing remains in BedrockEnrichmentService.
 */
@Service
public class FloodgateEnrichmentService {

    private static final Logger logger = LoggerFactory.getLogger(FloodgateEnrichmentService.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String messagesPath;
    private final String modelId;
    private final String userAgent;
    private final String projectToken;
    private final String authCommand;
    private final int timeoutMs;

    public FloodgateEnrichmentService(
            ObjectMapper objectMapper,
            @Value("${llm.floodgate.base-url:https://floodgate.g.apple.com}") String baseUrl,
            @Value("${llm.floodgate.messages-path:/api/anthropic/v1/messages}") String messagesPath,
            @Value("${llm.floodgate.model:}") String modelId,
            @Value("${llm.floodgate.user-agent:AppleContentLake/1.0}") String userAgent,
            @Value("${llm.floodgate.project-token:}") String projectToken,
            @Value("${llm.floodgate.auth.command:}") String authCommand,
            @Value("${llm.floodgate.timeout-ms:120000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.messagesPath = normalizePath(messagesPath);
        this.modelId = modelId != null ? modelId.trim() : "";
        this.userAgent = userAgent != null ? userAgent.trim() : "AppleContentLake/1.0";
        this.projectToken = projectToken != null ? projectToken.trim() : "";
        this.authCommand = authCommand != null ? authCommand.trim() : "";
        this.timeoutMs = Math.max(5000, timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    public String invokeChatForText(String content, int maxTokens) {
        ensureConfigured();
        String token = fetchTokenFromCommand();
        String endpoint = baseUrl + messagesPath;

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", modelId);
            payload.put("max_tokens", Math.max(64, maxTokens));
            ArrayNode messages = payload.putArray("messages");
            ObjectNode user = objectMapper.createObjectNode();
            user.put("role", "user");
            user.put("content", Objects.toString(content, ""));
            messages.add(user);

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

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Floodgate returned HTTP " + response.statusCode() + ": " + clip(response.body(), 600));
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode contentBlock = responseJson.path("content");
            if (contentBlock.isArray() && contentBlock.size() > 0) {
                String text = contentBlock.get(0).path("text").asText("").trim();
                return stripCodeFences(text);
            }
            throw new RuntimeException("Floodgate response missing content text block");
        } catch (Exception e) {
            throw new RuntimeException("Floodgate invoke failed: " + e.getMessage(), e);
        }
    }

    public String getConfiguredModelId() {
        return modelId;
    }

    private void ensureConfigured() {
        if (modelId.isBlank()) {
            throw new IllegalStateException("llm.floodgate.model must be configured.");
        }
        if (authCommand.isBlank()) {
            throw new IllegalStateException("llm.floodgate.auth.command must be configured.");
        }
    }

    private String fetchTokenFromCommand() {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", authCommand)
                    .redirectErrorStream(true)
                    .start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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

            // Prefer oauth-id style line if present; else use last token from last non-empty line.
            String selected = lines.get(lines.size() - 1);
            for (String line : lines) {
                if (line.toLowerCase(Locale.ROOT).contains("oauth-id")) {
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
            logger.error("Failed to fetch Floodgate auth token: {}", e.getMessage());
            throw new RuntimeException("Floodgate auth token fetch failed", e);
        }
    }

    private String stripCodeFences(String text) {
        if (text == null) return "";
        String out = text.trim();
        if (out.startsWith("```json")) {
            out = out.substring(7).trim();
        } else if (out.startsWith("```")) {
            out = out.substring(3).trim();
        }
        if (out.endsWith("```")) {
            out = out.substring(0, out.length() - 3).trim();
        }
        return out;
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
        if (!s.startsWith("/")) s = "/" + s;
        return s;
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

