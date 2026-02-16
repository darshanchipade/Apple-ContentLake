package com.apple.springboot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Stores image/icon metadata extracted from uploaded JSON payloads for Asset Finder.
 */
@Setter
@Getter
@Entity
@Table(
        name = "asset_image_store",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_asset_image_store_source_version_hash",
                        columnNames = {"source_uri", "source_version", "asset_hash"}
                )
        },
        indexes = {
                @Index(name = "idx_asset_image_store_raw_data_id", columnList = "raw_data_id"),
                @Index(name = "idx_asset_image_store_source_uri_version", columnList = "source_uri,source_version"),
                @Index(name = "idx_asset_image_store_filters", columnList = "tenant,environment,project,site,geo,locale"),
                @Index(name = "idx_asset_image_store_section", columnList = "section_path"),
                @Index(name = "idx_asset_image_store_created_at", columnList = "created_at")
        }
)
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

    @Column(name = "tenant", columnDefinition = "TEXT")
    private String tenant;

    @Column(name = "environment", columnDefinition = "TEXT")
    private String environment;

    @Column(name = "project", columnDefinition = "TEXT")
    private String project;

    @Column(name = "site", columnDefinition = "TEXT")
    private String site;

    @Column(name = "geo", columnDefinition = "TEXT")
    private String geo;

    @Column(name = "locale", columnDefinition = "TEXT")
    private String locale;

    @Column(name = "asset_key", nullable = false, columnDefinition = "TEXT")
    private String assetKey;

    @Column(name = "asset_node_path", nullable = false, columnDefinition = "TEXT")
    private String assetNodePath;

    @Column(name = "asset_model", columnDefinition = "TEXT")
    private String assetModel;

    @Column(name = "section_path", columnDefinition = "TEXT")
    private String sectionPath;

    @Column(name = "section_uri", columnDefinition = "TEXT")
    private String sectionUri;

    @Column(name = "interactive_path", columnDefinition = "TEXT")
    private String interactivePath;

    @Column(name = "preview_uri", columnDefinition = "TEXT")
    private String previewUri;

    @Column(name = "alt_text", columnDefinition = "TEXT")
    private String altText;

    @Column(name = "accessibility_text", columnDefinition = "TEXT")
    private String accessibilityText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "viewports_json", columnDefinition = "jsonb")
    private String viewportsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_metadata_json", columnDefinition = "jsonb")
    private String assetMetadataJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_metadata_json", columnDefinition = "jsonb")
    private String requestMetadataJson;

    @Column(name = "asset_hash", nullable = false, columnDefinition = "TEXT")
    private String assetHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Initializes timestamps before insert.
     */
    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    /**
     * Updates timestamps before update.
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
