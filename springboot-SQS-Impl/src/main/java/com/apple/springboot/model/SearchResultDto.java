package com.apple.springboot.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Search result containing matched content and metadata")
public class SearchResultDto {
    @Schema(description = "Cleansed text content of the matched result", example = "Learn how to set up your iPad")
    private String cleansedText;

    @Schema(description = "Source field name where the content was found", example = "copy")
    private String sourceFieldName;

    @Schema(description = "Path to the section containing the result", example = "/en_US/ipad")
    private String sectionPath;

    @Schema(description = "Associated image URLs from the same section")
    private List<String> images;

    /**
     * Creates a DTO for a single search result (no images).
     */
    public SearchResultDto(String cleansedText, String sourceFieldName, String sectionPath) {
        this.cleansedText = cleansedText;
        this.sourceFieldName = sourceFieldName;
        this.sectionPath = sectionPath;
        this.images = List.of();
    }

    /**
     * Creates a DTO for a single semantic search result with associated images.
     */
    public SearchResultDto(String cleansedText, String sourceFieldName, String sectionPath, List<String> images) {
        this.cleansedText = cleansedText;
        this.sourceFieldName = sourceFieldName;
        this.sectionPath = sectionPath;
        this.images = images != null ? images : List.of();
    }
    /**
     * Returns the cleansed text content.
     */
    public String getCleansedText() { return cleansedText; }
    /**
     * Updates the cleansed text content.
     */
    public void setCleansedText(String cleansedText) { this.cleansedText = cleansedText; }
    /**
     * Returns the source field name.
     */
    public String getSourceFieldName() { return sourceFieldName; }

    /**
     * Updates the source field name.
     */
    public void setSourceFieldName(String sourceFieldName) { this.sourceFieldName = sourceFieldName; }
    /**
     * Returns the section path.
     */
    public String getSectionPath() { return sectionPath; }
    /**
     * Updates the section path.
     */
    public void setSectionPath(String sectionPath) { this.sectionPath = sectionPath; }

    /**
     * Returns associated image URLs from the same section.
     */
    public List<String> getImages() { return images; }
    /**
     * Updates associated image URLs.
     */
    public void setImages(List<String> images) { this.images = images; }
}