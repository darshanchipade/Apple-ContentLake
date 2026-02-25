package com.apple.springboot.service;

import com.apple.springboot.model.ChatbotRequest;
import com.apple.springboot.model.ChatbotResultDto;
import com.apple.springboot.model.ContentChunkWithDistance;
import com.apple.springboot.model.ConsolidatedEnrichedSection;
import com.apple.springboot.model.QueryInterpretation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatbotService {
    private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);
    private static final int LOG_VALUE_LIMIT = 500;
    private final VectorSearchService vectorSearchService;
    private final QueryInterpretationService queryInterpretationService;

    private static final Pattern NORMALIZE_LOCALE_PATTERN = Pattern.compile("(?i)^([a-z]{2})[_-]([a-z]{2})$");

    public ChatbotService(VectorSearchService vectorSearchService,
            QueryInterpretationService queryInterpretationService) {
        this.vectorSearchService = vectorSearchService;
        this.queryInterpretationService = queryInterpretationService;
    }

    /**
     * Entry point for chatbot queries; orchestrates interpretation, filtering, and
     * retrieval.
     */
    public List<ChatbotResultDto> query(ChatbotRequest request) {
        String userMessage = request != null ? request.getMessage() : null;
        Map<String, Object> requestContext = request != null && request.getContext() != null ? request.getContext()
                : Collections.emptyMap();
        log.info(
                "Chatbot query input message='{}', sectionKey='{}', role='{}', tags={}, keywords={}, contextKeys={}",
                clip(userMessage),
                request != null ? request.getSectionKey() : null,
                request != null ? request.getOriginal_field_name() : null,
                request != null ? request.getTags() : null,
                request != null ? request.getKeywords() : null,
                contextKeys(requestContext));

        QueryInterpretation interpretation = queryInterpretationService
                .interpret(userMessage, requestContext)
                .orElse(null);
        if (interpretation != null) {
            log.info(
                    "Chatbot interpretation rawQuery='{}', sectionKey='{}', role='{}', pageId='{}', locale='{}', language='{}', country='{}', tags={}, keywords={}, contextKeys={}",
                    clip(interpretation.rawQuery()),
                    interpretation.sectionKey(),
                    interpretation.role(),
                    interpretation.pageId(),
                    interpretation.locale(),
                    interpretation.language(),
                    interpretation.country(),
                    interpretation.tags(),
                    interpretation.keywords(),
                    contextKeys(interpretation.context()));
        }

        SearchCriteria criteria = buildCriteria(request, interpretation);
        log.info(
                "Chatbot search criteria sectionKey='{}', role='{}', pageId='{}', locale='{}', language='{}', country='{}', message='{}'",
                criteria.sectionKey(),
                criteria.role(),
                criteria.pageId(),
                criteria.locale(),
                criteria.language(),
                criteria.country(),
                clip(criteria.message()));
        if (!StringUtils.hasText(criteria.sectionKey())) {
            return List.of();
        }

        LinkedHashSet<String> tagSet = new LinkedHashSet<>();
        LinkedHashSet<String> keywordSet = new LinkedHashSet<>();
        if (request != null) {
            addNormalizedStrings(tagSet, request.getTags());
            addNormalizedStrings(keywordSet, request.getKeywords());
        }
        List<String> tagFilters = new ArrayList<>(tagSet);
        List<String> keywordFilters = new ArrayList<>(keywordSet);
        log.info("Chatbot search filters tags={}, keywords={}", tagFilters, keywordFilters);

        Map<String, Object> interpretationContext = interpretation != null && interpretation.context() != null
                ? interpretation.context()
                : Collections.emptyMap();
        if (interpretation != null) {
            String interpretedPageId = normalizePageId(interpretation.pageId());
            if (!StringUtils.hasText(criteria.pageId()) && StringUtils.hasText(interpretedPageId)) {
                criteria = criteria.withPageId(interpretedPageId);
            }
            Map<String, Object> facets = asMap(interpretationContext.get("facets"));
            if (!StringUtils.hasText(criteria.sectionKey()) && facets != null) {
                String fromContext = normalizeKey(firstString(facets.get("sectionKey")));
                if (StringUtils.hasText(fromContext)) {
                    criteria = criteria.withSectionKey(fromContext);
                }
            }
        }

        int limit = determineLimit(request);

        Map<String, Object> effectiveContext = new LinkedHashMap<>();
        if (requestContext != null) {
            effectiveContext.putAll(requestContext);
        }

        Map<String, Object> envelopeCriteria = new LinkedHashMap<>();
        if (StringUtils.hasText(criteria.locale())) {
            envelopeCriteria.put("locale", criteria.locale());
        }
        if (StringUtils.hasText(criteria.country())) {
            envelopeCriteria.put("country", criteria.country());
        }
        if (StringUtils.hasText(criteria.language())) {
            envelopeCriteria.put("language", criteria.language());
        }

        if (!envelopeCriteria.isEmpty()) {
            Object existingEnvelope = effectiveContext.get("envelope");
            if (existingEnvelope instanceof Map<?, ?> existingMap) {
                Map<String, Object> mergedEnvelope = new LinkedHashMap<>();
                existingMap.forEach((k, v) -> mergedEnvelope.put(String.valueOf(k), v));
                mergedEnvelope.putAll(envelopeCriteria);
                effectiveContext.put("envelope", mergedEnvelope);
            } else {
                effectiveContext.put("envelope", envelopeCriteria);
            }
        }

        List<ChatbotResultDto> results = fetchVectorResults(criteria, effectiveContext, limit, tagFilters,
                keywordFilters);
        assignCfIds(results, criteria, tagFilters, keywordFilters);

        return results;
    }

    /**
     * Resolves the desired result limit while enforcing sane boundaries.
     */
    private int determineLimit(ChatbotRequest request) {
        if (request == null || request.getLimit() == null || request.getLimit() <= 0) {
            return 15;
        }
        return Math.min(request.getLimit(), 200);
    }

    /**
     * Executes the vector search path and adapts database chunks into DTOs.
     */
    private List<ChatbotResultDto> fetchVectorResults(SearchCriteria criteria,
            Map<String, Object> context,
            int limit,
            List<String> tags,
            List<String> keywords) {
        if (!StringUtils.hasText(criteria.embeddingQuery())) {
            return List.of();
        }
        try {
            List<ContentChunkWithDistance> rows = vectorSearchService.search(
                    criteria.embeddingQuery(),
                    criteria.role(),
                    limit,
                    tags == null || tags.isEmpty() ? null : tags,
                    keywords == null || keywords.isEmpty() ? null : keywords,
                    context.isEmpty() ? null : context,
                    null,
                    criteria.sectionKey());

            return rows.stream()
                    .map(row -> {
                        ConsolidatedEnrichedSection section = row.getContentChunk().getConsolidatedEnrichedSection();
                        ChatbotResultDto dto = mapSection(section, "content_chunks");
                        if (dto != null && !StringUtils.hasText(dto.getContentRole())) {
                            dto.setContentRole(firstNonBlank(row.getContentChunk().getSourceField()));
                        }
                        return dto;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception ex) {
            log.error("Error fetching vector results", ex);
            return List.of();
        }
    }

    /**
     * Normalizes and truncates strings for log-safe output.
     */
    private String clip(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= LOG_VALUE_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LOG_VALUE_LIMIT) + "...";
    }

    /**
     * Extracts top-level keys from a context map for logging.
     */
    private List<String> contextKeys(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(context.keySet());
    }

    /**
     * Populates cfIds and inferred metadata fields for outbound DTOs.
     */
    private void assignCfIds(List<ChatbotResultDto> results,
            SearchCriteria criteria,
            List<String> tags,
            List<String> keywords) {
        if (results == null) {
            return;
        }
        for (int i = 0; i < results.size(); i++) {
            ChatbotResultDto dto = results.get(i);
            if (dto == null) {
                continue;
            }
            dto.setCfId("cf" + (i + 1));

            if (!StringUtils.hasText(dto.getSection())) {
                dto.setSection(criteria.sectionKey());
            }
            if (!StringUtils.hasText(dto.getLocale()) && StringUtils.hasText(criteria.locale())) {
                dto.setLocale(criteria.locale());
            }
            if (!StringUtils.hasText(dto.getCountry()) && StringUtils.hasText(criteria.country())) {
                dto.setCountry(criteria.country());
            }
            if (!StringUtils.hasText(dto.getLanguage()) && StringUtils.hasText(criteria.language())) {
                dto.setLanguage(criteria.language());
            }
            if (!StringUtils.hasText(dto.getPageId()) && StringUtils.hasText(criteria.pageId())) {
                dto.setPageId(criteria.pageId());
            }
            if (!StringUtils.hasText(dto.getContentRole()) && StringUtils.hasText(criteria.role())) {
                dto.setContentRole(criteria.role());
            }
            if (!StringUtils.hasText(dto.getTenant())) {
                dto.setTenant("applecom-cms");
            }

            dto.setMatchTerms(buildMatchTerms(dto, criteria, tags, keywords));
        }
    }

    /**
     * Builds the descriptive term list exposed to clients for highlighting.
     */
    private List<String> buildMatchTerms(ChatbotResultDto dto,
            SearchCriteria criteria,
            List<String> tags,
            List<String> keywords) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTerm(terms, criteria.sectionKey());
        addTerm(terms, dto.getContentRole());
        addTerm(terms, criteria.role());
        addTerm(terms, dto.getPageId());
        addTerm(terms, criteria.pageId());
        addTerm(terms, criteria.country());
        addTerm(terms, criteria.language());

        if (tags != null) {
            terms.addAll(tags);
        }
        if (keywords != null) {
            terms.addAll(keywords);
        }
        return new ArrayList<>(terms);
    }

    /**
     * Adds a trimmed value to a term set when present.
     */
    private void addTerm(Set<String> terms, String value) {
        if (terms == null || !StringUtils.hasText(value)) {
            return;
        }
        terms.add(value);
    }

    /**
     * Normalizes and deduplicates user-provided tags or keywords.
     */
    private void addNormalizedStrings(Set<String> target, List<String> values) {
        if (target == null || values == null) {
            return;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                target.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * Converts a consolidated section entity into a DTO representation.
     */
    private ChatbotResultDto mapSection(ConsolidatedEnrichedSection section, String source) {
        if (section == null) {
            return null;
        }
        ChatbotResultDto dto = new ChatbotResultDto();
        dto.setSectionPath(section.getSectionPath());
        dto.setSectionUri(section.getSectionUri());
        dto.setCleansedText(section.getCleansedText());
        dto.setContext(section.getContext());
        dto.setContentRole(
                firstNonBlank(section.getOriginalFieldName(), asStringFromContext(section, "envelope", "sectionName")));
        dto.setSource(source);
        dto.setLastModified(formatTimestamp(section.getSavedAt()));

        String tenant = firstNonBlank(asStringFromContext(section, "envelope", "tenant"), "applecom-cms");
        dto.setTenant(tenant);

        String pageId = normalizePageId(asStringFromContext(section, "envelope", "pageId"));
        dto.setPageId(pageId);

        return dto;
    }

    /**
     * Formats timestamps for serialization while handling nulls.
     */
    private String formatTimestamp(OffsetDateTime timestamp) {
        return timestamp != null ? timestamp.toString() : null;
    }

    /**
     * Derives the effective search criteria from request and interpretation
     * signals.
     */
    private SearchCriteria buildCriteria(ChatbotRequest request, QueryInterpretation interpretation) {
        String originalMessage = request != null ? request.getMessage() : null;
        String interpretedQuery = interpretation != null ? interpretation.rawQuery() : null;
        String message = StringUtils.hasText(interpretedQuery) ? interpretedQuery : originalMessage;
        if (!StringUtils.hasText(message) && StringUtils.hasText(originalMessage)) {
            message = originalMessage;
        }

        String interpretedSectionKey = normalizeKey(interpretation != null ? interpretation.sectionKey() : null);
        String requestSectionKey = slugify(request != null ? request.getSectionKey() : null);
        String sectionKey = firstNonBlank(interpretedSectionKey, requestSectionKey);

        String interpretedRole = normalizeRole(interpretation != null ? interpretation.role() : null);
        String explicitRole = normalizeRole(request != null ? request.getOriginal_field_name() : null);
        String role = firstNonBlank(interpretedRole, explicitRole);

        String interpretedLocale = normalizeLocale(interpretation != null ? interpretation.locale() : null);
        String interpretedLanguage = interpretation != null ? interpretation.language() : null;
        String interpretedCountry = interpretation != null ? interpretation.country() : null;

        String locale = firstNonBlank(interpretedLocale, null);
        String language = firstNonBlank(interpretedLanguage, null);
        String country = firstNonBlank(interpretedCountry, null);

        String pageId = normalizePageId(interpretation != null ? interpretation.pageId() : null);

        if (request != null && request.getContext() != null) {
            Map<String, Object> context = request.getContext();
            sectionKey = firstNonBlank(sectionKey, slugify(firstString(context.get("sectionKey"))));
            locale = firstNonBlank(locale, normalizeLocale(firstString(context.get("locale"))));
            country = firstNonBlank(country, firstString(context.get("country")));
            language = firstNonBlank(language, firstString(context.get("language")));
            pageId = firstNonBlank(pageId, normalizePageId(firstString(context.get("pageId"))));

            Map<String, Object> envelope = asMap(context.get("envelope"));
            if (envelope != null) {
                sectionKey = firstNonBlank(sectionKey, slugify(firstString(envelope.get("sectionKey"))));
                locale = firstNonBlank(locale, normalizeLocale(firstString(envelope.get("locale"))));
                country = firstNonBlank(country, firstString(envelope.get("country")));
                language = firstNonBlank(language, firstString(envelope.get("language")));
                pageId = firstNonBlank(pageId, normalizePageId(firstString(envelope.get("pageId"))));
            }

            Map<String, Object> facets = asMap(context.get("facets"));
            if (facets != null) {
                sectionKey = firstNonBlank(sectionKey, slugify(firstString(facets.get("sectionKey"))));
                locale = firstNonBlank(locale, normalizeLocale(firstString(facets.get("locale"))));
                country = firstNonBlank(country, firstString(facets.get("country")));
                pageId = firstNonBlank(pageId, normalizePageId(firstString(facets.get("pageId"))));
            }
        }

        if (!StringUtils.hasText(language) && StringUtils.hasText(locale)) {
            language = locale.substring(0, 2).toLowerCase(Locale.ROOT);
        }
        if (!StringUtils.hasText(country) && StringUtils.hasText(locale)) {
            country = locale.substring(3).toUpperCase(Locale.ROOT);
        }

        return new SearchCriteria(sectionKey, role, locale, language, country, pageId, message);
    }

    /**
     * Normalizes arbitrary keys into lowercase slugs.
     */
    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Converts a phrase into a slug usable as section key.
     */
    private String slugify(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String slug = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-");
        if (slug.startsWith("-")) {
            slug = slug.substring(1);
        }
        if (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return StringUtils.hasText(slug) ? slug : null;
    }

    /**
     * Trims and lowercases role descriptors.
     */
    private String normalizeRole(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes page identifiers for consistent comparisons.
     */
    private String normalizePageId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes locales to the lang_COUNTRY format.
     */
    private String normalizeLocale(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim().replace('-', '_');
        Matcher matcher = NORMALIZE_LOCALE_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1).toLowerCase(Locale.ROOT) + "_" + matcher.group(2).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * Returns the first non-blank string within an array.
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Helper to read nested context values as strings.
     */
    private String asStringFromContext(ConsolidatedEnrichedSection section, String topLevelKey, String nestedKey) {
        if (section == null || section.getContext() == null) {
            return null;
        }
        Map<String, Object> parent = asMap(section.getContext().get(topLevelKey));
        if (parent == null) {
            return null;
        }
        return firstString(parent.get(nestedKey));
    }

    /**
     * Converts a raw object into a mutable string-keyed map when possible.
     */
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, val) -> copy.put(String.valueOf(key), val));
            return copy;
        }
        return null;
    }

    /**
     * Returns the first available non-blank string from heterogenous structures.
     */
    private String firstString(Object value) {
        if (value instanceof String str && StringUtils.hasText(str)) {
            return str;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String str && StringUtils.hasText(str)) {
                    return str;
                }
            }
        }
        return null;
    }

    /**
     * Immutable holder describing the normalized query intent.
     */

    private record SearchCriteria(String sectionKey,
            String role,
            String locale,
            String language,
            String country,
            String pageId,
            String message) {
        /**
         * Returns the string that should feed the embedding pipeline.
         */
        String embeddingQuery() {
            return StringUtils.hasText(message) ? message : sectionKey;
        }

        /**
         * Returns a copy with a new section key when it changes.
         */
        SearchCriteria withSectionKey(String newSectionKey) {
            String normalized = StringUtils.hasText(newSectionKey) ? newSectionKey : null;
            if (Objects.equals(sectionKey, normalized)) {
                return this;
            }
            return new SearchCriteria(normalized, role, locale, language, country, pageId, message);
        }

        /**
         * Returns a copy with an updated page identifier.
         */
        SearchCriteria withPageId(String newPageId) {
            String normalized = StringUtils.hasText(newPageId) ? newPageId : null;
            if (Objects.equals(pageId, normalized)) {
                return this;
            }
            return new SearchCriteria(sectionKey, role, locale, language, country, normalized, message);
        }
    }

}