package com.apple.springboot.service;

import com.apple.springboot.model.EnrichmentContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

import java.time.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class BedrockEnrichmentService {

    private static final Logger logger = LoggerFactory.getLogger(BedrockEnrichmentService.class);
    private final BedrockRuntimeClient bedrockClient;
    private final ObjectMapper objectMapper;
    private final String bedrockModelId;
    private final String bedrockRegion;
    private final String embeddingModelId;
    private final int bedrockMaxTokens;

    @Value("${app.enrichment.computeItemVector:false}")
    private boolean computeItemVector;

    /**
     * Initializes the Bedrock client and model configuration.
     */
    @Autowired
    public BedrockEnrichmentService(ObjectMapper objectMapper,
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.bedrock.modelId}") String modelId,
            @Value("${aws.bedrock.embeddingModelId}") String embeddingModelId,
            @Value("${app.bedrock.maxTokens:512}") int bedrockMaxTokens) {
        this.objectMapper = objectMapper;
        this.bedrockRegion = region;
        this.bedrockModelId = modelId;
        this.embeddingModelId = embeddingModelId;
        this.bedrockMaxTokens = Math.max(128, bedrockMaxTokens);

        if (region == null) {
            logger.error("AWS Region for Bedrock is null. Cannot initialize BedrockRuntimeClient.");
            throw new IllegalArgumentException("AWS Region for Bedrock must not be null.");
        }

        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.of(this.bedrockRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMinutes(10))
                        .apiCallAttemptTimeout(Duration.ofMinutes(10))
                        .build())
                .build();
        logger.info("BedrockEnrichmentService initialized with region: {} and model ID: {}", this.bedrockRegion,
                this.bedrockModelId);
    }

    /**
     * Returns the configured Bedrock model identifier.
     */
    public String getConfiguredModelId() {
        return this.bedrockModelId;
    }

    /**
     * Generates an embedding vector using the configured embedding model.
     */
    public float[] generateEmbedding(String text) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("inputText", text);

        String payloadJson = objectMapper.writeValueAsString(payload);
        SdkBytes body = SdkBytes.fromUtf8String(payloadJson);

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(embeddingModelId)
                .contentType("application/json")
                .accept("application/json")
                .body(body)
                .build();

        try {
            InvokeModelResponse response = invokeWithRetry(request, true);
            JsonNode responseJson = objectMapper.readTree(response.body().asUtf8String());
            JsonNode embeddingNode = responseJson.get("embedding");
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = embeddingNode.get(i).floatValue();
            }
            return embedding;
        } catch (ThrottledException te) {
            // IMPORTANT: bubble up so SQS listener does NOT delete the message
            throw te;
        } catch (BedrockRuntimeException e) {
            logger.error("Bedrock API error during embedding generation: {}", e.awsErrorDetails().errorMessage(), e);
            throw new IOException("Bedrock API error during embedding generation.", e);
        }
    }

    /**
     * Builds the prompt template for Bedrock enrichment calls.
     */
    private String createEnrichmentPrompt(JsonNode itemContent, EnrichmentContext context)
            throws JsonProcessingException {
        String cleansedContent = itemContent.path("cleansedContent").asText("");
        String contextJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);

        String promptTemplate = "You are an expert content analyst. Analyze the text and return enrichments as a single JSON object ONLY (no markdown, no code fences, no commentary).\n"
                +
                "\n" +
                "Input content:\n<content>\n%s\n</content>\n" +
                "\n" +
                "Context (JSON):\n<context>\n%s\n</context>\n" +
                "\n" +
                "Rules:\n" +
                "- Output MUST be exactly one JSON object with one top-level key: \"standardEnrichments\".\n" +
                "- Do not include any text before/after the JSON. No backticks. No explanations.\n" +
                "- Use context (e.g., pathHierarchy, locale, facets, model) to tailor results.\n" +
                "- Keep summary ≤ 2 sentences; do not copy the content verbatim.\n" +
                "- keywords/tags: lowercase, unique, ≤ 10 keywords, ≤ 5 tags; no stopwords.\n" +
                "- sentiment ∈ {\"positive\",\"neutral\",\"negative\"}.\n" +
                "- classification: short category like \"product description\", \"legal disclaimer\", \"promotional heading\", etc.\n"
                +
                "- If uncertain, return best-effort values; never null; use [] for empty arrays and \"unknown\" when needed.\n"
                +
                "\n" +
                "Output JSON schema (example shape; values must reflect the input):\n" +
                "{\n" +
                "  \"standardEnrichments\": {\n" +
                "    \"summary\": \"\",\n" +
                "    \"keywords\": [\"\"],\n" +
                "    \"sentiment\": \"\",\n" +
                "    \"classification\": \"\",\n" +
                "    \"tags\": [\"\"]\n" +
                "  }\n" +
                "}";
        return String.format(promptTemplate, cleansedContent, contextJson);
    }

    /**
     * Invokes Bedrock to enrich a single content item with summary, tags, and
     * metadata.
     */
    public Map<String, Object> enrichItem(JsonNode itemContent, EnrichmentContext context) {
        String effectiveModelId = this.bedrockModelId;
        String sourcePath = (context != null && context.getEnvelope() != null) ? context.getEnvelope().getSourcePath()
                : "Unknown";
        logger.info("Starting enrichment for item using model: {}. Item path: {}", effectiveModelId, sourcePath);

        Map<String, Object> results = new HashMap<>();
        results.put("enrichedWithModel", effectiveModelId);

        String cleansedContent = itemContent.path("cleansedContent").asText("");
        if (cleansedContent.isBlank() || cleansedContent.length() <= 3) {
            logger.info("Content is nearly empty (length: {}). Bypassing LLM and returning default enrichment for: {}", cleansedContent.length(), sourcePath);
            Map<String, Object> defaultEnrichments = new HashMap<>();
            defaultEnrichments.put("summary", "");
            defaultEnrichments.put("keywords", new ArrayList<>());
            defaultEnrichments.put("sentiment", "neutral");
            defaultEnrichments.put("classification", "unknown");
            defaultEnrichments.put("tags", new ArrayList<>());
            
            results.put("standardEnrichments", defaultEnrichments);
            return results;
        }

        try {
            String prompt = createEnrichmentPrompt(itemContent, context);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("anthropic_version", "bedrock-2023-05-31");
            payload.put("max_tokens", bedrockMaxTokens);
            List<ObjectNode> messages = new ArrayList<>();
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);
            payload.set("messages", objectMapper.valueToTree(messages));

            String payloadJson = objectMapper.writeValueAsString(payload);
            SdkBytes body = SdkBytes.fromUtf8String(payloadJson);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(bedrockModelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(body)
                    .build();

            InvokeModelResponse response = invokeWithRetry(request, false);
            String responseBodyString = response.body().asUtf8String();
            JsonNode responseJson = objectMapper.readTree(responseBodyString);
            JsonNode contentBlock = responseJson.path("content");

            if (contentBlock.isArray() && contentBlock.size() > 0) {
                String textContent = contentBlock.get(0).path("text").asText("").trim();
                String jsonCandidate = sanitizeJsonObjectCandidate(textContent);
                Map<String, Object> aiResults = parseMapWithRecovery(jsonCandidate);

                if (aiResults == null) {
                    String repaired = attemptEnrichmentJsonRepair(jsonCandidate, cleansedContent, context);
                    aiResults = parseMapWithRecovery(repaired);
                }

                if (aiResults != null) {
                    aiResults.put("enrichedWithModel", effectiveModelId);
                    return aiResults;
                }

                logger.error("Failed to parse JSON content from Bedrock response: {}",
                        clipForLog(textContent, 2000));
                results.put("error", "Failed to parse JSON from Bedrock response");
                results.put("raw_bedrock_response", clipForLog(textContent, 2000));
                return results;
            } else {
                logger.error("Bedrock response does not contain expected content block or content is not an array.");
                results.put("error", "Bedrock response structure unexpected");
                results.put("raw_bedrock_response", responseBodyString);
            }
        } catch (ThrottledException te) {
            // Crucial: let the caller (SQS listener) decide retry/delete; do not swallow
            throw te;
        } catch (BedrockRuntimeException e) {
            logger.error("Bedrock API error during enrichment for model {}: {}", effectiveModelId,
                    e.awsErrorDetails().errorMessage(), e);
            results.put("error", "Bedrock API error: " + e.awsErrorDetails().errorMessage());
            results.put("aws_error_code", e.awsErrorDetails().errorCode());
            return results;
        } catch (Exception e) {
            logger.error("Unexpected error during Bedrock enrichment for model {}: {}", effectiveModelId,
                    e.getMessage(), e);
            results.put("error", "Unexpected error during enrichment: " + e.getMessage());
            return results;
        }
        return results;
    }

    private String sanitizeJsonObjectCandidate(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("```json")) {
            text = text.substring(7).trim();
        } else if (text.startsWith("```")) {
            text = text.substring(3).trim();
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3).trim();
        }

        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            text = text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private Map<String, Object> parseMapWithRecovery(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;

        try {
            return objectMapper.readValue(candidate, new TypeReference<>() {});
        } catch (JsonProcessingException ignored) {
        }

        com.fasterxml.jackson.databind.ObjectMapper relaxed = new com.fasterxml.jackson.databind.ObjectMapper();
        relaxed.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
        relaxed.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        relaxed.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        relaxed.configure(com.fasterxml.jackson.core.JsonParser.Feature.IGNORE_UNDEFINED, true);

        String[] attempts = new String[] { candidate, candidate + "}", candidate + "}}", candidate + "\"}}" };
        for (String attempt : attempts) {
            try {
                Map<String, Object> parsed = relaxed.readValue(attempt, new TypeReference<>() {});
                logger.warn("Enrichment JSON recovered via relaxed/healed parser.");
                return parsed;
            } catch (JsonProcessingException ignored) {
            }
        }
        return null;
    }

    private String attemptEnrichmentJsonRepair(String brokenJson, String cleansedContent, EnrichmentContext context) {
        try {
            String ctx = context != null ? objectMapper.writeValueAsString(context) : "{}";
            String prompt = "You are a strict JSON repair engine.\n" +
                    "Return ONLY valid JSON object with this exact top-level key: standardEnrichments.\n" +
                    "Inside standardEnrichments keep keys: summary, keywords, sentiment, classification, tags.\n" +
                    "No markdown, no commentary.\n\n" +
                    "Input content:\n" + cleansedContent + "\n\n" +
                    "Context:\n" + ctx + "\n\n" +
                    "Broken response to repair:\n" + brokenJson;
            String repaired = invokeChatForText(prompt, Math.min(1024, Math.max(256, bedrockMaxTokens)));
            return sanitizeJsonObjectCandidate(repaired);
        } catch (Exception e) {
            logger.warn("Enrichment JSON repair pass failed: {}", e.getMessage());
            return null;
        }
    }

    private String clipForLog(String raw, int maxLen) {
        if (raw == null) return "";
        return raw.length() <= maxLen ? raw : raw.substring(0, maxLen) + "...";
    }

    /**
     * Generic chat invoke for free-form prompts. Returns the first text block from
     * the response.
     * Allows an optional max token override to fit prompt sizes.
     */
    public String invokeChatForText(String content, Integer overrideMaxTokens) {
        String effectiveModelId = this.bedrockModelId;
        int maxTokens = overrideMaxTokens != null ? Math.max(64, overrideMaxTokens) : this.bedrockMaxTokens;

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("anthropic_version", "bedrock-2023-05-31");
            payload.put("max_tokens", maxTokens);
            List<ObjectNode> messages = new ArrayList<>();
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", content);
            messages.add(userMessage);
            payload.set("messages", objectMapper.valueToTree(messages));

            String payloadJson = objectMapper.writeValueAsString(payload);
            SdkBytes body = SdkBytes.fromUtf8String(payloadJson);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(effectiveModelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(body)
                    .build();

            InvokeModelResponse response = invokeWithRetry(request, false);
            String responseBodyString = response.body().asUtf8String();
            JsonNode responseJson = objectMapper.readTree(responseBodyString);
            JsonNode contentBlock = responseJson.path("content");

            if (contentBlock.isArray() && contentBlock.size() > 0) {
                String textContent = contentBlock.get(0).path("text").asText("").trim();

                // Common case: models sometimes wrap with ```json fences
                if (textContent.startsWith("```json")) {
                    textContent = textContent.substring(7).trim();
                    if (textContent.endsWith("```")) {
                        textContent = textContent.substring(0, textContent.length() - 3).trim();
                    }
                } else if (textContent.startsWith("```") && textContent.endsWith("```")) {
                    textContent = textContent.substring(3, textContent.length() - 3).trim();
                }

                return textContent;
            }

            throw new RuntimeException("Bedrock response missing content block");
        } catch (ThrottledException te) {
            throw te; // do not swallow throttling
        } catch (BedrockRuntimeException e) {
            logger.error("Bedrock API error during chat invoke for model {}: {}", this.bedrockModelId,
                    e.awsErrorDetails().errorMessage(), e);
            throw new RuntimeException("Bedrock API error during chat invoke", e);
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error during chat invoke: " + e.getMessage(), e);
        }
    }

    /**
     * Executes the Bedrock invocation with exponential backoff for throttling.
     */
    private InvokeModelResponse invokeWithRetry(InvokeModelRequest request, boolean isEmbedding) {
        final int maxAttempts = 6;
        final long baseBackoffMs = isEmbedding ? 400L : 800L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return bedrockClient.invokeModel(request);
            } catch (BedrockRuntimeException e) {
                int statusCode = e.statusCode();
                String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
                boolean throttled = statusCode == 429
                        || "ThrottlingException".equalsIgnoreCase(code)
                        || "TooManyRequestsException".equalsIgnoreCase(code)
                        || "ProvisionedThroughputExceededException".equalsIgnoreCase(code);

                if (!throttled) {
                    throw e; // non-throttling error -> bubble up
                }

                if (attempt == maxAttempts) {
                    logger.warn("Bedrock throttled after {} attempts; surfacing throttling.", maxAttempts);
                    throw new ThrottledException("Bedrock throttling after retries", e);
                }

                long jitter = ThreadLocalRandom.current().nextLong(50, 200);
                long sleepMs = (long) Math.min(10_000, baseBackoffMs * Math.pow(2, attempt - 1) + jitter);
                logger.warn("Bedrock throttled (attempt {}/{}). Backing off for {} ms. Error: {}",
                        attempt, maxAttempts, sleepMs,
                        e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during backoff", ie);
                }
            }
        }
        throw new RuntimeException("Unreachable");
    }
}