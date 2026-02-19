package com.apple.springboot.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional metadata supplied by upload requests for downstream asset indexing.
 */
public record UploadRequestMetadata(
        String tenant,
        String environment,
        String project,
        String site,
        String geo,
        String locale
) {
    /**
     * Creates a normalized metadata object with trimmed values.
     */
    public static UploadRequestMetadata of(String tenant,
                                           String environment,
                                           String project,
                                           String site,
                                           String geo,
                                           String locale) {
        return new UploadRequestMetadata(
                trimToNull(tenant),
                trimToNull(environment),
                trimToNull(project),
                trimToNull(site),
                trimToNull(geo),
                normalizeLocale(trimToNull(locale))
        );
    }

    /**
     * Returns true when no metadata attribute has a value.
     */
    public boolean isEmpty() {
        return tenant == null
                && environment == null
                && project == null
                && site == null
                && geo == null
                && locale == null;
    }

    /**
     * Converts metadata to a JSON-friendly map.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (tenant != null) map.put("tenant", tenant);
        if (environment != null) map.put("environment", environment);
        if (project != null) map.put("project", project);
        if (site != null) map.put("site", site);
        if (geo != null) map.put("geo", geo);
        if (locale != null) map.put("locale", locale);
        return map;
    }

    /**
     * Parses metadata from a generic map payload.
     */
    public static UploadRequestMetadata fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return UploadRequestMetadata.of(null, null, null, null, null, null);
        }
        return UploadRequestMetadata.of(
                valueAsString(map.get("tenant")),
                valueAsString(map.get("environment")),
                valueAsString(map.get("project")),
                valueAsString(map.get("site")),
                valueAsString(map.get("geo")),
                valueAsString(map.get("locale"))
        );
    }

    /**
     * Normalizes locale values into ll_CC format when possible.
     */
    private static String normalizeLocale(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replace('-', '_');
        if (normalized.length() == 5 && normalized.charAt(2) == '_') {
            String language = normalized.substring(0, 2).toLowerCase(java.util.Locale.ROOT);
            String country = normalized.substring(3).toUpperCase(java.util.Locale.ROOT);
            return language + "_" + country;
        }
        return normalized;
    }

    /**
     * Returns a trimmed string or null if blank.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Converts any object into a trimmed string when possible.
     */
    private static String valueAsString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return trimToNull(s);
        }
        return trimToNull(String.valueOf(value));
    }
}
