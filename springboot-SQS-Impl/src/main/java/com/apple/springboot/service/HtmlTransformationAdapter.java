package com.apple.springboot.service;

import com.apple.springboot.dto.UnstructuredIngestionPayload;
import com.apple.springboot.dto.UnstructuredUrlPayload;
import com.apple.springboot.model.UnstructuredDataStore;
import com.apple.springboot.repository.UnstructuredDataStoreRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class HtmlTransformationAdapter {

    private static final Logger logger = LoggerFactory.getLogger(HtmlTransformationAdapter.class);

    /**
     * Bump when HTML→section logic or path contract changes. Mixed into unstructured cache hash only.
     */
    private static final String UNSTRUCTURED_HTML_TRANSFORM_VERSION = "3";

    private final UnstructuredDataStoreRepository unstructuredDataStoreRepository;
    private final DataIngestionService dataIngestionService;
    private final BedrockEnrichmentService bedrockService;
    private final ObjectMapper objectMapper;

    /**
     * Processes a Live URL extraction request natively by downloading the DOM.
     */
    public ObjectNode processLiveUrl(UnstructuredUrlPayload payload) throws Exception {
        logger.info("Initializing Live URL ingestion for: {}", payload.getUrl());

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(payload.getUrl())).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Failed to fetch URL: " + payload.getUrl() + " HTTP " + response.statusCode());
        }

        // Try extracting pageId locally from URL slug if not provided.
        // Canonicalize to first path segment so related sub-pages map to one site/page id:
        // /iphone-17-pro/specs/ -> "iphone-17-pro"
        // /iphone-17-pro/       -> "iphone-17-pro"
        // /                      -> "index"
        String derivedPageId = payload.getPageId();
        String derivedLocale = payload.getLocale();
        if (derivedPageId == null || derivedPageId.isEmpty()) {
            URI uri = URI.create(payload.getUrl());
            String host = uri.getHost();
            
            if (host != null && (derivedLocale == null || derivedLocale.isEmpty())) {
                if (host.endsWith(".com.cn") || host.endsWith(".cn")) {
                    derivedLocale = "zh_CN";
                }
            }

            String path = uri.getPath();
            // Strip leading/trailing slashes
            path = path.replaceAll("^/+|/+$", "");
            if (path.isEmpty()) {
                derivedPageId = "index";
            } else {
                String[] segments = path.split("/");
                int pageIdOffset = 0;
                if (segments.length > 0 && segments[0].matches("^[a-z]{2}(-[a-zA-Z]{2,3})?$")) {
                    if (derivedLocale == null || derivedLocale.isEmpty()) {
                        String storefront = segments[0];
                        if (storefront.length() == 2) {
                            derivedLocale = storefront.toLowerCase(Locale.ROOT) + "_" + storefront.toUpperCase(Locale.ROOT);
                        } else {
                            derivedLocale = storefront.replace("-", "_");
                        }
                    }
                    pageIdOffset = 1;
                }
                derivedPageId = segments.length > pageIdOffset ? segments[pageIdOffset] : "index";
            }
        }

        UnstructuredIngestionPayload downstreamPayload = new UnstructuredIngestionPayload();
        downstreamPayload.setSourceUri(payload.getUrl());
        downstreamPayload.setHtmlContent(response.body());
        downstreamPayload.setPageId(derivedPageId);
        downstreamPayload.setLocale(derivedLocale != null && !derivedLocale.isEmpty() ? derivedLocale : "en_US");

        return processRawHtml(downstreamPayload);
    }

    /**
     * Processes a Raw HTML extraction request with highly deterministic Hash
     * Deduplication.
     */
    private static String computeUnstructuredHtmlMd5Hash(String htmlContent) {
        String raw = htmlContent != null ? htmlContent : "";
        return DigestUtils.md5DigestAsHex(
                (UNSTRUCTURED_HTML_TRANSFORM_VERSION + "|" + raw).getBytes(StandardCharsets.UTF_8));
    }

    public ObjectNode processRawHtml(UnstructuredIngestionPayload payload) throws Exception {
        logger.info("Processing RAW HTML from source: {}", payload.getSourceUri());

        // 1. Versioned MD5 (invalidates cache when UNSTRUCTURED_HTML_TRANSFORM_VERSION bumps)
        String htmlHash = computeUnstructuredHtmlMd5Hash(payload.getHtmlContent());

        // 2. Check Deduplication to bypass LLM completely securely
        var existing = unstructuredDataStoreRepository.findBySourceUriAndHtmlMd5Hash(payload.getSourceUri(), htmlHash);
        if (existing.isPresent()) {
            logger.info(
                    "HTML content has not changed since last ingestion. Utilizing cached mapping and triggering native update.");
            ObjectNode cachedPayload = objectMapper.createObjectNode();
            cachedPayload.put("cleansedId", existing.get().getId().toString());
            cachedPayload.put("message", "Cached DB Hit");
            return cachedPayload;
        }

        // 3. Save Raw Block to DB for Auditing
        UnstructuredDataStore store = new UnstructuredDataStore();
        store.setSourceUri(payload.getSourceUri());
        store.setPageId(payload.getPageId());
        store.setLocale(payload.getLocale());
        store.setRawHtmlContent(payload.getHtmlContent());
        store.setHtmlMd5Hash(htmlHash);
        store.setReceivedAt(OffsetDateTime.now());
        store.setStatus("PROCESSING");
        store = unstructuredDataStoreRepository.save(store);

        // 4. Transform HTML -> JSoup Parsing -> Text Only
        Document doc = Jsoup.parse(payload.getHtmlContent(),
                payload.getSourceUri() != null ? payload.getSourceUri() : "");

        // 4b. AI-Grade Fallback: If raw HTML is dumped without a mapped PageId,
        // natively extract Apple OG / SEO tags!
        if (payload.getPageId() == null || payload.getPageId().isEmpty()) {
            String omnitureName = doc.select("meta[property=analytics-ac-pageName]").attr("content");
            if (omnitureName != null && !omnitureName.isEmpty()) {
                payload.setPageId(omnitureName.split(" - ")[0].trim());
            } else {
                String ogUrl = doc.select("meta[property=og:url]").attr("content");
                if (ogUrl != null && ogUrl.contains("/")) {
                    String[] segments = URI.create(ogUrl).getPath().split("/");
                    payload.setPageId(segments.length > 1 ? segments[1] : "index");
                } else {
                    payload.setPageId("unstructured-webpage");
                }
            }
        }

        String baseUrl = "https://www.apple.com";
        if (payload.getSourceUri() != null && !payload.getSourceUri().isEmpty()) {
            try {
                URI sourceUri = URI.create(payload.getSourceUri());
                baseUrl = sourceUri.getScheme() + "://" + sourceUri.getHost();
            } catch (Exception ignored) {}
        }

        // 1. First, attempt to build a map of CSS classes to real Image URLs
        java.util.Map<String, String> cssClassToImageUrl = new java.util.HashMap<>();

        // Extract inline CSS mapping BEFORE removing <style> tags
        for (org.jsoup.nodes.Element styleElement : doc.select("style")) {
            String cssContent = styleElement.html();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\.([a-zA-Z0-9_-]+)[^\\.\\{]*?\\{[^}]*?(?:background(?:-image)?(?:-repeat)?|-webkit-mask(?:-image)?|mask(?:-image)?|--[a-zA-Z0-9_-]+)\\s*:\\s*(?:[^u]*?)url\\(\\s*['\"]?([^)'\"]+)['\"]?\\s*\\)")
                    .matcher(cssContent);
            while (m.find()) {
                String className = m.group(1);
                String bgUrl = m.group(2);
                if (bgUrl.startsWith("/")) {
                    bgUrl = baseUrl + bgUrl;
                }
                if (className.contains("image") || className.contains("icon") || className.contains("asset") 
                        || className.contains("thumb") || className.contains("logo") || className.contains("graphic")
                        || className.contains("media") || className.contains("poster") || className.contains("hero")
                        || className.contains("background") || className.contains("banner") || className.contains("resource")) {
                    cssClassToImageUrl.put(className, bgUrl);
                    logger.debug("Resolved Inline CSS Sprite mapping: {} -> {}", className, bgUrl);
                }
            }
        }

        doc.select("script, style, footer, noscript, iframe, template").remove();
        doc.select(
                "#globalnav, #ac-gn-segmentbar, #ac-localnav, #ac-globalfooter, .ac-gf-footer, .global-footer, [aria-hidden=true]")
                .remove();
        org.jsoup.nodes.Element rootElement = doc.selectFirst("main");
        if (rootElement == null) {
            rootElement = doc.body();
        }

        // Drill down to bypass useless single-child wrappers (e.g. <div id="main">)
        while (rootElement.childrenSize() == 1) {
            rootElement = rootElement.child(0);
        }

        // PRE-PROCESSING HACK: Apple heavily uses CSS sprites for Images (e.g.,
        // `<figure class="image-airpods-4"></figure>`).
        // Because the tag contains no `src` and no text, LLMs often aggressively delete
        // them to save tokens.
        // We inject a custom attribute `data-image-src` to force the AI to add them.

        // 2. Add external downloaded stylesheets to the map

        org.jsoup.select.Elements stylesheets = doc.select("link[rel=stylesheet]");
        HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

        for (org.jsoup.nodes.Element link : stylesheets) {
            String href = link.attr("href");
            if (!href.isEmpty()) {
                try {
                    String absoluteUrl = href;
                    if (href.startsWith("/")) {
                        absoluteUrl = baseUrl + href;
                    }

                    if (absoluteUrl.startsWith("http")) {
                        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(absoluteUrl)).GET().build();
                        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                        if (res.statusCode() == 200) {
                            logger.info("Successfully downloaded stylesheet: {}", absoluteUrl);
                            String cssContent = res.body();
                            // Find patterns like: .compare-module-wrapper .image-airpods-4 {
                            // background-image: url(/v/.../image.png) } OR background: url(...)
                            // We allow anything before the dot, but capture the right-most class name
                            // before the {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                    "\\.([a-zA-Z0-9_-]+)[^\\.\\{]*?\\{[^}]*?(?:background(?:-image)?(?:-repeat)?|-webkit-mask(?:-image)?|mask(?:-image)?|--[a-zA-Z0-9_-]+)\\s*:\\s*(?:[^u]*?)url\\(\\s*['\"]?([^)'\"]+)['\"]?\\s*\\)")
                                    .matcher(cssContent);
                            while (m.find()) {
                                String className = m.group(1);
                                String bgUrl = m.group(2);
                                if (bgUrl.startsWith("/")) {
                                    bgUrl = baseUrl + bgUrl;
                                }
                                if (className.contains("image") || className.contains("icon")) {
                                    cssClassToImageUrl.put(className, bgUrl);
                                    logger.debug("Resolved CSS Sprite mapping: {} -> {}", className, bgUrl);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to parse stylesheet for background images: {}", href);
                }
            }
        }

        for (org.jsoup.nodes.Element spriteNode : doc.select("figure, span, div, i")) {
            if (spriteNode.classNames().stream()
                    .anyMatch(c -> c.toLowerCase().contains("image") || c.toLowerCase().contains("icon"))) {
                // If the node has no text and no child img, it's a pure CSS sprite
                if (spriteNode.children().isEmpty() && spriteNode.text().isEmpty() && !spriteNode.hasAttr("src")) {
                    String imgClass = spriteNode.classNames().stream()
                            .filter(c -> c.toLowerCase().contains("image") || c.toLowerCase().contains("icon"))
                            .findFirst()
                            .orElse("sprite-icon");

                    // Attempt to resolve real URL from parsed CSS stylesheets
                    String resolvedUrl = cssClassToImageUrl.get(imgClass);

                    if (resolvedUrl != null) {
                        spriteNode.attr("data-image-src", resolvedUrl);
                        spriteNode.text("(Icon Sprite: " + resolvedUrl + ")");
                    } else {
                        // If we couldn't resolve it via CSS, just leave the class name so the LLM
                        // knows an image was meant to be here, but don't fake a URL.
                        // ACTUALLY: Let's remove the ambiguous class name to prevent AI hallucinations.
                        spriteNode.removeClass(imgClass);
                        spriteNode.attr("data-image-src", "unresolved-css-sprite-" + imgClass);
                    }
                } else {
                    // It has children (like <div class="image-wrap"> containing an <a>).
                    // Remove the deceptive "image" class name to prevent Bedrock LLM from
                    // hallucinating a URL for the parent, BUT ONLY if it's a generic div/span.
                    // Do not strip classes from semantic tags like <figure> or <picture> that legitimately wrap <img> tags!
                    if (spriteNode.tagName().equals("div") || spriteNode.tagName().equals("span")) {
                        java.util.List<String> deceptiveClasses = spriteNode.classNames().stream()
                                .filter(c -> c.toLowerCase().contains("image") || c.toLowerCase().contains("icon"))
                                .collect(java.util.stream.Collectors.toList());
                        deceptiveClasses.forEach(spriteNode::removeClass);
                    }
                }
            }
        }

        // ---- Semantic Section Detection -----------------------------------------------
        // Instead of a flat index per Bedrock result, we detect HTML sections that carry
        // data-analytics-activitymap-region-id and insert the slugified region value as a
        // middle path segment: html-content-section[N]/<region-slug>/fieldKey
        //
        // Pages (or page regions) without the attribute fall back to the current flat path:
        // html-content-section[N]/fieldKey
        // -------------------------------------------------------------------------------

        // Each Bedrock chunk knows its section index and optional region slug.
        record SectionChunk(String html, int sectionIndex, String regionSlug) {}

        java.util.List<SectionChunk> sectionChunks = new java.util.ArrayList<>();
        int[] sectionCounter = {0}; // int[] so we can mutate inside lambdas

        // Helper: slugify a region id like "in the box" → "in-the-box"
        java.util.function.Function<String, String> slugify = id ->
                id.toLowerCase(java.util.Locale.ROOT)
                  .replaceAll("[^a-z0-9]+", "-")
                  .replaceAll("^-+|-+$", "");

        // Helper: add chunks for a DOM element under a given section index + regionSlug
        java.util.function.BiConsumer<org.jsoup.nodes.Element, String> addElementChunks =
                (el, regionSlug) -> {
                    int idx = sectionCounter[0]++;
                    java.util.List<String> subChunks = new java.util.ArrayList<>();
                    StringBuilder subBuf = new StringBuilder();
                    for (org.jsoup.nodes.Element child : el.children()) {
                        chunkElement(child, subBuf, subChunks, 4000);
                    }
                    if (subBuf.length() > 0) subChunks.add(subBuf.toString());
                    if (subChunks.isEmpty()) subChunks.add(el.outerHtml());
                    for (String sub : subChunks) {
                        sectionChunks.add(new SectionChunk(sub, idx, regionSlug));
                    }
                };
        // Helper: extract a human-readable region label from an element by trying multiple
        // Apple analytics attributes in priority order.
        //   1. data-analytics-activitymap-region-id    "in the box"
        //   2. data-analytics-section-engagement       "name:buystrip hero" > "buystrip hero"
        //   3. data-analytics-region                    raw value
        java.util.function.Function<org.jsoup.nodes.Element, String> extractRegionRaw = el -> {
            if (el.hasAttr("data-analytics-activitymap-region-id")) {
                return el.attr("data-analytics-activitymap-region-id").trim();
            }
            if (el.hasAttr("data-analytics-gallery-id")) {
                return el.attr("data-analytics-gallery-id").trim();
            }
            if (el.hasAttr("data-analytics-section-engagement")) {
                String val = el.attr("data-analytics-section-engagement").trim();
                // value format: "name:buystrip hero" or just "buystrip hero"
                if (val.toLowerCase(java.util.Locale.ROOT).startsWith("name:")) {
                    val = val.substring(5).trim();
                }
                return val;
            }
            if (el.hasAttr("data-analytics-region")) {
                return el.attr("data-analytics-region").trim();
            }
            return null;
        };

        // CSS selector matching any of the supported region attributes
        String regionAttrSelector = "[data-analytics-activitymap-region-id]," +
                "[data-analytics-gallery-id]," +
                "[data-analytics-section-engagement]," +
                "[data-analytics-region]";

        for (org.jsoup.nodes.Element child : rootElement.children()) {
            // Case 1: this child is itself a semantic section
            String directRegion = extractRegionRaw.apply(child);
            if (directRegion != null) {
                String slug = slugify.apply(directRegion);
                addElementChunks.accept(child, slug.isEmpty() ? null : slug);
            } else {
                // Case 2: look deeper for semantic sections (any supported attribute)
                org.jsoup.select.Elements innerTagged = child.select(regionAttrSelector);
                if (!innerTagged.isEmpty()) {
                    // Only process the root-most tagged descendants (skip nested ones)
                    java.util.Set<org.jsoup.nodes.Element> roots = new java.util.LinkedHashSet<>(innerTagged);
                    for (org.jsoup.nodes.Element candidate : innerTagged) {
                        for (org.jsoup.nodes.Element ancestor : candidate.parents()) {
                            if (roots.contains(ancestor)) { roots.remove(candidate); break; }
                        }
                    }
                    for (org.jsoup.nodes.Element section : roots) {
                        String raw = extractRegionRaw.apply(section);
                        String slug = (raw != null) ? slugify.apply(raw) : "";
                        addElementChunks.accept(section, slug.isEmpty() ? null : slug);
                    }
                } else {
                    // Case 3: no region ID anywhere — current flat behaviour
                    addElementChunks.accept(child, null);
                }
            }
        }

        logger.info("DOM structural parsing complete. Split webpage into {} Bedrock chunks across {} semantic sections.",
                sectionChunks.size(), sectionCounter[0]);

        ObjectMapper relaxedMapper = new ObjectMapper();
        relaxedMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
        relaxedMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        relaxedMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        relaxedMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.IGNORE_UNDEFINED, true);

        // Process chunks in parallel using streams
        java.util.List<JsonNode> chunkResults = sectionChunks.parallelStream()
                .map(sc -> invokeBedrockForChunk(sc.html(), relaxedMapper))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        ArrayNode bedrockArray = relaxedMapper.createArrayNode();
        for (JsonNode chunkNode : chunkResults) {
            if (chunkNode.isArray()) {
                for (JsonNode item : chunkNode) {
                    bedrockArray.add(item);
                }
            } else if (chunkNode.isObject()) {
                bedrockArray.add(chunkNode);
            }
        }

        if (bedrockArray.isEmpty()) {
            logger.error("Relaxed Jackson completely failed to parse Bedrock layout across all chunks!");
            throw new RuntimeException("Bedrock payload structurally anomalous beyond deterministic bracket recovery.");
        }

        ObjectNode finalPayload = objectMapper
                .createObjectNode();
        finalPayload.put("pageId", payload.getPageId());
        finalPayload.put("locale", payload.getLocale());

        ArrayNode contentArray = finalPayload.putArray("content");

        // Securely convert the URL slug into a truly unique path prefix
        String pathPrefix = "";
        try {
            if (payload.getSourceUri() != null && !payload.getSourceUri().isEmpty()) {
                pathPrefix = URI.create(payload.getSourceUri()).getPath();
            }
        } catch (Exception e) {
            // Ignore non-http URI parsing errors (like uploaded files)
        }

        if (pathPrefix == null || pathPrefix.isEmpty() || pathPrefix.equals("/")) {
            pathPrefix = "/" + payload.getPageId();
        }

        // Remove trailing slash to prevent double slashes like //html-content-section
        if (pathPrefix.endsWith("/")) {
            pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
        }

        // Prepend locale to ensure cross-region deduplication and easier identification
        if (payload.getLocale() != null && !payload.getLocale().isEmpty()) {
            String localeSegment = "/" + payload.getLocale();
            if (!pathPrefix.startsWith(localeSegment)) {
                pathPrefix = localeSegment + pathPrefix;
            }
        }

        if (bedrockArray.isArray()) {
            // chunkResults and sectionChunks have matching indices (both built in parallel
            // from the same source list — parallelStream preserves encounter order for
            // collect()). We zip them to give each Bedrock item its semantic context.
            for (int chunkIdx = 0; chunkIdx < chunkResults.size(); chunkIdx++) {
                JsonNode chunkNode = chunkResults.get(chunkIdx);
                if (chunkNode == null) continue;

                // Determine the semantic region context for this chunk
                SectionChunk sc = chunkIdx < sectionChunks.size() ? sectionChunks.get(chunkIdx) : null;
                String regionPath = (sc != null && sc.regionSlug() != null) ? "/" + sc.regionSlug() : "";
                int sectionIdx   = (sc != null) ? sc.sectionIndex() : chunkIdx;

                // Capture final reference for use inside the lambda
                final String finalPathPrefix = pathPrefix;
                java.util.function.Consumer<JsonNode> addItem = item -> {
                    ObjectNode sectionNode = item.deepCopy();
                    sectionNode.put("_model", "html-content-section");

                    // Dynamically extract the semantic key generated by Bedrock (e.g.,
                    // 'videoRecordingHeadline002')
                    String semanticKey = "";
                    java.util.Iterator<String> fieldNames = sectionNode.fieldNames();
                    while (fieldNames.hasNext()) {
                        String fieldName = fieldNames.next();
                        if (!fieldName.startsWith("_")) {
                            semanticKey = "/" + fieldName;
                            break;
                        }
                    }

                    // Path: /en_US/iphone-17-pro/specs/html-content-section[N][/region-slug]/fieldKey
                    sectionNode.put("_path",
                            finalPathPrefix + "/html-content-section[" + sectionIdx + "]" + regionPath + semanticKey);
                    contentArray.add(sectionNode);
                };

                if (chunkNode.isArray()) {
                    for (JsonNode item : chunkNode) addItem.accept(item);
                } else if (chunkNode.isObject()) {
                    addItem.accept(chunkNode);
                }
            }
        }

        // 7. Inject Native DOM Analytics Tags to seamlessly mimic AEM JSON Vector
        // pipelines!
        org.jsoup.select.Elements metaTags = doc
                .select("meta[property^=analytics], meta[name^=analytics], meta[property^=og:]");
        if (!metaTags.isEmpty()) {
            ObjectNode analyticsNode = objectMapper.createObjectNode();
            analyticsNode.put("_model", "pageAnalyticsAttributes");
            analyticsNode.put("_path", pathPrefix + "/analytics");
            ObjectNode analyticsProps = analyticsNode.putObject("analytics");

            for (org.jsoup.nodes.Element meta : metaTags) {
                String propName = meta.hasAttr("property") ? meta.attr("property") : meta.attr("name");
                String propValue = meta.attr("content");
                if (propName != null && !propName.isEmpty() && propValue != null && !propValue.isEmpty()) {
                    analyticsProps.put(propName.replace("analytics-ac-", "").replace("analytics-", ""), propValue);
                }
            }
            // Always inject structurally at the absolute beginning of the Canonical array!
            contentArray.insert(0, analyticsNode);
        }

        // Build an additive grouped representation for HTML consumers.
        // This is persisted in raw payload for observability, but downstream extraction
        // explicitly ignores this branch to avoid duplicate cleansing/enrichment.
        ArrayNode groupedContent = buildGroupedContentForHtml(contentArray, payload.getPageId());
        finalPayload.set("contentGrouped", groupedContent);

        logger.info("Successfully generated Canonical JSON array. Attempting DataIngestionService Hand-Off...");

        // Hand-off directly to the DataIngestionService to process natively!
        store.setStatus("SUCCESS");
        unstructuredDataStoreRepository.save(store);

        com.apple.springboot.model.UploadRequestMetadata uploadMetadata = com.apple.springboot.model.UploadRequestMetadata
                .of(null, null, null, null, null, payload.getLocale());
        // Persist grouped-first payload in raw_data_store; cleansing projects canonical
        // flat leaves from this grouped view.
        ObjectNode groupedIngestionPayload = objectMapper.createObjectNode();
        groupedIngestionPayload.put("pageId", payload.getPageId());
        groupedIngestionPayload.put("locale", payload.getLocale());
        groupedIngestionPayload.set("contentGrouped", groupedContent.deepCopy());
        com.apple.springboot.model.CleansedDataStore cleansedStore = dataIngestionService.ingestAndCleanseJsonPayload(
                groupedIngestionPayload.toString(), "html-extraction:" + payload.getSourceUri(), uploadMetadata);

        finalPayload.put("cleansedId", cleansedStore.getId().toString());
        return finalPayload;
    }

    private ArrayNode buildGroupedContentForHtml(ArrayNode contentArray, String pageId) {
        ArrayNode grouped = objectMapper.createArrayNode();
        if (contentArray == null || contentArray.isEmpty()) {
            return grouped;
        }

        java.util.Map<String, ObjectNode> buckets = new java.util.LinkedHashMap<>();
        for (JsonNode node : contentArray) {
            if (!(node instanceof ObjectNode item) || !item.has("_path") || !item.get("_path").isTextual()) {
                continue;
            }
            String fullPath = item.get("_path").asText();
            SectionPathInfo info = parseSectionPathInfo(fullPath);
            String groupSlug;
            String bucketKey;
            String sectionBasePath;
            String regionSlug;
            if (info != null && info.baseSectionPath() != null && !info.baseSectionPath().isBlank()) {
                sectionBasePath = info.baseSectionPath();
                regionSlug = info.regionSlug();
            } else {
                int lastSlash = fullPath.lastIndexOf('/');
                if (lastSlash <= 0) {
                    continue;
                }
                sectionBasePath = fullPath.substring(0, lastSlash);
                regionSlug = slugifySegment(fullPath.substring(lastSlash + 1));
            }
            if (regionSlug != null && !regionSlug.isBlank()) {
                groupSlug = "section-group-" + regionSlug;
                bucketKey = "region:" + sectionBasePath + ":" + regionSlug;
            } else {
                groupSlug = "section-group-ungrouped";
                bucketKey = "region:" + sectionBasePath + ":ungrouped";
            }

            ObjectNode bucket = buckets.computeIfAbsent(bucketKey, k -> {
                ObjectNode b = objectMapper.createObjectNode();
                b.put("section_path", sectionBasePath + "/" + groupSlug);
                b.put("group_name", groupSlug);
                // Human-readable slug for the grouped section (e.g. "finish", "size-and-weight")
                b.put("slugName", regionSlug != null && !regionSlug.isBlank() ? regionSlug : "ungrouped");
                b.set("items", objectMapper.createArrayNode());
                return b;
            });
            ((ArrayNode) bucket.get("items")).add(item.deepCopy());
        }

        for (ObjectNode bucket : buckets.values()) {
            grouped.add(bucket);
        }
        return grouped;
    }

    private record SectionPathInfo(String baseSectionPath, String regionSlug) {}

    private SectionPathInfo parseSectionPathInfo(String fullPath) {
        if (fullPath == null || fullPath.isBlank()) {
            return null;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern
                .compile("^(.*?/html-content-section\\[\\d+\\])(?:/([^/]+))?(?:/.*)?$");
        java.util.regex.Matcher m = p.matcher(fullPath);
        if (!m.matches()) {
            return null;
        }
        String base = m.group(1);
        String region = m.group(2);
        if (region != null) {
            region = slugifySegment(region);
        }
        return new SectionPathInfo(base, region);
    }

    private String slugifySegment(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private JsonNode invokeBedrockForChunk(String chunkHtml, ObjectMapper relaxedMapper) {
        String prompt = "You are LosslessDOMExtractor, a strict DOM-to-JSON transformation engine for the Apple ContentLake.\n"
                +
                "Your job is NON-LOSSY conversion of HTML into JSON objects. You must preserve every meaningful content-bearing node in the given HTML chunk.\n\n"
                +
                "INPUT HTML:\n" + chunkHtml + "\n\n" +
                "OUTPUT FORMAT (STRICT):\n" +
                "Return ONLY a raw JSON array. Each array element must be exactly one object with one unique top-level key.\n"
                +
                "Example format:\n[\n  {\n" +
                "    \"highlyDescriptiveCamelCaseKeyHeadline001\": {\n" +
                "      \"_type\": \"headline|copy|gridCell|feature|footnote|image|icon|cta|unknown\",\n" +
                "      \"_tag\": \"p\",\n" +
                "      \"classes\": [\"typography-headline\"],\n" +
                "      \"copy\": \"exact HTML content including inline tags\",\n" +
                "      \"attributes\": { \"href\": \"...\", \"aria-label\": \"...\" },\n" +
                "      \"analytics\": { \"data-analytics-title\": \"...\" },\n" +
                "      \"uri\": \"\",\n" +
                "      \"_path\": \"\"\n" +
                "    }\n  }\n]\n\n" +
                "NON-LOSSY EXTRACTION RULES (MANDATORY):\n" +
                "1) UNIQUE KEYS MANDATE: You MUST append a unique sequential number to EVERY top-level key (e.g. `featureCell042`, `airPods4Headline015`). Never reuse a key. If you use the same key twice, the JSON parser will silently delete the duplicate grid cell!\n"
                +
                "2) HIERARCHICAL DOM NESTING: Preserve structural grouping via nested objects. If a container (`div`, `section`, `article`, `figure`, `table`) acts as a wrapper for child elements (like a headline and paragraphs, or a gallery and its images), create a single parent JSON object and place its children inside a nested property (e.g. `items` array or `children`). ONLY use flat top-level objects if the HTML elements are truly independent siblings.\n" +
                "3) Extract EVERY node that is content-bearing: h1-h6, p, li, td, th, caption, figcaption, a, button, span (when text-bearing), any node with class containing: \"typography-\", \"row\", \"feature-wrapper\", \"subheading\", \"copy\", \"headline\", \naccessibility text nodes: \".visuallyhidden\", \".sr-only\", \"[aria-label]\", images/media/icons: img, picture, source, figure, svg, use, video[poster].\n"
                +
                "4) EXHAUSTIVE EXTRACTION (NO TRUNCATION): You are strictly forbidden from omitting items for brevity or summarizing long repetitive grids/lists. You MUST extract every single item exactly as it appears in the DOM, no matter how long the list is.\n"
                +
                "5) COMPACT ARRAYS FOR FLAT LISTS: If a list `<ul>` or grid row contains ONLY simple text items (no images, no links, no icons), DO NOT create verbose nested objects for every single `<li>`. Instead, output a single JSON array of plain strings under the key `features` or `items`. Example: `\"features\": [\"18MP Center Stage camera\", \"Autofocus with Focus\", \"Retina Flash\"]`. This mathematically prevents JSON payload exhaustion on massive specs lists.\n"
                +
                "6) MANDATORY TEXT KEY: All readable text MUST be placed inside the `copy` property. NEVER use keys like `headline`, `title`, or `description` for text. If it is text, it goes in `copy`. Preserve inline semantic markup inside `copy` (<sup>, <br>, <strong>, etc).\n"
                +
                "7) Figure/picture handling: Every single image must create a JSON object. If you see a `figure`, `picture`, `img`, `source`, or `video` tag, YOU MUST EXTRACT IT. Never skip it. Map its `data-image-src` OR `src` OR `srcset` value into the `uri` property.\n"
                +
                "8) CRITICAL COMPATIBILITY RULE: If the node is an image, its top-level key MUST contain the exact word 'Image' (e.g. 'AirPods4Image'). If it is a CTA/link, its top-level key MUST contain exactly 'CallToAction' (e.g. 'BuyCallToAction') and include a 'url' property.\n"
                +
                "9) NESTED FEATURE HANDLING: If a `.feature-wrapper` or similar node contains BOTH an image/icon AND a text node, you MUST extract BOTH. Do not drop the text because an image exists, and do not drop the image because text exists. They can be separate objects or one combined object, but data loss is forbidden.\n"
                +
                "10) ANALYTICS CAPTURE: If ANY element (including parent divs, wrappers, or sections) possesses `data-analytics-*` attributes, you MUST capture them under a nested 'analytics' JSON dictionary within the extracted object. Do not drop analytics data from intermediate wrappers.\n"
                +
                "11) DOM TRAVERSAL RULE: Traverse the DOM in order from top to bottom. Do not reorder nodes.\n\n" +
                "CRITICAL REQUIREMENT: The JSON must represent a near-lossless structural representation of the HTML. Missing `<picture>` or `<img>` elements is considered a catastrophic failure.\n"
                +
                "RESPOND WITH RAW JSON ARRAY ONLY. NO MARKDOWN.";

        logger.info("Invoking Bedrock for structured semantic transformation chunk... (Length: {})",
                chunkHtml.length());

        try {
            String bedrockResponse = bedrockService.invokeChatForText(prompt, 8192);
            String cleaned = sanitizeModelOutput(bedrockResponse);
            JsonNode bedrockArray = tryParseJsonWithRecovery(cleaned, relaxedMapper);

            if (bedrockArray == null) {
                String repaired = attemptJsonRepair(cleaned);
                if (repaired != null && !repaired.isBlank()) {
                    bedrockArray = tryParseJsonWithRecovery(repaired, relaxedMapper);
                }
            }

            if (bedrockArray == null) {
                logger.error("Relaxed Jackson completely failed to parse Bedrock chunk layout: {}", clipForLog(bedrockResponse, 2000));
            }
            return bedrockArray;
        } catch (Exception ex) {
            logger.error("AwsBedrock SDK Exception caught while processing chunk: {}", ex.getMessage());
            return null;
        }
    }

    private String sanitizeModelOutput(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("(?s)^```json\\s*", "")
                .replaceAll("(?s)^```\\s*", "")
                .replaceAll("(?s)\\s*```\\s*$", "")
                .trim();
        int firstArray = cleaned.indexOf('[');
        int lastArray = cleaned.lastIndexOf(']');
        if (firstArray >= 0 && lastArray > firstArray) {
            return cleaned.substring(firstArray, lastArray + 1);
        }
        int firstObject = cleaned.indexOf('{');
        int lastObject = cleaned.lastIndexOf('}');
        if (firstObject >= 0 && lastObject > firstObject) {
            return cleaned.substring(firstObject, lastObject + 1);
        }
        return cleaned;
    }

    private JsonNode tryParseJsonWithRecovery(String candidate, ObjectMapper relaxedMapper) {
        if (candidate == null || candidate.isBlank()) return null;
        String healingResponse = candidate;
        int lastClosingBrace = healingResponse.lastIndexOf(']');
        if (lastClosingBrace == -1) {
            lastClosingBrace = healingResponse.lastIndexOf('}');
        }
        if (lastClosingBrace > 5) {
            healingResponse = healingResponse.substring(0, lastClosingBrace + 1);
        }

        String[] attempts = new String[] {
                healingResponse,
                healingResponse + "]",
                healingResponse + "}]",
                healingResponse + "}}]",
                healingResponse + "\"}}]"
        };

        for (String attempt : attempts) {
            try {
                return relaxedMapper.readTree(attempt);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String attemptJsonRepair(String brokenJson) {
        try {
            String prompt = "You are a strict JSON repair engine.\n" +
                    "Fix malformed JSON and return ONLY valid JSON array/object.\n" +
                    "Do not add explanation or markdown.\n" +
                    "If arrays/objects are truncated, close them minimally.\n" +
                    "Preserve original keys/values as much as possible.\n\n" +
                    "BROKEN JSON:\n" + brokenJson;
            String repaired = bedrockService.invokeChatForText(prompt, 8192);
            return sanitizeModelOutput(repaired);
        } catch (Exception e) {
            logger.warn("JSON repair pass failed: {}", e.getMessage());
            return null;
        }
    }

    private String clipForLog(String raw, int maxLen) {
        if (raw == null) return "";
        if (raw.length() <= maxLen) return raw;
        return raw.substring(0, maxLen) + "...";
    }

    private void chunkElement(org.jsoup.nodes.Element element, StringBuilder currentChunk,
            java.util.List<String> htmlChunks, int maxSize) {
        String outerHtml = element.outerHtml();
        if (outerHtml.length() <= maxSize) {
            if (currentChunk.length() + outerHtml.length() > maxSize && currentChunk.length() > 0) {
                htmlChunks.add(currentChunk.toString());
                currentChunk.setLength(0);
            }
            currentChunk.append(outerHtml).append("\n");
        } else {
            if (element.children().isEmpty()) {
                if (currentChunk.length() > 0) {
                    htmlChunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
                int idx = 0;
                while (idx < outerHtml.length()) {
                    htmlChunks.add(outerHtml.substring(idx, Math.min(idx + maxSize, outerHtml.length())));
                    idx += maxSize;
                }
            } else {
                // To preserve parent semantic structure (like `<div class="compare-grid">`)
                // when splitting
                // massive lists of children, we artificially inject the parent tag sequence
                // around the broken chunks.
                String startTag = "<" + element.tagName();
                for (org.jsoup.nodes.Attribute attr : element.attributes()) {
                    startTag += " " + attr.getKey() + "=\"" + attr.getValue() + "\"";
                }
                startTag += ">";
                String endTag = "</" + element.tagName() + ">";

                StringBuilder sliceChunk = new StringBuilder();
                for (org.jsoup.nodes.Element child : element.children()) {
                    String childHtml = child.outerHtml();
                    if (sliceChunk.length() + childHtml.length() > maxSize && sliceChunk.length() > 0) {
                        htmlChunks.add(startTag + "\n" + sliceChunk.toString() + "\n" + endTag);
                        sliceChunk.setLength(0);
                    }
                    if (childHtml.length() > maxSize) {
                        // Recursively handle an ultra-massive child
                        chunkElement(child, currentChunk, htmlChunks, maxSize);
                    } else {
                        sliceChunk.append(childHtml).append("\n");
                    }
                }
                if (sliceChunk.length() > 0) {
                    htmlChunks.add(startTag + "\n" + sliceChunk.toString() + "\n" + endTag);
                }
            }
        }
    }
}
