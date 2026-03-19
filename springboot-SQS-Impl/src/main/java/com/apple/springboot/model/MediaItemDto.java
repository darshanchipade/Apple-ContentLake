package com.apple.springboot.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A media item associated with a section")
public class MediaItemDto {
    private String type;
    private String label;
    private String url;

    public MediaItemDto() {}
    
    public MediaItemDto(String type, String label, String url) {
        this.type = type;
        this.label = label;
        this.url = url;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
