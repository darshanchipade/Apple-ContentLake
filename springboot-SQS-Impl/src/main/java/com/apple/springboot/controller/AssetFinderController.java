package com.apple.springboot.controller;

import com.apple.springboot.dto.*;
import com.apple.springboot.service.AssetImageStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/asset-finder")
@Tag(name = "Asset Finder", description = "Endpoints for finding and viewing image assets")
public class AssetFinderController {

    private final AssetImageStoreService assetImageStoreService;

    @Autowired
    public AssetFinderController(AssetImageStoreService assetImageStoreService) {
        this.assetImageStoreService = assetImageStoreService;
    }

    @Operation(summary = "Get unique filter options for assets")
    @GetMapping("/options")
    public ResponseEntity<AssetFinderOptionsResponse> getOptions() {
        return ResponseEntity.ok(assetImageStoreService.getFilterOptions());
    }

    @Operation(summary = "Search for image assets with filters")
    @PostMapping("/search")
    public ResponseEntity<AssetFinderSearchResponse> search(@RequestBody AssetFinderFilterRequest request) {
        return ResponseEntity.ok(assetImageStoreService.searchAssets(request));
    }

    @Operation(summary = "Get detailed information for a specific asset")
    @GetMapping("/assets/{id}")
    public ResponseEntity<AssetFinderAssetDetailDto> getAssetDetail(@PathVariable UUID id) {
        return assetImageStoreService.getAssetDetail(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
