package com.apple.springboot.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

/**
 * Full asset details payload displayed in the tile information modal.
 */
@Getter
@Setter
public class AssetFinderAssetDetailDto {
    private UUID id;
    private String tenant;
    private String environment;
    private String project;
    private String site;
    private String geo;
    private String locale;
    private String assetKey;
    private String assetModel;
    private String sectionPath;
    private String sectionUri;
    private String assetNodePath;
    private String interactivePath;
    private String previewUri;
    private String altText;
    private String accessibilityText;
    private Map<String, Object> viewports;
    private Map<String, Object> metadata;
}
