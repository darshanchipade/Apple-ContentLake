package com.apple.springboot.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Paginated Asset Finder search response.
 */
@Getter
@Setter
public class AssetFinderSearchResponse {
    private long count;
    private int page;
    private int size;
    private int totalPages;
    private List<AssetFinderTileDto> items;
}