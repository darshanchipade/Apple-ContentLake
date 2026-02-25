package com.apple.springboot.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@ToString
public class ParsedQuery {
    private String query;
    private List<String> tags;
    private List<String> keywords;
    private Map<String, Object> contextMap;
    private String originalFieldName;
    private String sectionKeyFilter;
}
