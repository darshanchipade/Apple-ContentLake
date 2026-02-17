package com.apple.springboot.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "asset_image_store")
public class AssetImageStore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "raw_data_id", nullable = false)
    private UUID rawDataId;

    @Column(name = "source_uri", nullable = false, columnDefinition = "TEXT")
    private String sourceUri;

    @Column(name = "source_version")
    private Integer sourceVersion;

    // Filter columns
    @Column(name = "tenant")
    private String tenant;

    @Column(name = "environment")
    private String environment;

    @Column(name = "project")
    private String project;

    @Column(name = "site")
    private String site;

    @Column(name = "geo")
    private String geo;

    @Column(name = "locale")
    private String locale;

    // Asset identity
    @Column(name = "asset_key")
    private String assetKey; // icon, backgroundImage, productImage, etc.

    @Column(name = "asset_node_path", columnDefinition = "TEXT")
    private String assetNodePath;

    @Column(name = "section_path", columnDefinition = "TEXT")
    private String sectionPath;

    @Column(name = "section_key")
    private String sectionKey;

    // Display
    @Column(name = "preview_uri", columnDefinition = "TEXT")
    private String previewUri;

    @Column(name = "alt_text", columnDefinition = "TEXT")
    private String altText;

    @Column(name = "accessibility_text", columnDefinition = "TEXT")
    private String accessibilityText;

    // JSONB blocks
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "viewports_json", columnDefinition = "jsonb")
    private Map<String, Object> viewportsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_metadata_json", columnDefinition = "jsonb")
    private Map<String, Object> assetMetadataJson;

    @Column(name = "asset_hash")
    private String assetHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public AssetImageStore() {}
}
