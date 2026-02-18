package com.apple.springboot.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Request payload for Asset Finder filtering.
 */
@Getter
@Setter
public class AssetFinderFilterRequest {
    private String tenant;
    private String environment;
    private String project;
    private String site;
    private String geo;
    private String locale;
    private Integer page = 0;
    private Integer size = 200;
}
