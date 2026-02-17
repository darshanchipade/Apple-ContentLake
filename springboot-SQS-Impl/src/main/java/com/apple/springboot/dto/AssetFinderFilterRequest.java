package com.apple.springboot.dto;

import lombok.Data;

@Data
public class AssetFinderFilterRequest {
    private String tenant;
    private String environment;
    private String project;
    private String site;
    private String geo;
    private String locale;
    private int page = 0;
    private int size = 20;
}
