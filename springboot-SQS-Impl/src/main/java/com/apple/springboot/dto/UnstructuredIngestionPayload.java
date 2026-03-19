package com.apple.springboot.dto;

import lombok.Data;

@Data
public class UnstructuredIngestionPayload {
    private String pageId;
    private String locale;
    private String sourceUri;
    private String htmlContent;
}
