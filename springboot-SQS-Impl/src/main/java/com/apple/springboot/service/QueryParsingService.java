package com.apple.springboot.service;

import com.apple.springboot.model.ParsedQuery;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class QueryParsingService {

    private static final Logger logger = LoggerFactory.getLogger(QueryParsingService.class);

    private final BedrockEnrichmentService bedrockEnrichmentService;
    private final ObjectMapper objectMapper;

    @Autowired
    public QueryParsingService(BedrockEnrichmentService bedrockEnrichmentService, ObjectMapper objectMapper) {
        this.bedrockEnrichmentService = bedrockEnrichmentService;
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a raw natural language query into a structured ParsedQuery object
     * using LLM.
     */

    public ParsedQuery parseQuery(String rawQuery) {
        String uiElementHint = extractUiElementHint(rawQuery);
        String prompt = createParsingPrompt(rawQuery, uiElementHint);
        logger.info("Parsing query using LLM: '{}' with UI hint: '{}'", rawQuery, uiElementHint);

        try {
            // override max tokens to 1024 for safety
            String responseText = bedrockEnrichmentService.invokeChatForText(prompt, 1024);
            return extractParsedQueryOrFallback(responseText, rawQuery);
        } catch (Exception e) {
            logger.error("Error invoking Bedrock for query parsing. Falling back to raw query.", e);
            return fallbackParsedQuery(rawQuery);
        }
    }

    private String extractUiElementHint(String rawQuery) {
        String lowerQuery = rawQuery.toLowerCase();
        if (lowerQuery.matches(".*\\b(url|urls|link|links)\\b.*")) {
            return "url";
        } else if (lowerQuery.matches(".*\\b(headline|title)\\b.*")) {
            return "headline";
        } else if (lowerQuery.matches(".*\\b(image|picture|img)\\b.*")) {
            return "image";
        } else if (lowerQuery.matches(".*\\b(copy|text)\\b.*") && !lowerQuery.contains("accessibilityText")) {
            return "copy";
        } else if (lowerQuery.matches(".*\\b(cta|button|btn)\\b.*")) {
            return "cta";
        }

        // Dynamically detect any camelCase word (e.g., accessibilityText, anyNewKey) as
        // a schema key hint
        java.util.regex.Matcher camelCaseMatcher = java.util.regex.Pattern.compile("\\b([a-z]+[A-Z][a-zA-Z]*)\\b")
                .matcher(rawQuery);
        if (camelCaseMatcher.find()) {
            return camelCaseMatcher.group(1);
        } else if (lowerQuery.matches(".*\\b(analytics)\\b.*")) {
            return "analytics";
        }
        return null;
    }

    private String createParsingPrompt(String rawQuery, String uiElementHint) {
        logger.info("UI ELEMENT" + uiElementHint);
        return "You are an intelligent search query parser for a Content Management System.\n\n" +
                "Your task is to extract structured search parameters from a user query.\n\n" +
                "Follow these rules strictly:\n\n" +
                "1. QUERY\n" +
                "- Extract the main search intent or topic into \"query\".\n" +
                "- If the entire query consists only of filters, leave \"query\" as an empty string \"\".\n\n"
                +
                "2. CONCEPTUAL DEFINITIONS\n" +
                "- A \"Section\" is a high-level structural block or area of a webpage (e.g., a banner, a footer, a grid, a tile, a promotional block).\n"
                +
                "- A \"UI Element\" is a specific, granular piece of content contained WITHIN a section (e.g., a headline, a title, a link, a url, an image, copy, text, CTA, button).\n"
                +
                "  NOTE: These are just examples. Do NOT treat them as a hardcoded exhaustive list. The user could ask for any conceptual UI element.\n\n"
                +
                "3. UI ELEMENTS / CONTENT FIELDS\n" +
                "- If the user asks for a conceptual UI element (like those in the examples above):\n"
                +
                "  -> DO NOT put it in \"originalFieldName\".\n" +
                "  -> Map it into contextMap.uiElement.\n" +
                "  -> Format the value as lowercase hyphenated if multiple words.\n"
                +
                "- IMPORTANT: If the user explicitly provides an exact schema key (e.g., any camelCase word like \"accessibilityText\" or \"anyNewKey\", or specific backend keys like \"analytics\"):\n"
                +
                "  -> Put it in \"originalFieldName\" exactly as typed (do not hyphenate or lowercase it).\n"
                +
                "  -> DO NOT put it in contextMap.uiElement.\n\n"
                +
                (uiElementHint != null
                        ? "- HINT: The user is likely asking for the data field: \"" + uiElementHint
                                + "\". If it is camelCase or a schema key, map it to originalFieldName. Otherwise, map it to contextMap.uiElement.\n\n"
                        : "\n")
                +
                "4. SECTION FILTERS\n" +
                "- If the user specifies a page Section (as defined above):\n" +
                "  -> Format it as lowercase words joined with hyphens and append \"-section\"\n" +
                "  -> Store it in \"sectionKeyFilter\"\n" +
                "  -> DO NOT put page section names inside contextMap.\n\n" +
                "5. HANDLING BOTH CONCURRENTLY\n" +
                "- If the query contains BOTH a UI element AND a page Section, map the UI element to contextMap.uiElement (or originalFieldName if it is an exact schema key), and map the Section to sectionKeyFilter.\n\n"
                +
                "6. COUNTRY / LOCALE\n" +
                "- If the user mentions a country, map it to contextMap.country using a standard 2-letter country code (e.g., \"DE\" for Germany).\n"
                +
                "- IMPORTANT: If the user mentions a country or region, you MUST also intelligently deduce and populate contextMap.locale with the standard locale format (e.g., \"de_DE\" for Germany, \"en_US\" for USA, \"fr_FR\" for France).\n"
                +
                "- If a locale is explicitly mentioned, map it directly to contextMap.locale.\n\n" +
                "7. TAGS & KEYWORDS\n" +
                "- Extract explicit tags or important keywords only if clearly mentioned. Do not put UI elements or Sections here.\n\n"
                +
                "8. STRICT CONTEXTMAP RULE\n" +
                "- contextMap may ONLY contain these keys:\n" +
                "  [\"uiElement\", \"country\", \"locale\"]\n" +
                "- DO NOT create new keys.\n" +
                "- Example: { \"uiElement\": \"<extracted_ui_element_name>\" }\n\n" +
                "9. OUTPUT RULES\n" +
                "- Output ONLY valid JSON.\n" +
                "- Do NOT include explanations, markdown, or extra text.\n" +
                "- If a field is not applicable, use:\n" +
                "  - Empty string \"\" for string values\n" +
                "  - Empty array [] for arrays\n\n" +
                "Return JSON strictly in this format:\n\n" +
                "{\n" +
                "  \"query\": \"string\",\n" +
                "  \"tags\": [\"string\"],\n" +
                "  \"keywords\": [\"string\"],\n" +
                "  \"contextMap\": {\n" +
                "    \"uiElement\": \"string\",\n" +
                "    \"country\": \"string\",\n" +
                "    \"locale\": \"string\"\n" +
                "  },\n" +
                "  \"originalFieldName\": \"string\",\n" +
                "  \"sectionKeyFilter\": \"string\"\n" +
                "}\n\n" +
                "User Query: \"" + rawQuery + "\"";
    }

    private ParsedQuery extractParsedQueryOrFallback(String aiResponse, String rawQuery) {
        try {
            JsonNode root = objectMapper.readTree(aiResponse);
            ParsedQuery parsedQuery = new ParsedQuery();

            if (root.has("query") && !root.get("query").isNull()) {
                parsedQuery.setQuery(root.get("query").asText());
            } else {
                parsedQuery.setQuery(rawQuery); // fallback query
            }

            if (root.has("originalFieldName") && !root.get("originalFieldName").isNull()) {
                String field = root.get("originalFieldName").asText();
                if (!field.isBlank()) {
                    parsedQuery.setOriginalFieldName(field.toLowerCase().trim());
                }
            }

            if (root.has("sectionKeyFilter") && !root.get("sectionKeyFilter").isNull()) {
                String section = root.get("sectionKeyFilter").asText();
                if (!section.isBlank()) {
                    parsedQuery.setSectionKeyFilter(section.toLowerCase().trim());
                }
            }

            if (root.has("tags") && root.get("tags").isArray()) {
                List<String> tags = new ArrayList<>();
                root.get("tags").forEach(node -> tags.add(node.asText()));
                parsedQuery.setTags(tags.isEmpty() ? null : tags);
            }

            if (root.has("keywords") && root.get("keywords").isArray()) {
                List<String> keywords = new ArrayList<>();
                root.get("keywords").forEach(node -> keywords.add(node.asText()));
                parsedQuery.setKeywords(keywords.isEmpty() ? null : keywords);
            }

            if (root.has("contextMap") && root.get("contextMap").isObject()) {
                Map<String, Object> contextMap = new HashMap<>();
                root.get("contextMap").fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) {
                        String key = entry.getKey();
                        // Map the LLM's semantic 'uiElement' back to the backend's expected
                        // 'sectionName'
                        if ("uiElement".equals(key)) {
                            key = "sectionName";
                        }
                        contextMap.put(key, entry.getValue().asText());
                    }
                });
                parsedQuery.setContextMap(contextMap.isEmpty() ? null : contextMap);
            }

            // If the query is empty or blank, fallback to the raw query
            // This prevents "Malformed input request: expected minLength: 1, actual: 0"
            // from Bedrock embeddings
            if (parsedQuery.getQuery() == null || parsedQuery.getQuery().isBlank()) {
                parsedQuery.setQuery(rawQuery.trim());
            }

            return parsedQuery;
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse the LLM JSON response. Falling back. Response: {}", aiResponse, e);
            return fallbackParsedQuery(rawQuery);
        }
    }

    private ParsedQuery fallbackParsedQuery(String rawQuery) {
        ParsedQuery q = new ParsedQuery();
        q.setQuery(rawQuery);
        return q;
    }
}
