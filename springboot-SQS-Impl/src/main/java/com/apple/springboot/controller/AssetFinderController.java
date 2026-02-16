package com.apple.springboot.controller;

import com.apple.springboot.dto.AssetFinderFilterRequest;
import com.apple.springboot.service.AssetImageStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * API endpoints supporting the Asset Finder UI.
 */
@RestController
@RequestMapping("/api/asset-finder")
@Tag(name = "Asset Finder", description = "Asset metadata filter and detail endpoints")
public class AssetFinderController {

    private final AssetImageStoreService assetImageStoreService;

    /**
     * Creates the controller used by Asset Finder UI routes.
     */
    public AssetFinderController(AssetImageStoreService assetImageStoreService) {
        this.assetImageStoreService = assetImageStoreService;
    }

    /**
     * Returns supported filter options and geo/locale mappings.
     */
    @Operation(summary = "Get Asset Finder filter options")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Filter options returned successfully")
    })
    @GetMapping("/options")
    public ResponseEntity<?> getOptions() {
        return ResponseEntity.ok(assetImageStoreService.getOptions());
    }

    /**
     * Returns paginated asset tiles based on filter criteria.
     */
    @Operation(summary = "Search image assets")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Asset search completed")
    })
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody(required = false) AssetFinderFilterRequest request) {
        return ResponseEntity.ok(assetImageStoreService.search(request));
    }

    /**
     * Returns a detailed image metadata payload for a tile.
     */
    @Operation(summary = "Get asset detail")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Asset detail returned"),
            @ApiResponse(responseCode = "404", description = "Asset not found")
    })
    @GetMapping("/assets/{id}")
    public ResponseEntity<?> getAsset(@PathVariable("id") UUID id) {
        return assetImageStoreService.getDetails(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Asset not found for id " + id)));
    }
}
