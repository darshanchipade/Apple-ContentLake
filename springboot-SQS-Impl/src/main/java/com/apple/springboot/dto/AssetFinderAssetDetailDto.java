package com.apple.springboot.dto;

import lombok.Data;
import java.util.Map;
import java.util.UUID;

@Data
public class AssetFinderAssetDetailDto {
    private UUID id;
    private String assetKey;
    private String assetNodePath;
    private String sectionPath;
    private String sectionKey;
    private String previewUri;
    private String altText;
    private String accessibilityText;
    private Map<String, Object> viewports;
    private Map<String, Object> assetMetadata;
}
