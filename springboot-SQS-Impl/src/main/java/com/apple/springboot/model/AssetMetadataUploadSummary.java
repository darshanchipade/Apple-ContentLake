package com.apple.springboot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Stores per-upload extracted asset row counts to keep activity history stable.
 */
@Setter
@Getter
@Entity
@Table(name = "asset_metadata_upload_summary")
public class AssetMetadataUploadSummary {

    @Id
    @Column(name = "raw_data_id", nullable = false, updatable = false)
    private UUID rawDataId;

    @Column(name = "source_uri", nullable = false, columnDefinition = "TEXT")
    private String sourceUri;

    @Column(name = "source_version")
    private Integer sourceVersion;

    @Column(name = "asset_count", nullable = false)
    private Long assetCount = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (assetCount == null) {
            assetCount = 0L;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (assetCount == null) {
            assetCount = 0L;
        }
        updatedAt = OffsetDateTime.now();
    }
}