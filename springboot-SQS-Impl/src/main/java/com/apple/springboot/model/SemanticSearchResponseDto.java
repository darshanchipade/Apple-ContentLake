package com.apple.springboot.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Wrapper representing a semantic search query and its structured section-pack results")
public class SemanticSearchResponseDto {
    private String query;
    private List<SemanticSectionResultDto> results;

    public SemanticSearchResponseDto() {}

    public SemanticSearchResponseDto(String query, List<SemanticSectionResultDto> results) {
        this.query = query;
        this.results = results;
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public List<SemanticSectionResultDto> getResults() { return results; }
    public void setResults(List<SemanticSectionResultDto> results) { this.results = results; }
}
