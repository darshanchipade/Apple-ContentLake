package com.apple.springboot.model;

import lombok.Data;

/**
 * Captures metadata related to the upload request, used for filtering and classification.
 */
@Data
public class UploadRequestMetadata {
    private String tenant;
    private String environment;
    private String project;
    private String site;
    private String geo;
    private String locale;

    public UploadRequestMetadata() {}

    public UploadRequestMetadata(String tenant, String environment, String project, String site, String geo, String locale) {
        this.tenant = tenant;
        this.environment = environment;
        this.project = project;
        this.site = site;
        this.geo = geo;
        this.locale = locale;
    }
}
