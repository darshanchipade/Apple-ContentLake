package com.apple.springboot.service;

import com.apple.springboot.model.ContentChunkWithDistance;
import com.apple.springboot.repository.ContentChunkRepository;
import com.apple.springboot.model.ContentRoleDto;
import com.apple.springboot.model.ConsolidatedEnrichedSection;
import com.apple.springboot.model.SemanticSectionResultDto;
import com.apple.springboot.repository.ConsolidatedEnrichedSectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);
    private static final Pattern LOCALE_SEGMENT_PATTERN = Pattern.compile("^[a-z]{2}([_-])[a-z]{2}$");
    private static final Set<String> PAGE_CONTEXT_TOKENS = Set.of("overview", "specs", "compare");
    private static final Set<String> GENERIC_QUERY_TOKENS = Set.of(
            "iphone", "ipad", "mac", "airpods", "watch", "vision",
            "pro", "max", "mini", "plus", "ultra",
            "spec", "specs", "overview", "compare", "model", "models");
    private static final Pattern GENERIC_LEAF_SEGMENT_PATTERN = Pattern.compile(
            "(?i).*(copy|text|title|headline|subheadline|topic|eyebrow|caption|label|cta|calltoaction|action|button|link|description|summary|heading|image|icon|graphic|media|disclaimers|items?|accordion|gallery|list|tab|row|column|cell).*\\d*$");

    @Autowired
    private ContentChunkRepository contentChunkRepository;
    @Autowired
    private ConsolidatedEnrichedSectionRepository consolidatedEnrichedSectionRepository;
    @Autowired
    private BedrockEnrichmentService bedrockEnrichmentService;
    @Autowired
    private ObjectMapper objectMapper;

    // Context window — configurable via application.properties
    @Value("${search.context.window-size:5}")
    private int contextWindowSize;

    @Value("${search.context.headline-window-size:25}")
    private int headlineWindowSize;

    @Value("${search.context.headline-field-keywords:headline,title,heading}")
    private List<String> headlineFieldKeywords;

    @Value("${search.context.excluded-field-keywords:analytics,accessibility,url_,footnote,image,icon,figure,picture,media,thumbnail,sprite,graphic,logo,hero,banner,background,artwork,badge,symbol,pinnable}")
    private List<String> excludedFieldKeywords;

    /** When true, LLM re-ranks top results. When false, hybrid scores (vector+lexical+coverage) are used as-is. */
    @Value("${search.rerank.use-llm:false}")
    private boolean useLlmRerank;

    /** Coverage weight in hybrid score. Lower values reduce dominance of sections with many generic chunks. */
    @Value("${search.context.coverage-weight:0.15}")
    private double coverageWeight;

    /**
     * Runs a vector search using the supplied query and optional filters.
     * Example: search("iphone chip", null, 20, null, null, null, null, null) returns top semantic chunks.
     */
    @Transactional(readOnly = true)
    public List<ContentChunkWithDistance> search(
            String query,
            String original_field_name,
            int limit,
            List<String> tags,
            List<String> keywords,
            Map<String, Object> contextMap,
            Double threshold,
            String sectionKeyFilter
    ) throws IOException {
        float[] queryVector = bedrockEnrichmentService.generateEmbedding(query);
        String[] tagsArray = (tags != null && !tags.isEmpty()) ? tags.toArray(new String[0]) : null;
        String[] keywordsArray = (keywords != null && !keywords.isEmpty()) ? keywords.toArray(new String[0]) : null;
        String fieldName = (original_field_name != null && !original_field_name.isEmpty()) ? original_field_name.toLowerCase() : null;
        return contentChunkRepository.findSimilar(queryVector, fieldName, tagsArray, keywordsArray, contextMap, threshold, limit, sectionKeyFilter);
    }
    /**
     * Convenience overload that omits the section key filter.
     * Example: search("compare airpods", null, 20, null, null, null, null) delegates with sectionKeyFilter=null.
     */
    public List<ContentChunkWithDistance> search(
            String query,
            String original_field_name,
            int limit,
            List<String> tags,
            List<String> keywords,
            Map<String, Object> contextMap,
            Double threshold
    ) throws IOException {
        return search(query, original_field_name, limit, tags, keywords, contextMap, threshold, null);
    }

    /**
     * V2 Enterprise-Grade Hybrid Semantic Retrieval Pipeline
     * Uses bounded math to prevent long-section bias and smooths outlier chunk scores.
     * Example: hybridSearch("Capacity for iphone 17 pro", 10) returns grouped semantic result cards.
     */
    @Transactional(readOnly = true)
    public List<SemanticSectionResultDto> hybridSearch(String query, int limit) throws IOException {
        final QueryIntent intent = detectIntent(query);
        final IntentProfile intentProfile = profileForIntent(intent);
        final QueryScope queryScope = extractQueryScope(query);
        final boolean isCompareIntent = intent == QueryIntent.COMPARE;
        // 1. Dense Retrieval (Embeddings)
        float[] queryVector = bedrockEnrichmentService.generateEmbedding(query);
        // We fetch a larger pool (e.g., 50) of chunks for aggregation, and we'll return 'limit' sections
        List<ContentChunkWithDistance> denseHits = contentChunkRepository.findSimilar(
                queryVector, null, null, null, null, null, 100, null);

        // 2. Lexical Fallback Retrieval
        List<ContentChunkWithDistance> lexicalHits = contentChunkRepository.findLexicalSimilar(
                query, null, null, null, null, 100, null);

        // Map section URI -> List of hitting chunks (from both dense and lexical)
        Map<String, List<ContentChunkWithDistance>> sectionHits = new HashMap<>();
        Map<String, ConsolidatedEnrichedSection> sectionMap = new HashMap<>();

        // Group dense hits
        for (ContentChunkWithDistance hit : denseHits) {
            String uri = hit.getContentChunk().getConsolidatedEnrichedSection().getSectionUri();
            String path = hit.getContentChunk().getConsolidatedEnrichedSection().getSectionPath();
            
            // Exclude global navigation/hidden structural files entirely
            if (path != null && path.contains("/global-elements/")) {
                continue;
            }

            sectionHits.computeIfAbsent(uri, k -> new ArrayList<>()).add(hit);
            sectionMap.putIfAbsent(uri, hit.getContentChunk().getConsolidatedEnrichedSection());
        }

        // Group lexical hits (avoid duplicate chunks if already found by dense)
        for (ContentChunkWithDistance lHit : lexicalHits) {
            String uri = lHit.getContentChunk().getConsolidatedEnrichedSection().getSectionUri();
            String path = lHit.getContentChunk().getConsolidatedEnrichedSection().getSectionPath();
            
            // Output Shaping: Exclude global navigation/hidden structural files entirely
            if (path != null && path.contains("/global-elements/")) {
                continue;
            }

            sectionMap.putIfAbsent(uri, lHit.getContentChunk().getConsolidatedEnrichedSection());
            List<ContentChunkWithDistance> currentHits = sectionHits.computeIfAbsent(uri, k -> new ArrayList<>());
            boolean alreadyExists = currentHits.stream().anyMatch(
                    h -> h.getContentChunk().getId().equals(lHit.getContentChunk().getId()));
            if (!alreadyExists) {
                // To keep math simple, we assign a simulated dense distance for pure lexical hits
                // Standard pgvector distance: 0.0 is perfect, 1.0 is unrelated. Lexical gets 0.45.
                currentHits.add(new ContentChunkWithDistance(lHit.getContentChunk(), 0.45));
            }
        }

        // Combine dense and lexical hits into a single map of ContentChunk (to avoid duplicates)
        Map<UUID, ContentChunkWithDistance> combinedHits = new HashMap<>();
        for (ContentChunkWithDistance hit : denseHits) {
            combinedHits.put(hit.getContentChunk().getId(), hit);
        }
        for (ContentChunkWithDistance lHit : lexicalHits) {
            // Only add if not already present from dense hits
            combinedHits.putIfAbsent(lHit.getContentChunk().getId(), new ContentChunkWithDistance(lHit.getContentChunk(), 0.45)); // Assign default distance for pure lexical
        }

        // 3. Section-Level Aggregation & Assembly
        // Map of baseComponentPath -> List of chunks
        Map<String, List<ContentChunkWithDistance>> chunksByBaseComponent = new HashMap<>();
        Map<String, ConsolidatedEnrichedSection> sectionsByBaseComponent = new HashMap<>();
        // Track ALL original sectionUris that map to each baseComponentPath
        Map<String, Set<String>> urisByBaseComponent = new HashMap<>();

        // These patterns indicate hollow structural containers or global nav — always excluded from search results
        Set<String> EXCLUDED_PATH_SEGMENTS = Set.of("/global-elements/", "/global-navigation/");
        Set<String> EXCLUDED_URI_SUFFIXES = Set.of("/webpage-content", "/composition", "/section", "/container", "/wrapper");
        // These substring patterns in any section path/uri indicate a navigation link node — not a displayable card
        // navigationItem = anchor/link nodes (e.g. K-12, College Students) — pure nav links, not displayable cards
        Set<String> EXCLUDED_URI_CONTAINS = Set.of("navigationItem");

        for (ContentChunkWithDistance hit : combinedHits.values()) {
            ConsolidatedEnrichedSection section = hit.getContentChunk().getConsolidatedEnrichedSection();
            if (section == null || section.getSectionUri() == null) continue;

            String sectionPath = section.getSectionPath();
            String sectionUri = section.getSectionUri();

            // Exclude global navigation and structural paths
            boolean excluded = false;
            for (String seg : EXCLUDED_PATH_SEGMENTS) {
                if ((sectionPath != null && sectionPath.contains(seg)) ||
                    sectionUri.contains(seg)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) continue;

            String baseComponentPath = getBaseComponentPath(sectionUri);
            baseComponentPath = canonicalGroupPath(baseComponentPath, sectionPath, sectionUri, section.getOriginalFieldName());
            // For compare-intent queries, collapse all compare subtree fragments into one canonical group.
            // Example:
            //   /en_US/airpods/html-content-section[5]/compare/airPods4AdaptiveAudioFeature001
            //   /en_US/airpods/html-content-section[5]/compare/pricingCallToAction006
            // both map to:
            //   /en_US/airpods/html-content-section[5]/compare
            //while building grouped section keys, and only executes when isCompareIntent is true (query detected as compare intent). In that case, it collapses compare subtree paths to one canonical /compare root before scoring/groupin
            if (isCompareIntent) {
                String canonicalCompare = canonicalCompareGroupPath(baseComponentPath, sectionPath, sectionUri);
                if (StringUtils.hasText(canonicalCompare)) {
                    baseComponentPath = canonicalCompare;
                }
            }

            // Exclude results that resolve to hollow structural containers
            for (String suffix : EXCLUDED_URI_SUFFIXES) {
                if (baseComponentPath.endsWith(suffix)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) continue;

            // Exclude navigation link items and other non-card structural nodes
            for (String pattern : EXCLUDED_URI_CONTAINS) {
                if (sectionUri.contains(pattern)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) continue;

            chunksByBaseComponent.computeIfAbsent(baseComponentPath, k -> new ArrayList<>()).add(hit);
            sectionsByBaseComponent.putIfAbsent(baseComponentPath, section);
            urisByBaseComponent.computeIfAbsent(baseComponentPath, k -> new HashSet<>()).add(sectionUri);
        }

        List<SectionScore> scoredSections = new ArrayList<>();
        
        for (Map.Entry<String, List<ContentChunkWithDistance>> entry : chunksByBaseComponent.entrySet()) {
            String baseComponentPath = entry.getKey();
            List<ContentChunkWithDistance> hits = entry.getValue();
            ConsolidatedEnrichedSection section = sectionsByBaseComponent.get(baseComponentPath);
            
            // Calculate similarity score (invert pgvector distance where 0 is perfect match)
            // Sim range: [0.0, 1.0] where 1.0 is exact match
            List<Double> similarities = hits.stream()
                    .map(h -> Math.max(0.0, 1.0 - h.getDistance()))
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());

            double maxSim = similarities.get(0);
            
            // Mean of Top 3 similarities
            double sumTop3 = 0.0;
            int topK = Math.min(3, similarities.size());
            for (int i = 0; i < topK; i++) {
                sumTop3 += similarities.get(i);
            }
            double meanTop3Sim = sumTop3 / topK;

            // vectorStrength = 0.7 * maxSim + 0.3 * meanTop3Sim
            double vectorStrength = 0.7 * maxSim + 0.3 * meanTop3Sim;

            //hitCount: how many retrieved chunks from this section matched.
            //totalChunksInSection: approximate section size in chunks (estimated from text length).
            // coverage bounding logic
            int hitCount = hits.size();
            // Estimate total chunks in section (if unavailable, fallback to hitCount roughly scaled)
            int totalChunksInSection = Math.max(1, estimateChunks(section.getCleansedText())); 

            //Uses log1p so early hits help a lot, later hits help less (diminishing returns).
            //Normalized roughly to [0, 0.5] around 10 hits.
            double coverageP1 = 0.5 * (Math.log1p(hitCount) / Math.log1p(10.0));
            double coverageP2 = 0.5 * ((double) hitCount / Math.max(1, totalChunksInSection));
            // Keep coverage bounded to avoid large-section score explosions.
            coverageP1 = Math.min(0.5, Math.max(0.0, coverageP1));
            //Measures what portion of the section is matched.
            //Also capped to max 0.5 contribution. 5 hits in a 10-chunk section is stronger than 5 hits in a 200-chunk section.
            coverageP2 = Math.min(0.5, Math.max(0.0, coverageP2));
            double coverage = Math.min(1.0, coverageP1 + coverageP2);

            String text = section.getCleansedText();
            // Token-based lexical: score by fraction of query terms present (handles natural typing, no exact punctuation)
            double lexicalNorm = computeTokenLexicalScore(query, text);
            // Perfect Match Bonus: If head-term matches exactly, boost lexical score to maximum
            if (text != null && text.equalsIgnoreCase(query)) {
               lexicalNorm = 1.0;
            }

            // Hybrid Gate
            double hybridBonus = (vectorStrength >= 0.55 && lexicalNorm >= 0.35) ? 1.0 : 0.0;

            // finalScore: vector + coverage (configurable, lower = less generic dominance) + lexical token overlap
            double finalScore = 0.47 * vectorStrength + coverageWeight * coverage + 0.25 * lexicalNorm + 0.05 * hybridBonus;
            double pathAlignment = computePathAlignmentScore(
                    query,
                    baseComponentPath,
                    section.getSectionPath(),
                    section.getSectionUri(),
                    section.getOriginalFieldName());
            finalScore += 0.18 * pathAlignment;

            // Intent-aware deterministic adjustment for compare queries:
            // - boost compare/spec/model sections
            // - penalize analytics/a11y-only sections that often pollute compare results
            String sectionPathForIntent = section.getSectionPath();
            if (!StringUtils.hasText(sectionPathForIntent)) {
                sectionPathForIntent = section.getSectionUri();
            }
            String fieldNameForIntent = section.getOriginalFieldName();
            finalScore += intentAdjustment(intentProfile, query, sectionPathForIntent, section.getOriginalFieldName());
            finalScore += queryScopeAdjustment(queryScope, sectionPathForIntent, section.getOriginalFieldName());
            String lowerPathForIntent = sectionPathForIntent == null ? "" : sectionPathForIntent.toLowerCase(java.util.Locale.ROOT);
            String lowerFieldForIntent = fieldNameForIntent == null ? "" : fieldNameForIntent.toLowerCase(java.util.Locale.ROOT);
            boolean analyticsSection = lowerPathForIntent.contains("/analytics") || lowerFieldForIntent.contains("analytics");
            if (analyticsSection) {
                // Generic quality guardrail: analytics rows are usually telemetry/context shells,
                // not direct answer content for end-user semantic questions.
                finalScore -= 0.18;
            }
            if (pathAlignment < 0.20 && lexicalNorm < 0.40) {
                // Generic guardrail: if neither textual nor structural alignment is present,
                // avoid letting broad semantic drift dominate top ranks.
                finalScore -= 0.10;
            }
            finalScore = Math.min(1.0, Math.max(0.0, finalScore));

            logger.info("Hybrid math for section '{}': maxSim={}, meanTop3={}, vecStr={}, hitCount={}, cov={}, lex={}, pathAlign={}, bonus={}, finalScore={}",
                    baseComponentPath, String.format("%.3f", maxSim), String.format("%.3f", meanTop3Sim), 
                    String.format("%.3f", vectorStrength), hitCount, String.format("%.3f", coverage), 
                    lexicalNorm, String.format("%.3f", pathAlignment), hybridBonus, String.format("%.3f", finalScore));

            scoredSections.add(new SectionScore(section, finalScore, hitCount, baseComponentPath, pathAlignment));
        }

        // Sort by final hybrid score descending
        scoredSections.sort(Comparator
                .comparingDouble(SectionScore::getScore).reversed()
                .thenComparing(Comparator.comparingDouble(SectionScore::getPathAlignment).reversed()));

        // Deterministic compare-intent ordering guardrail:
        // ensure compare sections for the requested product family surface first.
        boolean useDeterministicSort = intentProfile.deterministicSort() || queryScope.urlQuery();
        if (useDeterministicSort) {
            scoredSections.sort((a, b) -> {
                int sa = scopePriority(queryScope, a.section);
                int sb = scopePriority(queryScope, b.section);
                if (sa != sb) return Integer.compare(sa, sb);
                int pa = intentPriority(intentProfile, query, a.section);
                int pb = intentPriority(intentProfile, query, b.section);
                if (pa != pb) return Integer.compare(pa, pb);
                int pathCmp = Double.compare(b.pathAlignment, a.pathAlignment);
                if (pathCmp != 0) return pathCmp;
                return Double.compare(b.score, a.score);
            });
        }

        // LLM Re-rank the full requested result set (up to `limit` sections)
        int rerankLimit = Math.min(limit, scoredSections.size());
        List<SectionScore> topKForRerank = new ArrayList<>(scoredSections.subList(0, rerankLimit));

        // 4. LLM re-ranking is force-disabled for deterministic ranking behavior.
        // Keep pure hybrid ordering here regardless of runtime property overrides.

        // Final ordering for response:
        // - compare intent: keep deterministic compare-priority guardrail
        // - non-compare: pure score desc
        if (useDeterministicSort) {
            topKForRerank.sort((a, b) -> {
                int sa = scopePriority(queryScope, a.section);
                int sb = scopePriority(queryScope, b.section);
                if (sa != sb) return Integer.compare(sa, sb);
                int pa = intentPriority(intentProfile, query, a.section);
                int pb = intentPriority(intentProfile, query, b.section);
                if (pa != pb) return Integer.compare(pa, pb);
                int pathCmp = Double.compare(b.pathAlignment, a.pathAlignment);
                if (pathCmp != 0) return pathCmp;
                return Double.compare(b.score, a.score);
            });
        } else {
            topKForRerank.sort(Comparator
                    .comparingDouble(SectionScore::getScore).reversed()
                    .thenComparing(Comparator.comparingDouble(SectionScore::getPathAlignment).reversed()));
        }

        // Debug visibility: log final ranked list after all ordering logic.
        // This prevents confusion when per-section hybrid math looks right but downstream sorting
        // or grouping changes final rank order.
        int debugTopN = Math.min(10, topKForRerank.size());
        for (int i = 0; i < debugTopN; i++) {
            SectionScore ss = topKForRerank.get(i);
            String path = ss.baseComponentPath;
            if (!StringUtils.hasText(path) && ss.section != null) {
                path = ss.section.getSectionPath();
            }
            if (!StringUtils.hasText(path) && ss.section != null) {
                path = ss.section.getSectionUri();
            }
            int resolvedIntentPriority = useDeterministicSort
                    ? intentPriority(intentProfile, query, ss.section)
                    : -1;
            int resolvedScopePriority = queryScope.urlQuery() ? scopePriority(queryScope, ss.section) : -1;
            logger.info("Final rank {}: path='{}', score={}, pathAlign={}, hits={}, intent={}, intentPriority={}, scopePriority={}",
                    i + 1,
                    path,
                    String.format("%.3f", ss.score),
                    String.format("%.3f", ss.pathAlignment),
                    ss.hitCount,
                    intent.name(),
                    resolvedIntentPriority,
                    resolvedScopePriority);
        }

        // 5. Section-Pack Assembly
        // Fetch ALL sibling fields by sectionPath (shared parent) — this is far more reliable than
        // sectionUri because every field (title, copy, image, url, CTA) in a section shares the *same*
        // sectionPath, even if their individual sectionUris differ or weren't hit by the vector search.
        List<SemanticSectionResultDto> finalResults = new ArrayList<>();
        int returnLimit = Math.min(limit, topKForRerank.size());

        // Collect sectionPaths for the top results
        Set<String> topSectionPaths = new HashSet<>();
        for (int i = 0; i < returnLimit; i++) {
            SectionScore ss = topKForRerank.get(i);
            String sp = ss.section.getSectionPath();
            if (StringUtils.hasText(ss.baseComponentPath)) {
                sp = ss.baseComponentPath;
            }
            // For compare-intent, fetch pack fragments from the canonical compare root
            // so we don't anchor cards to a random compare child node.
            if (isCompareIntent && StringUtils.hasText(ss.baseComponentPath)) {
                String lower = ss.baseComponentPath.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("/compare")) {
                    sp = ss.baseComponentPath;
                }
            }
            if (sp != null && !sp.isBlank()) topSectionPaths.add(sp);
        }

        // Fetch ALL sections for parent paths plus descendants (e.g. banner-card-section-items/apple-pencil)
        // so header/bodyCopy for child cards (Apple Pencil, Keyboards) are included.
        List<ConsolidatedEnrichedSection> allSections = new ArrayList<>();
        java.util.Map<UUID, ConsolidatedEnrichedSection> seen = new java.util.HashMap<>();
        for (String path : topSectionPaths) {
            for (ConsolidatedEnrichedSection s : consolidatedEnrichedSectionRepository.findAllBySectionPathOrDescendants(path)) {
                if (s.getId() != null && seen.putIfAbsent(s.getId(), s) == null) allSections.add(s);
            }
        }
        Map<String, List<ConsolidatedEnrichedSection>> fragmentsByBaseComponent = allSections.stream()
                .filter(s -> s.getSectionUri() != null)
                .collect(Collectors.groupingBy(
                        s -> getBaseComponentPath(s.getSectionUri()),
                        java.util.stream.Collectors.toCollection(ArrayList::new)));

        // Context Window Expansion:
        // For HTML content sections (html-content-section[N]), also pull in adjacent sections
        // (±CONTEXT_WINDOW positions) from the same page so that product headings appear
        // alongside their associated feature/spec content.
        final int CONTEXT_WINDOW = contextWindowSize;
        final java.util.regex.Pattern HTML_SECTION_PATTERN =
            java.util.regex.Pattern.compile("(.*html-content-section\\[)(\\d+)(\\].*)");

        // Collect source URIs for all top-K hits that are html-content-section based
        Set<String> pageSourceUris = new HashSet<>();
        Map<String, Integer> sectionPathToIndex = new HashMap<>(); // cached section-number lookups
        for (SectionScore ss : topKForRerank.subList(0, returnLimit)) {
            String sp = ss.section.getSectionPath();
            if (sp != null) {
                java.util.regex.Matcher m = HTML_SECTION_PATTERN.matcher(sp);
                if (m.matches()) {
                    sectionPathToIndex.put(sp, Integer.parseInt(m.group(2)));
                    String srcUri = ss.section.getSourceUri();
                    if (srcUri != null) pageSourceUris.add(srcUri);
                }
            }
        }

        if (!pageSourceUris.isEmpty()) {
            // Fetch all sections for the same pages (may be large; bounded by page content size)
            List<ConsolidatedEnrichedSection> pageSections =
                    consolidatedEnrichedSectionRepository.findAllBySourceUriIn(new ArrayList<>(pageSourceUris));

            // Build a lookup: sectionPath → section index number (for page sections)
            Map<String, Integer> pageSectionNums = new HashMap<>();
            for (ConsolidatedEnrichedSection s : pageSections) {
                if (s.getSectionPath() != null) {
                    java.util.regex.Matcher m = HTML_SECTION_PATTERN.matcher(s.getSectionPath());
                    if (m.matches()) {
                        pageSectionNums.put(s.getSectionPath(), Integer.parseInt(m.group(2)));
                    }
                }
            }

            // For each top-K hit, expand its fragment group with nearby sections
            for (int i = 0; i < returnLimit; i++) {
                SectionScore ss = topKForRerank.get(i);
                String hitPath = ss.section.getSectionPath();
                Integer hitIdx = sectionPathToIndex.get(hitPath);
                if (hitIdx == null) continue; // Not an html-content-section hit

                String hitSourceUri = ss.section.getSourceUri();
                String baseComponentPath = ss.baseComponentPath;

                // Extract the page base (prefix before section number) to avoid cross-page matches
                java.util.regex.Matcher hitMatcher = HTML_SECTION_PATTERN.matcher(hitPath);
                if (!hitMatcher.matches()) continue;
                String pageBase = hitMatcher.group(1); // e.g. "/en_US/airpods/html-content-section["

                List<ConsolidatedEnrichedSection> fragmentGroup =
                        fragmentsByBaseComponent.computeIfAbsent(baseComponentPath, k -> new ArrayList<>());
                Set<String> existingPaths = fragmentGroup.stream()
                        .map(ConsolidatedEnrichedSection::getSectionPath)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());

                // Detect if this hit is already scoped to a semantic region slug
                // (i.e. path has pattern html-content-section[N]/<region-slug>/<fieldKey>).
                // When a region slug is present the section is already a complete semantic unit —
                // expanding ±CONTEXT_WINDOW would pull in unrelated neighboring spec rows.
                // group(3) = everything after the number, e.g. "]/in-the-box/techSpecsRowHeader001"
                String suffixAfterIdx = hitMatcher.group(3); // e.g. "]/in-the-box/fieldKey"
                // Strip the leading "] and count remaining segments
                String strippedSuffix = suffixAfterIdx.replaceFirst("^\\]/?", ""); // "in-the-box/fieldKey"
                boolean hasRegionSlug = strippedSuffix.contains("/"); // two or more segments → region slug present

                if (!hasRegionSlug) {
                    // No region slug — use context window expansion (AEM JSON product pages)
                    for (ConsolidatedEnrichedSection candidate : pageSections) {
                        String candPath = candidate.getSectionPath();
                        Integer candIdx = pageSectionNums.get(candPath);
                        if (candIdx == null) continue;
                        if (!java.util.Objects.equals(candidate.getSourceUri(), hitSourceUri)) continue;
                        if (!candPath.startsWith(pageBase)) continue; // different page base — skip
                        if (existingPaths.contains(candPath)) continue; // already in this group
                        if (Math.abs(candIdx - hitIdx) > CONTEXT_WINDOW) continue; // outside window
                        // Skip blank text
                        if (candidate.getCleansedText() == null || candidate.getCleansedText().isBlank()) continue;

                        fragmentGroup.add(candidate);
                        existingPaths.add(candPath);
                    }
                } else {
                    // Semantic region section — load ALL siblings sharing the same region prefix.
                    // hitPath = .../html-content-section[35]/in-the-box/techSpecsRowHeader001
                    // regionPrefix = .../html-content-section[35]/in-the-box
                    String regionPrefix = hitPath.contains("/")
                            ? hitPath.substring(0, hitPath.lastIndexOf('/'))
                            : hitPath;
                    for (ConsolidatedEnrichedSection candidate : pageSections) {
                        String candPath = candidate.getSectionPath();
                        if (candPath == null) continue;
                        if (!java.util.Objects.equals(candidate.getSourceUri(), hitSourceUri)) continue;
                        // Match all items within this region (including the field key segment)
                        if (!candPath.startsWith(regionPrefix)) continue;
                        if (existingPaths.contains(candPath)) continue;
                        if (candidate.getCleansedText() == null || candidate.getCleansedText().isBlank()) continue;
                        fragmentGroup.add(candidate);
                        existingPaths.add(candPath);
                    }
                }
                // For semantic region sections (hasRegionSlug=true): fragments already loaded from
                // findAllBySectionPathIn above — all items sharing the same sectionPath (region scope).

                // Backward Headline Search (wider window):
                // The compare table places product headings (airPodsPro3Headline006) 10-20 sections
                // BEFORE their feature cells. Look backwards up to HEADLINE_WINDOW sections to find
                // the nearest headline/subheadline and surface the product name in the result card.
                final int HEADLINE_WINDOW = headlineWindowSize;
                ConsolidatedEnrichedSection nearestHeadline = null;
                int nearestHeadlineDist = Integer.MAX_VALUE;
                for (ConsolidatedEnrichedSection candidate : pageSections) {
                    String candPath = candidate.getSectionPath();
                    Integer candIdx = pageSectionNums.get(candPath);
                    if (candIdx == null) continue;
                    if (!java.util.Objects.equals(candidate.getSourceUri(), hitSourceUri)) continue;
                    if (!candPath.startsWith(pageBase)) continue;
                    if (existingPaths.contains(candPath)) continue;
                    if (candidate.getCleansedText() == null || candidate.getCleansedText().isBlank()) continue;
                    // Only look backward (lower index) — headlines precede features on Apple pages
                    int dist = hitIdx - candIdx;
                    if (dist <= 0 || dist > HEADLINE_WINDOW) continue;
                    // Is this a heading field? Check against configurable keyword list
                    String fieldName = candidate.getOriginalFieldName();
                    if (fieldName == null) continue;
                    String fn = fieldName.toLowerCase();
                    boolean isHeadline = headlineFieldKeywords.stream().anyMatch(fn::contains);
                    if (!isHeadline) continue;
                    if (dist < nearestHeadlineDist) {
                        nearestHeadlineDist = dist;
                        nearestHeadline = candidate;
                    }
                }
                if (nearestHeadline != null) {
                    // Prepend so the headline appears first in the content list
                    fragmentGroup.add(0, nearestHeadline);
                    existingPaths.add(nearestHeadline.getSectionPath());
                }
            }
        }

        for (int i = 0; i < returnLimit; i++) {
            SectionScore ss = topKForRerank.get(i);
            String baseComponentPath = ss.baseComponentPath;
            
            SemanticSectionResultDto dto = new SemanticSectionResultDto();
            dto.setRank(i + 1);
            dto.setHitCount(ss.hitCount);
            
            // Use a representative non-CTA fragment when possible so section cards do not
            // default to CTA rows like "learn more" for compare intent.
            ConsolidatedEnrichedSection representative = pickRepresentativeFragment(
                    fragmentsByBaseComponent.getOrDefault(baseComponentPath, List.of(ss.section)),
                    ss.section,
                    isCompareIntent
            );

            // Store FULL path here for UI and image logic in SearchController.
            dto.setSectionPath(representative.getSectionPath());
            // Set the sectionUri to the base component path so SearchController can match images correctly
            dto.setSectionUri(baseComponentPath);
            dto.setSourceUrl(representative.getSourceUri());
            dto.setFinalScore(ss.score);

            // Snippet: best-matching chunk text (trimmed to ~200 chars for display)
            String rawSnippet = representative.getCleansedText();
            if (rawSnippet != null && !rawSnippet.isBlank()) {
                dto.setSnippet(rawSnippet.length() > 200 ? rawSnippet.substring(0, 200) + "…" : rawSnippet);
                dto.setMatchedFieldName(representative.getOriginalFieldName());
            }

            // Unique set to ensure we don't duplicate identical nested fragments
            Set<String> uniqueContentFingerprints = new HashSet<>();
            List<ContentRoleDto> contentList = new ArrayList<>();
            // Gather fragments for this hit. Two strategies:
            // (1) html-content-section: use fragmentsByBaseComponent (includes context-window-expanded neighbors)
            // (2) AEM sections (banner-card-section, etc.): use section_path hierarchy to include
            //     descendant cards (e.g. banner-card-section-items/apple-pencil header/bodyCopy)
            String hitSectionPath = ss.section.getSectionPath();
            // Keep compare result packs scoped to canonical compare root when available.
            if (isCompareIntent && StringUtils.hasText(baseComponentPath)) {
                String lower = baseComponentPath.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("/compare")) {
                    hitSectionPath = baseComponentPath;
                }
            }
            final String effectiveHitSectionPath = hitSectionPath;
            List<ConsolidatedEnrichedSection> fragments;
            if (effectiveHitSectionPath != null && HTML_SECTION_PATTERN.matcher(effectiveHitSectionPath).matches()) {
                java.util.regex.Matcher m = HTML_SECTION_PATTERN.matcher(effectiveHitSectionPath);
                m.matches();
                String strippedSuffix = m.group(3).replaceFirst("^\\]/?", "");
                boolean hasRegionSlug = strippedSuffix.contains("/");
                if (!hasRegionSlug) {
                    // Context-window case: use fragmentGroup (has [N±1] neighbors)
                    fragments = new ArrayList<>(fragmentsByBaseComponent.getOrDefault(baseComponentPath, List.of(ss.section)));
                } else {
                    // Region-scoped case: expand to the semantic region prefix so table/list siblings
                    // (e.g. .../capacity/capacityListColumn001, .../capacity/capacityListItem001)
                    // are packed together instead of anchoring to a single leaf field.
                    String regionPrefix = effectiveHitSectionPath.contains("/")
                            ? effectiveHitSectionPath.substring(0, effectiveHitSectionPath.lastIndexOf('/'))
                            : effectiveHitSectionPath;
                    fragments = allSections.stream().filter(s -> {
                        String sp = s.getSectionPath();
                        if (sp == null) return false;
                        return regionPrefix.equals(sp)
                                || sp.startsWith(regionPrefix + "/")
                                || sp.startsWith(regionPrefix + "-");
                    }).collect(Collectors.toCollection(ArrayList::new));
                }
            } else if (effectiveHitSectionPath != null) {
                fragments = allSections.stream().filter(s -> {
                    String sp = s.getSectionPath();
                    if (sp == null) return false;
                    return effectiveHitSectionPath.equals(sp)
                            || sp.startsWith(effectiveHitSectionPath + "/")
                            || sp.startsWith(effectiveHitSectionPath + "-");
                }).collect(Collectors.toCollection(ArrayList::new));
            } else {
                fragments = new ArrayList<>(fragmentsByBaseComponent.getOrDefault(baseComponentPath, List.of(ss.section)));
            }
            if (fragments.isEmpty()) fragments.add(ss.section);
            // Preserve natural source sequence (path/uri natural-order) instead of "best-hit first".
            fragments.sort(this::compareFragmentsInDisplayOrder);
            List<ConsolidatedEnrichedSection> orderedFragments = new ArrayList<>(fragments);

            // Pass 1: collect url-type fragments indexed by section-path prefix (shared parent component path)
            // e.g. "/en_US/airpods/html-content-section[88]/airPodsMaxBuyStrip001" → "/us/shop/..."
            Map<String, String> urlByPrefix = new HashMap<>();
            for (ConsolidatedEnrichedSection fragment : fragments) {
                String role = fragment.getOriginalFieldName();
                String text = fragment.getCleansedText();
                if (role == null || text == null || text.isBlank()) continue;
                if (!role.equals("url")) continue;

                // Strip suffix to find parent prefix (for sibling matching)
                String sp = fragment.getSectionUri();
                if (sp == null) sp = fragment.getSectionPath();
                if (sp != null) {
                    urlByPrefix.put(sp, text.trim()); // Direct match (child object)
                    String prefix = sp.contains("/") ? sp.substring(0, sp.lastIndexOf('/')) : sp;
                    urlByPrefix.put(prefix, text.trim()); // Prefix match (sibling)
                }
            }

            // Pass 2: build content list, skipping noise fields (ordered so matching fragment is first)
            int nonCtaRows = 0;
            for (ConsolidatedEnrichedSection fragment : orderedFragments) {
                // Filter out analytics and accessibility text fields
                if (fragment.getCleansedText() != null && !fragment.getCleansedText().isBlank()) {
                    String role = fragment.getOriginalFieldName();
                    if (role != null) {
                        String roleLower = role.toLowerCase();
                        // Skip noise fields: analytics, accessibility, url, footnote, image/icon/media types.
                        // Configurable via search.context.excluded-field-keywords in application.properties.
                        boolean shouldSkip = excludedFieldKeywords.stream().anyMatch(kw -> {
                            if (kw.endsWith("_")) return roleLower.startsWith(kw);
                            return roleLower.contains(kw);
                        });
                        boolean isA11yCandidate = roleLower.contains("a11y") || roleLower.contains("accessibility");
                        // Intent profile can allow a11y rows when they carry useful answer attributes (e.g., colors).
                        if (roleLower.startsWith("analytics")
                                || (shouldSkip && !(intentProfile.keepA11yRows() && isA11yCandidate))) {
                            continue;
                        }
                    }
                    String text = fragment.getCleansedText();
                    String outerRoleLower = role == null ? "" : role.toLowerCase(java.util.Locale.ROOT);
                    boolean isCtaRole = outerRoleLower.contains("cta")
                            || outerRoleLower.contains("calltoaction")
                            || outerRoleLower.contains("action")
                            || outerRoleLower.contains("button")
                            || outerRoleLower.contains("link");
                    String outerTextLower = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
                    boolean genericLearnMore = outerTextLower.equals("learn more") || outerTextLower.startsWith("learn more ");
                    String fingerprint = role + ":" + text;
                    if (uniqueContentFingerprints.add(fingerprint)) {
                        // For linkable fields, resolve their paired URL href
                        String href = null;
                        if (role != null) {
                            String linkRoleLower = role.toLowerCase();
                            boolean isCta = linkRoleLower.contains("cta")
                                    || linkRoleLower.contains("calltoaction")
                                    || linkRoleLower.contains("action")
                                    || linkRoleLower.contains("button")
                                    || linkRoleLower.contains("link");
                            // Some CMS payloads store CTA labels under generic roles like "text".
                            // If the text itself looks like a CTA ("Learn more", "Buy", "Shop", etc.),
                            // still try to pair it with its sibling URL.
                            String linkTextLower = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
                            boolean ctaLikeText = linkTextLower.equals("learn more")
                                    || linkTextLower.equals("buy")
                                    || linkTextLower.equals("shop")
                                    || linkTextLower.equals("get started")
                                    || linkTextLower.equals("view pricing")
                                    || linkTextLower.equals("compare")
                                    || linkTextLower.equals("read more")
                                    || linkTextLower.startsWith("learn more ");
                            String sp = fragment.getSectionUri();
                            if (sp == null) sp = fragment.getSectionPath();
                            String pairedHref = null;
                            if (sp != null) {
                                // Try direct path match first (AEM hierarchy), then fallback to sibling prefix (HTML)
                                pairedHref = urlByPrefix.get(sp);
                                if (pairedHref == null) {
                                    String prefix = sp.contains("/") ? sp.substring(0, sp.lastIndexOf('/')) : sp;
                                    pairedHref = urlByPrefix.get(prefix);
                                }
                                // If href is a relative path, make it an absolute Apple URL
                                if (pairedHref != null && pairedHref.startsWith("/")) {
                                    pairedHref = "https://www.apple.com" + pairedHref;
                                }
                            }

                            // Navigation headings/titles are often clickable labels with sibling URL objects
                            // (e.g. chapter-nav items like "iPad Pro", "iPad Air", "iPad mini").
                            boolean isHeadingLike = linkRoleLower.contains("heading")
                                    || linkRoleLower.equals("title")
                                    || linkRoleLower.equals("label")
                                    || linkRoleLower.equals("text");
                            boolean isChapterNavNode = sp != null && sp.toLowerCase(java.util.Locale.ROOT).contains("chapter-nav");

                            if ((isCta || ctaLikeText) || (isHeadingLike && isChapterNavNode)) {
                                href = pairedHref;
                            }
                        }
                        // For compare intent, keep content rows focused on comparison/spec data first.
                        // We still retain some CTA links, but avoid CTA-only packs.
                        if (isCompareIntent && isCtaRole && genericLearnMore && nonCtaRows >= 2) {
                            continue;
                        }

                        contentList.add(new ContentRoleDto(role, text, href));
                        if (!isCtaRole) {
                            nonCtaRows++;
                        }
                    }
                }
            }
            dto.setContent(contentList);
            finalResults.add(dto);
        }

        return finalResults;
    }


    /**
     * Uses Bedrock to score the relevance of context packs against a user query on a 0-10 scale.
     * Example: reRankWithLLM("best ipad", sections) currently no-ops because rerank is force-disabled.
     */
    private void reRankWithLLM(String query, List<SectionScore> topKSections) {
        // Force-disabled: keep method as no-op to avoid accidental invocation from other paths.
        return;
        /*
        if (topKSections.isEmpty()) return;

        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are an expert search relevance rater. Score each of the following sections from 0 to 10 based on how well they directly answer the user query.\n");
            prompt.append("User Query: \"").append(query).append("\"\n\n");
            
            for (int i = 0; i < topKSections.size(); i++) {
                prompt.append("--- Section ID: ").append(i).append(" ---\n");
                prompt.append(topKSections.get(i).section.getCleansedText().substring(0, Math.min(500, topKSections.get(i).section.getCleansedText().length()))).append("...\n");
            }
            
            prompt.append("\nOutput ONLY JSON array of scores in the exact order of the sections. Example output:\n[9.5, 4.0, 7.2]");

            String llmResponse = bedrockEnrichmentService.invokeChatForText(prompt.toString(), 256);
            
            // Parse JSON array — Claude sometimes wraps it in explanatory text.
            // Extract only the [...] portion before attempting to deserialize.
            String rawResponse = llmResponse;
            int arrayStart = rawResponse.indexOf('[');
            int arrayEnd   = rawResponse.lastIndexOf(']');
            if (arrayStart != -1 && arrayEnd > arrayStart) {
                rawResponse = rawResponse.substring(arrayStart, arrayEnd + 1);
            }
            Double[] llmScores;
            try {
                 llmScores = objectMapper.readValue(rawResponse, Double[].class);
                 if (llmScores != null && llmScores.length == topKSections.size()) {
                    // Update scores: We blend the LLM score (normalized to 0-1) with the hybrid score
                    for (int i = 0; i < topKSections.size(); i++) {
                        double oldScore = topKSections.get(i).score;
                        double llmNorm = Math.min(10.0, Math.max(0.0, llmScores[i])) / 10.0;
                        // When hybrid score is very high (>= 0.85), trust it more — LLM can misjudge
                        // e.g. "ipad essentials" query + banner-card-section "iPad essentials." headline
                        double llmWeight = oldScore >= 0.85 ? 0.2 : 0.6;
                        double hybridWeight = 1.0 - llmWeight;
                        topKSections.get(i).score = (llmWeight * llmNorm) + (hybridWeight * oldScore);
                        logger.info("LLM Re-rank for section '{}': oldScore={}, llmRaw={}, newScore={}",
                            topKSections.get(i).section.getSectionUri(), 
                            String.format("%.3f", oldScore), llmScores[i], 
                            String.format("%.3f", topKSections.get(i).score));
                    }
                 }
            } catch (Exception px) {
                logger.warn("Failed to parse LLM re-rank scores, falling back to pure hybrid scores. Raw response: {}", rawResponse);
            }

        } catch (Exception e) {
            logger.warn("LLM Re-ranking skipped due to error: {}", e.getMessage());
        }
        */
    }

    /** Stopwords for token-based lexical scoring (same as ContentChunkRepositoryImpl). */
    private static final Set<String> LEXICAL_STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "to", "of", "in", "for", "on", "with", "at", "by",
            "from", "it", "its", "be", "been", "being", "and", "or", "but");

    /**
     * Token-based lexical score: fraction of query terms present in text.
     * Handles natural typing (no exact punctuation). E.g. "why apple best place buy mac" vs stored
     * "Why Apple is the best place to buy Mac." → 6/6 tokens → 1.0.
     * Example: computeTokenLexicalScore("iphone chip", "A19 Pro chip in iPhone 17 Pro") ≈ 1.0.
     */
    private static double computeTokenLexicalScore(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) return 0.0;
        List<String> tokens = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+")
                .splitAsStream(query.trim().toLowerCase())
                .filter(s -> s.length() >= 2 && !LEXICAL_STOPWORDS.contains(s))
                .distinct()
                .collect(Collectors.toList());
        if (tokens.isEmpty()) return 0.0;
        String textLower = text.toLowerCase(java.util.Locale.ROOT);
        double matchedWeight = 0.0;
        double totalWeight = 0.0;
        for (String token : tokens) {
            double weight = tokenWeight(token);
            totalWeight += weight;
            if (textLower.contains(token)) {
                matchedWeight += weight;
            }
        }
        if (totalWeight <= 0.0) return 0.0;
        return Math.min(1.0, matchedWeight / totalWeight);
    }

    /**
     * Returns per-token weight for lexical/path alignment scoring.
     * Example: tokenWeight("iphone")=0.5, tokenWeight("capacity")=1.5, tokenWeight("17")=0.4.
     */
    private static double tokenWeight(String token) {
        if (!StringUtils.hasText(token)) {
            return 0.0;
        }
        if (token.chars().allMatch(Character::isDigit)) {
            return 0.4;
        }
        if (GENERIC_QUERY_TOKENS.contains(token)) {
            return 0.5;
        }
        return 1.5;
    }

    /**
     * Roughly estimates chunk count from text length.
     * Example: estimateChunks(800-char text)=2.
     */
    private int estimateChunks(String text) {
        if (text == null) return 1;
        // Approximation: 1 chunk is roughly 400 chars.
        return Math.max(1, text.length() / 400);
    }

    private enum QueryIntent {
        GENERAL,
        COMPARE,
        COLOR
    }

    private record IntentProfile(
            QueryIntent intent,
            boolean deterministicSort,
            boolean keepA11yRows,
            List<String> positivePathTokens,
            List<String> negativePathTokens,
            double positiveBoost,
            double negativePenalty,
            double domainMatchBoost,
            double domainMismatchPenalty
    ) {}

    private record QueryScope(
            String siteToken,
            String pageContext,
            boolean urlQuery
    ) {}

    /**
     * Detects query intent profile bucket.
     * Example: detectIntent("compare iphone 17 and iphone 16") -> COMPARE.
     */
    private QueryIntent detectIntent(String query) {
        if (isCompareIntentQuery(query)) return QueryIntent.COMPARE;
        if (isColorIntentQuery(query)) return QueryIntent.COLOR;
        return QueryIntent.GENERAL;
    }

    /**
     * Resolves scoring/sorting profile for a detected intent.
     * Example: profileForIntent(COLOR) enables deterministic sort and keeps a11y rows.
     */
    private IntentProfile profileForIntent(QueryIntent intent) {
        return switch (intent) {
            case COMPARE -> new IntentProfile(
                    QueryIntent.COMPARE,
                    true,
                    false,
                    List.of("/compare", "compare-", "models", "specs"),
                    List.of("/analytics", "/a11y/", "-a11y"),
                    0.15,
                    0.12,
                    0.08,
                    0.12
            );
            case COLOR -> new IntentProfile(
                    QueryIntent.COLOR,
                    true,
                    true,
                    List.of("/finish", "finish-", "/color", "color-", "colour"),
                    List.of("/analytics"),
                    0.20,
                    0.30,
                    0.08,
                    0.08
            );
            case GENERAL -> new IntentProfile(
                    QueryIntent.GENERAL,
                    false,
                    false,
                    List.of(),
                    List.of(),
                    0.0,
                    0.0,
                    0.0,
                    0.0
            );
        };
    }

    /**
     * Computes additive score delta from intent profile and section metadata.
     * Example: compare path with "/compare" gets positive boost in COMPARE intent.
     */
    private double intentAdjustment(IntentProfile profile, String query, String sectionPathOrUri, String fieldName) {
        if (profile.intent() == QueryIntent.GENERAL) return 0.0;
        String path = sectionPathOrUri == null ? "" : sectionPathOrUri.toLowerCase(java.util.Locale.ROOT);
        String field = fieldName == null ? "" : fieldName.toLowerCase(java.util.Locale.ROOT);
        String domainToken = extractDomainToken(query);

        boolean positive = profile.positivePathTokens().stream().anyMatch(path::contains)
                || (profile.intent() == QueryIntent.COLOR
                    && (field.contains("color") || field.contains("colour")));
        boolean negative = profile.negativePathTokens().stream().anyMatch(path::contains)
                || field.contains("analytics");
        boolean domainMatch = StringUtils.hasText(domainToken)
                && (path.contains("/" + domainToken) || path.contains(domainToken + "/"));

        double delta = 0.0;
        if (positive) delta += profile.positiveBoost();
        if (negative) delta -= profile.negativePenalty();
        if (StringUtils.hasText(domainToken)) {
            delta += domainMatch ? profile.domainMatchBoost() : -profile.domainMismatchPenalty();
        }

        // Compare intent: keep CTA fragments from dominating ranking.
        if (profile.intent() == QueryIntent.COMPARE) {
            boolean ctaLikeField = field.contains("cta")
                    || field.contains("calltoaction")
                    || field.contains("action")
                    || field.contains("button")
                    || field.contains("link");
            if (ctaLikeField && positive) delta -= 0.05;
        }
        return delta;
    }

    /**
     * Returns deterministic intent priority bucket for tie-breaking.
     * Example: compare+domain-matched section -> priority 0 (best).
     */
    private int intentPriority(IntentProfile profile, String query, ConsolidatedEnrichedSection section) {
        if (section == null) return 4;
        if (profile.intent() == QueryIntent.GENERAL) return 0;
        String path = section.getSectionPath();
        if (!StringUtils.hasText(path)) path = section.getSectionUri();
        String p = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        String field = section.getOriginalFieldName() == null
                ? ""
                : section.getOriginalFieldName().toLowerCase(java.util.Locale.ROOT);
        String domain = extractDomainToken(query);

        boolean positive = profile.positivePathTokens().stream().anyMatch(p::contains)
                || (profile.intent() == QueryIntent.COLOR && (field.contains("color") || field.contains("colour")));
        boolean negative = profile.negativePathTokens().stream().anyMatch(p::contains)
                || field.contains("analytics");
        boolean domainMatch = StringUtils.hasText(domain) && (p.contains("/" + domain) || p.contains(domain + "/"));

        if (positive && domainMatch) return 0;
        if (positive) return 1;
        if (domainMatch && !negative) return 2;
        if (!negative) return 3;
        return 4;
    }

    /**
     * True when query text indicates compare intent.
     * Example: isCompareIntentQuery("ipad vs macbook") -> true.
     */
    private boolean isCompareIntentQuery(String query) {
        if (!StringUtils.hasText(query)) return false;
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return q.contains("compare")
                || q.contains(" vs ")
                || q.contains("difference")
                || q.contains("which model")
                || q.contains("which one");
    }

    /**
     * True when query text indicates color/finish intent.
     * Example: isColorIntentQuery("iphone 17 pro colors") -> true.
     */
    private boolean isColorIntentQuery(String query) {
        if (!StringUtils.hasText(query)) return false;
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return q.contains("color")
                || q.contains("colors")
                || q.contains("colour")
                || q.contains("colours")
                || q.contains("finish")
                || q.contains("available color")
                || q.contains("available colours");
    }

    /**
     * Extracts coarse product-domain token from query.
     * Example: extractDomainToken("best airpods model") -> "airpods".
     */
    private String extractDomainToken(String query) {
        if (!StringUtils.hasText(query)) return null;
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (String token : List.of("airpods", "ipad", "iphone", "mac", "watch", "vision")) {
            if (q.contains(token)) return token;
        }
        return null;
    }

    /**
     * Generic query-to-structure alignment score.
     * Uses section path/uri/field tokens so exact structural terms (e.g., capacity, storage,
     * specific product slugs) can rank high without hardcoded per-page keyword lists.
     * Example: query "capacity iphone 17 pro" aligns strongly with path ".../capacity/...".
     */
    private double computePathAlignmentScore(
            String query,
            String baseComponentPath,
            String sectionPath,
            String sectionUri,
            String fieldName
    ) {
        String structuralText = String.join(" ",
                java.util.Objects.toString(baseComponentPath, ""),
                java.util.Objects.toString(sectionPath, ""),
                java.util.Objects.toString(sectionUri, ""),
                java.util.Objects.toString(fieldName, ""));
        if (!StringUtils.hasText(query) || !StringUtils.hasText(structuralText)) {
            return 0.0;
        }
        List<String> tokens = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+")
                .splitAsStream(query.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(s -> s.length() >= 2 && !LEXICAL_STOPWORDS.contains(s))
                .distinct()
                .collect(Collectors.toList());
        if (tokens.isEmpty()) {
            return 0.0;
        }
        String haystack = structuralText.toLowerCase(java.util.Locale.ROOT);
        double matchedWeight = 0.0;
        double totalWeight = 0.0;
        for (String token : tokens) {
            double weight = tokenWeight(token);
            totalWeight += weight;
            if (haystack.contains(token)) {
                matchedWeight += weight;
            }
        }
        if (totalWeight <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, matchedWeight / totalWeight);
    }

    /**
     * Parses URL query scope into site + pageContext for optional ranking bias.
     * Example: "https://www.apple.com/iphone-17-pro/specs/" -> siteToken=iphone-17-pro, pageContext=specs.
     */
    private QueryScope extractQueryScope(String query) {
        if (!StringUtils.hasText(query)) {
            return new QueryScope(null, null, false);
        }
        String q = query.trim();
        if (q.startsWith("http://") || q.startsWith("https://")) {
            try {
                URI uri = URI.create(q);
                String rawPath = uri.getPath();
                if (!StringUtils.hasText(rawPath)) {
                    return new QueryScope(null, null, true);
                }
                String[] segments = rawPath.split("/");
                List<String> tokens = new ArrayList<>();
                for (String segment : segments) {
                    if (StringUtils.hasText(segment)) {
                        tokens.add(segment.toLowerCase(java.util.Locale.ROOT));
                    }
                }
                if (tokens.isEmpty()) {
                    return new QueryScope(null, null, true);
                }

                int start = 0;
                if (LOCALE_SEGMENT_PATTERN.matcher(tokens.get(0)).matches()) {
                    start = 1;
                }
                String site = tokens.size() > start ? tokens.get(start) : null;
                String context = null;
                if (tokens.size() > start + 1) {
                    String maybeContext = tokens.get(start + 1);
                    if (PAGE_CONTEXT_TOKENS.contains(maybeContext)) {
                        context = maybeContext;
                    }
                }
                // Product root URL without explicit subpage is usually the overview tab.
                if (!StringUtils.hasText(context)) {
                    context = "overview";
                }
                return new QueryScope(site, context, true);
            } catch (Exception ignored) {
                return new QueryScope(null, null, false);
            }
        }

        return new QueryScope(null, null, false);
    }

    /**
     * Checks whether a token appears in path-like separators/boundaries.
     * Example: pathContainsToken("/en_US/iphone-17-pro/specs", "iphone-17-pro") -> true.
     */
    private boolean pathContainsToken(String path, String token) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(token)) {
            return false;
        }
        String p = path.toLowerCase(java.util.Locale.ROOT);
        String t = token.toLowerCase(java.util.Locale.ROOT);
        return p.contains("/" + t + "/")
                || p.endsWith("/" + t)
                || p.contains(t + "/")
                || p.contains("/" + t + "-")
                || p.contains("-" + t + "/");
    }

    /**
     * Applies URL-scope-based score delta (site/pageContext/analytics penalties).
     * Example: scoped site mismatch receives negative delta.
     */
    private double queryScopeAdjustment(QueryScope scope, String sectionPathOrUri, String fieldName) {
        if (scope == null || !scope.urlQuery()) {
            return 0.0;
        }
        String path = sectionPathOrUri == null ? "" : sectionPathOrUri.toLowerCase(java.util.Locale.ROOT);
        String field = fieldName == null ? "" : fieldName.toLowerCase(java.util.Locale.ROOT);

        boolean siteMatch = pathContainsToken(path, scope.siteToken());
        boolean pageMatch = pathContainsToken(path, scope.pageContext());
        boolean analytics = path.contains("/analytics") || field.contains("analytics");

        double delta = 0.0;
        if (StringUtils.hasText(scope.siteToken())) {
            if (siteMatch) {
                delta += 0.22;
            } else {
                delta -= 0.24;
            }
        }
        if (StringUtils.hasText(scope.pageContext())) {
            delta += pageMatch ? 0.12 : -0.10;
        }
        if (analytics) {
            delta -= 0.25;
        }
        return delta;
    }

    /**
     * Produces deterministic scope-priority rank for URL-scoped queries.
     * Example: site+page match non-analytics -> priority 0.
     */
    private int scopePriority(QueryScope scope, ConsolidatedEnrichedSection section) {
        if (scope == null || !scope.urlQuery()) {
            return 0;
        }
        if (section == null) {
            return 5;
        }
        String path = section.getSectionPath();
        if (!StringUtils.hasText(path)) {
            path = section.getSectionUri();
        }
        String p = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        String field = section.getOriginalFieldName() == null
                ? ""
                : section.getOriginalFieldName().toLowerCase(java.util.Locale.ROOT);

        boolean siteMatch = pathContainsToken(p, scope.siteToken());
        boolean pageMatch = pathContainsToken(p, scope.pageContext());
        boolean analytics = p.contains("/analytics") || field.contains("analytics");

        if (siteMatch && pageMatch && !analytics) return 0;
        if (siteMatch && !analytics) return 1;
        if (siteMatch) return 2;
        if (!analytics) return 3;
        return 4;
    }

    /**
     * Chooses the best representative fragment for display/snippet.
     * Prefers non-CTA content so section cards are not anchored to "learn more" links.
     * Example: among [headline, cta-link], headline is selected as representative.
     */
    private ConsolidatedEnrichedSection pickRepresentativeFragment(List<ConsolidatedEnrichedSection> fragments,
            ConsolidatedEnrichedSection fallback, boolean isCompareIntent) {
        if (fragments == null || fragments.isEmpty()) {
            return fallback;
        }
        ConsolidatedEnrichedSection best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ConsolidatedEnrichedSection f : fragments) {
            if (f == null || f.getCleansedText() == null || f.getCleansedText().isBlank()) {
                continue;
            }
            String role = f.getOriginalFieldName() == null ? "" : f.getOriginalFieldName().toLowerCase(java.util.Locale.ROOT);
            int rank;
            if (role.contains("headline") || role.contains("title") || role.contains("heading") || role.contains("subheadline")) {
                rank = 0;
            } else if (role.contains("copy") || role.contains("description") || role.contains("summary")
                    || role.contains("feature") || role.contains("text")) {
                rank = 1;
            } else if (role.contains("cta") || role.contains("button") || role.contains("link") || role.equals("url")) {
                rank = 3;
            } else {
                rank = 2;
            }

            if (isCompareIntent) {
                String sp = f.getSectionPath() == null ? "" : f.getSectionPath().toLowerCase(java.util.Locale.ROOT);
                String txt = f.getCleansedText() == null ? "" : f.getCleansedText().toLowerCase(java.util.Locale.ROOT);
                // Prefer explicit compare/spec/model fragments for compare queries
                if (sp.contains("/compare") || sp.contains("compare-") || sp.contains("models") || sp.contains("specs")) {
                    rank -= 2;
                }
                // De-prioritize generic CTA rows as representative
                if (txt.equals("learn more") || txt.startsWith("learn more ")) {
                    rank += 2;
                }
            }

            if (rank < bestRank) {
                bestRank = rank;
                best = f;
            }
        }
        return best != null ? best : fallback;
    }

    /** Returns true if two fragments represent the same section (same uri, path, text, field).
     * Example: sameFragment(a,b)=true when uri/path/text/field all match exactly.
     */
    private static boolean sameFragment(ConsolidatedEnrichedSection a, ConsolidatedEnrichedSection b) {
        if (a == null || b == null) return a == b;
        return java.util.Objects.equals(a.getSectionUri(), b.getSectionUri())
                && java.util.Objects.equals(a.getSectionPath(), b.getSectionPath())
                && java.util.Objects.equals(a.getCleansedText(), b.getCleansedText())
                && java.util.Objects.equals(a.getOriginalFieldName(), b.getOriginalFieldName());
    }

    /**
     * Deterministic display ordering for content rows:
     * 1) natural order of sectionPath (if available) else sectionUri
     * 2) natural order of sectionUri (tie-break)
     * 3) natural order of field name (final tie-break)
     * Example: ".../item2" is ordered before ".../item10".
     */
    private int compareFragmentsInDisplayOrder(ConsolidatedEnrichedSection a, ConsolidatedEnrichedSection b) {
        String aPath = a == null ? null : a.getSectionPath();
        String bPath = b == null ? null : b.getSectionPath();
        String aUri = a == null ? null : a.getSectionUri();
        String bUri = b == null ? null : b.getSectionUri();
        String aField = a == null ? null : a.getOriginalFieldName();
        String bField = b == null ? null : b.getOriginalFieldName();

        String aPrimary = StringUtils.hasText(aPath) ? aPath : aUri;
        String bPrimary = StringUtils.hasText(bPath) ? bPath : bUri;

        int c1 = naturalCompareNullable(aPrimary, bPrimary);
        if (c1 != 0) return c1;
        int c2 = naturalCompareNullable(aUri, bUri);
        if (c2 != 0) return c2;
        return naturalCompareNullable(aField, bField);
    }

    /**
     * Null-safe wrapper around natural string compare.
     * Example: naturalCompareNullable(null,"a") -> 1.
     */
    private int naturalCompareNullable(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return naturalCompare(a, b);
    }

    /**
     * Natural comparator ("item2" < "item10") for stable source-order rendering.
     * Example: naturalCompare("row2","row10") < 0.
     */
    private int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        int n = a.length();
        int m = b.length();
        while (i < n && j < m) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            boolean da = Character.isDigit(ca);
            boolean db = Character.isDigit(cb);

            if (da && db) {
                int i2 = i;
                while (i2 < n && Character.isDigit(a.charAt(i2))) i2++;
                int j2 = j;
                while (j2 < m && Character.isDigit(b.charAt(j2))) j2++;

                String sa = a.substring(i, i2);
                String sb = b.substring(j, j2);

                String ta = sa.replaceFirst("^0+", "");
                String tb = sb.replaceFirst("^0+", "");
                if (ta.isEmpty()) ta = "0";
                if (tb.isEmpty()) tb = "0";

                if (ta.length() != tb.length()) {
                    return Integer.compare(ta.length(), tb.length());
                }
                int numCmp = ta.compareTo(tb);
                if (numCmp != 0) return numCmp;
                if (sa.length() != sb.length()) {
                    return Integer.compare(sa.length(), sb.length());
                }
                i = i2;
                j = j2;
                continue;
            }

            if (!da && !db) {
                int i2 = i;
                while (i2 < n && !Character.isDigit(a.charAt(i2))) i2++;
                int j2 = j;
                while (j2 < m && !Character.isDigit(b.charAt(j2))) j2++;
                String sa = a.substring(i, i2).toLowerCase(java.util.Locale.ROOT);
                String sb = b.substring(j, j2).toLowerCase(java.util.Locale.ROOT);
                int sCmp = sa.compareTo(sb);
                if (sCmp != 0) return sCmp;
                i = i2;
                j = j2;
                continue;
            }

            return da ? -1 : 1;
        }
        return Integer.compare(n, m);
    }

    /**
     * Strips AEM property and array suffixes from a section URI to find its base component path.
     * For example: /content/dam/.../feature-card/copy -> /content/dam/.../feature-card
     * Example: getBaseComponentPath("/x/chip/title001") -> "/x/chip".
     */
    public static String getBaseComponentPath(String uri) {
        if (uri == null) return null;
        String pattern = "/(?i)[^/-]*(copy|text|title|headline|subheadline|topic|eyebrow|caption|label|cta|calltoaction|action|button|link|description|summary|heading|image|icon|graphic|media|disclaimers|items|accordion|gallery|list|tab|item|row|column|cell)(\\d+)?(\\[\\d+\\])?$";
        String previous;
        String current = uri;
        do {
            previous = current;
            current = current.replaceAll(pattern, "");
        } while (!current.equals(previous));
        
        if (current.endsWith("/") && current.length() > 1) {
            current = current.substring(0, current.length() - 1);
        }
        return current;
    }

    /**
     * Shape-agnostic canonical grouping root:
     * - starts from the existing base path
     * - strips trailing field/leaf segments from section path/uri when detected
     * - prefers the most specific non-leaf candidate
     * Example: canonicalGroupPath("/a/chip/title001","/a/chip/title001",..., "title001") -> "/a/chip".
     */
    private String canonicalGroupPath(String basePath, String sectionPath, String sectionUri, String fieldName) {
        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(basePath)) {
            candidates.add(trimTrailingSlash(basePath));
        }
        for (String raw : List.of(sectionPath, sectionUri, basePath)) {
            if (!StringUtils.hasText(raw)) continue;
            String trimmed = trimTrailingSlash(raw);
            candidates.add(trimmed);
            String strippedByField = stripTrailingFieldSegment(trimmed, fieldName);
            if (StringUtils.hasText(strippedByField)) {
                candidates.add(strippedByField);
            }
            String strippedLeaf = stripTrailingLeafSegment(trimmed);
            if (StringUtils.hasText(strippedLeaf)) {
                candidates.add(strippedLeaf);
            }
        }
        if (candidates.isEmpty()) {
            return basePath;
        }
        String best = candidates.get(0);
        int bestScore = groupingCandidateScore(best);
        for (String candidate : candidates) {
            int score = groupingCandidateScore(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Normalizes a path-like string by removing trailing '/' characters
     * while preserving root-like values such as "/".
     * Example: trimTrailingSlash("/a/b///") -> "/a/b".
     */
    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) return value;
        String out = value;
        while (out.length() > 1 && out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /**
     * Removes the last path segment when it matches the current field name
     * (after alphanumeric normalization). This helps collapse
     * ".../chip/techSpecsRowHeader001" to ".../chip" when the leaf is a field node.
     * Example: stripTrailingFieldSegment("/x/chip/row001","row001") -> "/x/chip".
     */
    private String stripTrailingFieldSegment(String path, String fieldName) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(fieldName)) return null;
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return null;
        String leaf = path.substring(idx + 1);
        String normalizedLeaf = leaf.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
        String normalizedField = fieldName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
        if (!normalizedLeaf.isEmpty() && normalizedLeaf.equals(normalizedField)) {
            return path.substring(0, idx);
        }
        return null;
    }

    /**
     * Removes the last path segment when it looks like a leaf/detail node.
     * Used as a generic fallback when no direct field-name match exists.
     * Example: stripTrailingLeafSegment("/x/chip/techSpecsRowHeader001") -> "/x/chip".
     */
    private String stripTrailingLeafSegment(String path) {
        if (!StringUtils.hasText(path)) return null;
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return null;
        String leaf = path.substring(idx + 1);
        if (isLikelyLeafSegment(leaf)) {
            return path.substring(0, idx);
        }
        return null;
    }

    /**
     * Heuristic leaf detector for path segments that represent detail rows/items
     * rather than semantic group roots. Numeric-heavy and known leaf-pattern
     * names are treated as leaf nodes.
     * Example: isLikelyLeafSegment("capacityListItem001") -> true.
     */
    private boolean isLikelyLeafSegment(String segment) {
        if (!StringUtils.hasText(segment)) return false;
        String s = segment.toLowerCase(java.util.Locale.ROOT);
        if (s.matches(".*\\d{2,}.*")) return true;
        return GENERIC_LEAF_SEGMENT_PATTERN.matcher(s).matches();
    }

    /**
     * Scores a grouping-root candidate path.
     * Higher depth is preferred (more specific), but likely leaf endings are penalized
     * so stable semantic parent nodes win over field-level leaves.
     * Example: groupingCandidateScore("/x/chip") > groupingCandidateScore("/x/chip/row001").
     */
    private int groupingCandidateScore(String path) {
        if (!StringUtils.hasText(path)) return Integer.MIN_VALUE;
        String[] segments = path.split("/");
        int depth = 0;
        for (String seg : segments) {
            if (StringUtils.hasText(seg)) depth++;
        }
        int penalty = 0;
        int idx = path.lastIndexOf('/');
        if (idx >= 0 && idx < path.length() - 1) {
            String leaf = path.substring(idx + 1);
            if (isLikelyLeafSegment(leaf)) {
                penalty = 2;
            }
        }
        return depth - penalty;
    }

    /**
     * Returns canonical compare group path when a path belongs to a compare subtree.
     * Prefers the first available source among basePath, sectionPath, sectionUri.
     * Example: "/en_US/airpods/.../compare/feature001" -> "/en_US/airpods/.../compare".
     */
    private String canonicalCompareGroupPath(String basePath, String sectionPath, String sectionUri) {
        for (String candidate : List.of(basePath, sectionPath, sectionUri)) {
            if (!StringUtils.hasText(candidate)) continue;
            String lower = candidate.toLowerCase(java.util.Locale.ROOT);
            int idx = lower.indexOf("/compare/");
            if (idx >= 0) {
                return candidate.substring(0, idx + "/compare".length());
            }
            if (lower.endsWith("/compare")) {
                return candidate;
            }
        }
        return null;
    }
    /**
     * Helper struct for ranking.
     * Example: SectionScore(score=0.72,pathAlignment=0.9) outranks lower-score peers.
     */
    private static class SectionScore {
        ConsolidatedEnrichedSection section;
        double score;
        int hitCount;
        String baseComponentPath;
        double pathAlignment;
        SectionScore(ConsolidatedEnrichedSection section, double score, int hitCount, String baseComponentPath, double pathAlignment) {
            this.section = section;
            this.score = score;
            this.hitCount = hitCount;
            this.baseComponentPath = baseComponentPath;
            this.pathAlignment = pathAlignment;
        }
        /** Example: getScore() -> 0.734 for a strong match section. */
        public double getScore() { return score; }
        /** Example: getPathAlignment() -> 1.0 when query tokens fully match path/field tokens. */
        public double getPathAlignment() { return pathAlignment; }
    }

}