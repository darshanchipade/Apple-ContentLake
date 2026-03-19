package com.apple.springboot.dto;

import lombok.Data;

@Data
public class UnstructuredUrlPayload {
    private String url;
    // Optional overrides, if provided by UI
    private String pageId;
    private String locale;
}
