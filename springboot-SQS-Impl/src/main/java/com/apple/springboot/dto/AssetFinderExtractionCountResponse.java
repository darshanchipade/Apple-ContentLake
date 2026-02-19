package com.apple.springboot.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Extraction count summary for a single ingested upload.
 */
@Getter
@Setter
public class AssetFinderExtractionCountResponse {
    private UUID cleansedDataStoreId;
    private UUID rawDataId;
    private String sourceUri;
    private Integer sourceVersion;
    private long assetCount;
    private boolean assetFinderEnabled;
    private boolean tablePresent;
}
