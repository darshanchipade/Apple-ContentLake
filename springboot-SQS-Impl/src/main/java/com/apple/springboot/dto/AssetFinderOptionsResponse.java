package com.apple.springboot.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Filter options returned by the Asset Finder options endpoint.
 */
@Getter
@Setter
public class AssetFinderOptionsResponse {
    private List<String> tenants;
    private List<String> environments;
    private List<String> projects;
    private List<String> sites;
    private List<String> geos;
    private Map<String, List<String>> geoToLocales;
}