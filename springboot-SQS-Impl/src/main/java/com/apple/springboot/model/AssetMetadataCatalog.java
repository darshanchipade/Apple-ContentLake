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
 * Canonical image metadata catalog. Stores one row per unique metadata hash.
 */
@Setter
@Getter
@Entity
@Table(
        name = "asset_metadata_catalog",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_asset_metadata_catalog_metadata_hash",
                        columnNames = {"metadata_hash"}
                )
        },
        indexes = {
                @Index(name = "idx_asset_metadata_catalog_interactive", columnList = "interactive_path"),
                @Index(name = "idx_asset_metadata_catalog_created_at", columnList = "created_at")
        }
)
public class AssetMetadataCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "metadata_hash", nullable = false, columnDefinition = "TEXT")
    private String metadataHash;

    @Column(name = "asset_key", nullable = false, columnDefinition = "TEXT")
    private String assetKey;

    @Column(name = "asset_model", columnDefinition = "TEXT")
    private String assetModel;

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