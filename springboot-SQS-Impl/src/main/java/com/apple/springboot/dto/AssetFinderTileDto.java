package com.apple.springboot.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Minimal tile payload returned to the Asset Finder grid.
 */
@Getter
@Setter
public class AssetFinderTileDto {
    private UUID id;
    private String assetKey;
    private String assetModel;
    private String sectionPath;
    private String sectionUri;
    private String interactivePath;
    private String previewUri;
    private String locale;
    private String site;
    private String geo;
    private String altText;
}
