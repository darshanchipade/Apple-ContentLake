package com.apple.springboot.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "unstructured_data_store")
public class UnstructuredDataStore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "source_uri", nullable = false, columnDefinition = "TEXT")
    private String sourceUri;

    @Column(name = "raw_html_content", columnDefinition = "TEXT")
    private String rawHtmlContent;

    @Column(name = "page_id", nullable = false, columnDefinition = "TEXT")
    private String pageId;

    @Column(name = "locale", nullable = false, columnDefinition = "TEXT")
    private String locale;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    private String status;

    @Getter
    @Column(name = "html_md5_hash", columnDefinition = "TEXT")
    private String htmlMd5Hash;

    /**
     * Default constructor for JPA.
     */
    public UnstructuredDataStore() {
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public void setSourceUri(String sourceUri) {
        this.sourceUri = sourceUri;
    }

    public void setRawHtmlContent(String rawHtmlContent) {
        this.rawHtmlContent = rawHtmlContent;
    }

    public void setPageId(String pageId) {
        this.pageId = pageId;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public void setReceivedAt(OffsetDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setHtmlMd5Hash(String htmlMd5Hash) {
        this.htmlMd5Hash = htmlMd5Hash;
    }
}
