package com.apple.springboot.dto;

import lombok.Data;
import java.util.List;

@Data
public class AssetFinderSearchResponse {
    private List<AssetFinderTileDto> tiles;
    private long totalCount;
    private int totalPages;
    private int currentPage;
}
