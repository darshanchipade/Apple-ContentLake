package com.apple.springboot.repository;

import com.apple.springboot.model.ContentChunk;
import com.apple.springboot.model.ContentChunkWithDistance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Repository
public class ContentChunkRepositoryImpl implements ContentChunkRepositoryCustom {

    private static final Set<String> LEXICAL_STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "to", "of", "in", "for", "on", "with", "at", "by",
            "from", "it", "its", "be", "been", "being", "and", "or", "but");

    @PersistenceContext
    private EntityManager entityManager;

    /** Extracts significant query tokens for token-based lexical match (ignores stopwords, punctuation). */
    private static List<String> extractQueryTokens(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> tokens = Pattern.compile("[^\\p{L}\\p{N}]+")
                .splitAsStream(query.trim().toLowerCase())
                .filter(s -> s.length() >= 2 && !LEXICAL_STOPWORDS.contains(s))
                .distinct()
                .collect(Collectors.toList());
        return tokens;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Builds and executes a native query for vector similarity search.
     */
    @Override
    public List<ContentChunkWithDistance> findSimilar(float[] embedding, String originalFieldName, String[] tags,
            String[] keywords, Map<String, Object> contextMap, Double threshold, int limit, String sectionKeyFilter) {
        StringBuilder sql = new StringBuilder("SELECT c.*");
        if (embedding != null) {
            sql.append(", (c.vector <=> CAST(:embedding AS vector)) as distance");
        }
        sql.append(
                " FROM content_chunks c JOIN consolidated_enriched_sections s ON c.consolidated_enriched_section_id = s.id WHERE 1=1");

        Map<String, Object> params = new HashMap<>();

        if (embedding != null) {
            params.put("embedding", embedding);
        }
        if (threshold != null) {
            params.put("distance_threshold", threshold);
        }
        if (originalFieldName != null && !originalFieldName.isBlank()) {
            sql.append(" AND (")
                    .append("LOWER(COALESCE(s.original_field_name, '')) LIKE :originalFieldNameMatch ")
                    .append("OR LOWER(COALESCE(s.context#>>'{facets,sectionName}', '')) LIKE :originalFieldNameMatch ")
                    .append("OR LOWER(COALESCE(s.context#>>'{envelope,sectionName}', '')) LIKE :originalFieldNameMatch ")
                    .append("OR LOWER(COALESCE(s.context#>>'{sectionName}', '')) LIKE :originalFieldNameMatch")
                    .append(")");
            params.put("originalFieldNameMatch", "%" + originalFieldName.toLowerCase() + "%");
        }
        if (tags != null && tags.length > 0) {
            sql.append(" AND s.tags @> CAST(:tags AS text[])");
            params.put("tags", tags);
        }
        if (keywords != null && keywords.length > 0) {
            sql.append(" AND s.keywords @> CAST(:keywords AS text[])");
            params.put("keywords", keywords);
        }
        if (contextMap != null && !contextMap.isEmpty()) {
            buildJsonbQueries(contextMap, new ArrayList<>(), sql, params);
        }
        if (sectionKeyFilter != null && !sectionKeyFilter.isBlank()) {
            String loweredKey = sectionKeyFilter.toLowerCase();
            sql.append(" AND (")
                    .append("LOWER(COALESCE(s.original_field_name, '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.section_path, '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.section_uri, '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.context->>'usagePath', '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.context#>>'{envelope,usagePath}', '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.context#>>'{sectionKey}', '')) = :sectionKeyExact ")
                    .append("OR LOWER(COALESCE(s.context#>>'{facets,sectionKey}', '')) = :sectionKeyExact ")
                    .append("OR LOWER(COALESCE(s.context#>>'{envelope,sectionKey}', '')) = :sectionKeyExact ")
                    .append("OR LOWER(COALESCE(s.context#>>'{facets,analyticsRegionSlug}', '')) = :sectionKeyExact ")
                    .append("OR LOWER(COALESCE(c.source_field, '')) LIKE :sectionKey)");
            params.put("sectionKey", "%" + loweredKey + "%"); // BOTH-SIDES WILDCARD
            params.put("sectionKeyExact", loweredKey);
        }
        if (embedding != null) {
            if (params.containsKey("distance_threshold")) {
                sql.append(" AND (c.vector <=> CAST(:embedding AS vector)) < :distance_threshold");
            }
            sql.append(" ORDER BY distance");
        }
        sql.append(" LIMIT :limit");
        params.put("limit", limit);

        Query query = entityManager.createNativeQuery(sql.toString(), "ContentChunkWithDistanceMapping");
        params.forEach(query::setParameter);

        List<Object[]> results = query.getResultList();
        List<ContentChunkWithDistance> dtos = new ArrayList<>();
        for (Object[] result : results) {
            ContentChunk chunk = (ContentChunk) result[0];
            double distance = (embedding != null) ? ((Number) result[1]).doubleValue() : 0.0;
            dtos.add(new ContentChunkWithDistance(chunk, distance));
        }
        return dtos;
    }

    /**
     * Appends JSONB query predicates for context map filters.
     */
    private void buildJsonbQueries(Map<String, Object> map, List<String> path, StringBuilder sql,
            Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            List<String> newPath = new ArrayList<>(path);
            newPath.add(entry.getKey());
            if (entry.getValue() instanceof Map) {
                buildJsonbQueries((Map<String, Object>) entry.getValue(), newPath, sql, params);
            } else if (entry.getValue() instanceof List) {
                String pathString = "{" + String.join(",", newPath) + "}";
                String paramName = String.join("_", newPath);
                sql.append(" AND s.context #>> '").append(pathString).append("' IN (:").append(paramName).append(")");
                params.put(paramName, (List<?>) entry.getValue());
            } else if (entry.getValue() != null) {
                // Handle string and other scalar values (locale, country, language, etc.)
                String pathString = "{" + String.join(",", newPath) + "}";
                String paramName = String.join("_", newPath);
                sql.append(" AND LOWER(COALESCE(s.context#>>'").append(pathString).append("', '')) = LOWER(:")
                        .append(paramName).append(")");
                params.put(paramName, entry.getValue().toString());
            }
        }
    }

    @Override
    public List<ContentChunkWithDistance> findLexicalSimilar(String textQuery, String originalFieldName, String[] tags,
            String[] keywords, Map<String, Object> contextMap, int limit, String sectionKeyFilter) {
        
        // Use exact match distance of 0.0, or partial match ILIKE. 
        // For simplicity using ILIKE match yielding distance 0.5 for ranking differentiation.
        StringBuilder sql = new StringBuilder("SELECT c.*, 0.5 as distance");
        sql.append(
                " FROM content_chunks c JOIN consolidated_enriched_sections s ON c.consolidated_enriched_section_id = s.id WHERE 1=1");

        Map<String, Object> params = new HashMap<>();

        // Token-based lexical: match chunks containing multiple query terms (handles natural typing, no exact punctuation)
        if (textQuery != null && !textQuery.isBlank()) {
            List<String> tokens = extractQueryTokens(textQuery);
            if (tokens.size() >= 2) {
                // Require ALL significant tokens (e.g. "why apple best place buy mac" → all 6 must appear)
                for (int i = 0; i < tokens.size(); i++) {
                    sql.append(" AND c.chunk_text ILIKE :lexToken").append(i);
                    params.put("lexToken" + i, "%" + escapeLike(tokens.get(i)) + "%");
                }
            } else {
                // Fallback: 1 token or all stopwords → use full-query substring
                sql.append(" AND c.chunk_text ILIKE :textQueryText ");
                params.put("textQueryText", "%" + escapeLike(textQuery.trim()) + "%");
            }
        }

        if (originalFieldName != null && !originalFieldName.isBlank()) {
            sql.append(" AND (")
                    .append("LOWER(COALESCE(s.original_field_name, '')) LIKE :originalFieldNameMatch ")
                    .append("OR LOWER(COALESCE(s.context#>>'{facets,sectionName}', '')) LIKE :originalFieldNameMatch ")
                    .append("OR LOWER(COALESCE(s.context#>>'{envelope,sectionName}', '')) LIKE :originalFieldNameMatch ")
                    .append("OR LOWER(COALESCE(s.context#>>'{sectionName}', '')) LIKE :originalFieldNameMatch")
                    .append(")");
            params.put("originalFieldNameMatch", "%" + originalFieldName.toLowerCase() + "%");
        }
        if (tags != null && tags.length > 0) {
            sql.append(" AND s.tags @> CAST(:tags AS text[])");
            params.put("tags", tags);
        }
        if (keywords != null && keywords.length > 0) {
            sql.append(" AND s.keywords @> CAST(:keywords AS text[])");
            params.put("keywords", keywords);
        }
        if (contextMap != null && !contextMap.isEmpty()) {
            buildJsonbQueries(contextMap, new ArrayList<>(), sql, params);
        }
        if (sectionKeyFilter != null && !sectionKeyFilter.isBlank()) {
            String loweredKey = sectionKeyFilter.toLowerCase();
            sql.append(" AND (")
                    .append("LOWER(COALESCE(s.original_field_name, '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.section_path, '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.section_uri, '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.context->>'usagePath', '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.context#>>'{envelope,usagePath}', '')) LIKE :sectionKey ")
                    .append("OR LOWER(COALESCE(s.context#>>'{sectionKey}', '')) = :sectionKeyExact ")
                    .append("OR LOWER(COALESCE(s.context#>>'{facets,sectionKey}', '')) = :sectionKeyExact ")
                    .append("OR LOWER(COALESCE(s.context#>>'{envelope,sectionKey}', '')) = :sectionKeyExact ")
                    .append("OR LOWER(COALESCE(s.context#>>'{facets,analyticsRegionSlug}', '')) = :sectionKeyExact ")
                    .append("OR LOWER(COALESCE(c.source_field, '')) LIKE :sectionKey)");
            params.put("sectionKey", "%" + loweredKey + "%"); // BOTH-SIDES WILDCARD
            params.put("sectionKeyExact", loweredKey);
        }
        
        sql.append(" ORDER BY c.id LIMIT :limit");
        params.put("limit", limit);

        Query query = entityManager.createNativeQuery(sql.toString(), "ContentChunkWithDistanceMapping");
        params.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        List<ContentChunkWithDistance> dtos = new ArrayList<>();
        for (Object[] result : results) {
            ContentChunk chunk = (ContentChunk) result[0];
            double distance = ((Number) result[1]).doubleValue(); // 0.5 flat score for now to treat as matched
            dtos.add(new ContentChunkWithDistance(chunk, distance));
        }
        return dtos;
    }
}