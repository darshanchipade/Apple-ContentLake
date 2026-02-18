package com.apple.springboot.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class AssetFinderTileDto {
    private UUID id;
    private String assetKey;
    private String previewUri;
    private String assetNodePath;
    private String altText;
}
