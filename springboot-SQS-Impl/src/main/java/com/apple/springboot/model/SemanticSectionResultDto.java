package com.apple.springboot.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Structured section pack representing a hybrid search result")
public class SemanticSectionResultDto {
    private Integer rank;
    private String sectionPath;
    private Double finalScore;
    private String sourceUrl;
    private List<ContentRoleDto> content;
    private List<MediaItemDto> media;
    private Integer hitCount;

    /** Best-matching chunk text for this section (snippet / content-around-match). */
    private String snippet;
    /** Field name of the fragment that matched the query. */
    private String matchedFieldName;

    // Optional field for LLM debugging/feedback if requested
    private String llmReasoning;

    public SemanticSectionResultDto() {}

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }

    public String getSectionPath() { return sectionPath; }
    public void setSectionPath(String sectionPath) { this.sectionPath = sectionPath; }
    
    private List<String> clusterPaths;
    public List<String> getClusterPaths() { return clusterPaths; }
    public void setClusterPaths(List<String> clusterPaths) { this.clusterPaths = clusterPaths; }
    
    private String sectionUri;
    public String getSectionUri() { return sectionUri; }
    public void setSectionUri(String sectionUri) { this.sectionUri = sectionUri; }

    public Double getFinalScore() { return finalScore; }
    public void setFinalScore(Double finalScore) { this.finalScore = finalScore; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public List<ContentRoleDto> getContent() { return content; }
    public void setContent(List<ContentRoleDto> content) { this.content = content; }

    public List<MediaItemDto> getMedia() { return media; }
    public void setMedia(List<MediaItemDto> media) { this.media = media; }

    public Integer getHitCount() { return hitCount; }
    public void setHitCount(Integer hitCount) { this.hitCount = hitCount; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public String getMatchedFieldName() { return matchedFieldName; }
    public void setMatchedFieldName(String matchedFieldName) { this.matchedFieldName = matchedFieldName; }

    public String getLlmReasoning() { return llmReasoning; }
    public void setLlmReasoning(String llmReasoning) { this.llmReasoning = llmReasoning; }
}
