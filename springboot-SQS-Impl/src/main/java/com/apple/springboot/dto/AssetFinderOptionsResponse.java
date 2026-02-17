package com.apple.springboot.dto;

import lombok.Data;
import java.util.List;

@Data
public class AssetFinderOptionsResponse {
    private List<String> tenants;
    private List<String> environments;
    private List<String> projects;
    private List<String> sites;
    private List<String> geos;
    private List<String> locales;
}
