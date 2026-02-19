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
 * Versioned occurrence references to canonical catalog metadata rows.
 */
@Setter
@Getter
@Entity
@Table(
        name = "asset_metadata_occurrence",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_asset_metadata_occurrence_source_version_slot",
                        columnNames = {"source_uri", "source_version", "asset_slot_key"}
                )
        },
        indexes = {
                @Index(name = "idx_asset_metadata_occurrence_catalog_id", columnList = "catalog_id"),
                @Index(name = "idx_asset_metadata_occurrence_raw_data_id", columnList = "raw_data_id"),
                @Index(name = "idx_asset_metadata_occurrence_source_uri_version", columnList = "source_uri,source_version"),
                @Index(name = "idx_asset_metadata_occurrence_filters", columnList = "tenant,environment,project,site,geo,locale"),
                @Index(name = "idx_asset_metadata_occurrence_section", columnList = "section_path"),
                @Index(name = "idx_asset_metadata_occurrence_created_at", columnList = "created_at")
        }
)
public class AssetMetadataOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "catalog_id", nullable = false)
    private UUID catalogId;

    @Column(name = "raw_data_id", nullable = false)
    private UUID rawDataId;

    @Column(name = "source_uri", nullable = false, columnDefinition = "TEXT")
    private String sourceUri;

    @Column(name = "source_version")
    private Integer sourceVersion;

    @Column(name = "asset_slot_key", nullable = false, columnDefinition = "TEXT")
    private String assetSlotKey;

    @Column(name = "asset_node_path", nullable = false, columnDefinition = "TEXT")
    private String assetNodePath;

    @Column(name = "section_path", columnDefinition = "TEXT")
    private String sectionPath;

    @Column(name = "section_uri", columnDefinition = "TEXT")
    private String sectionUri;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_metadata_json", columnDefinition = "jsonb")
    private String requestMetadataJson;

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