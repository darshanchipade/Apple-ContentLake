package com.apple.springboot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only audit trail for changes applied to latest-only asset occurrences.
 */
@Setter
@Getter
@Entity
@Table(
        name = "asset_metadata_occurrence_audit",
        indexes = {
                @Index(name = "idx_asset_metadata_occ_audit_source_version", columnList = "source_uri,source_version"),
                @Index(name = "idx_asset_metadata_occ_audit_slot", columnList = "asset_slot_key"),
                @Index(name = "idx_asset_metadata_occ_audit_created_at", columnList = "created_at"),
                @Index(name = "idx_asset_metadata_occ_audit_raw_data_id", columnList = "raw_data_id")
        }
)
public class AssetMetadataOccurrenceAudit {

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

    @Column(name = "asset_slot_key", nullable = false, columnDefinition = "TEXT")
    private String assetSlotKey;

    @Column(name = "event_type", nullable = false, columnDefinition = "TEXT")
    private String eventType;

    @Column(name = "old_catalog_id")
    private UUID oldCatalogId;

    @Column(name = "new_catalog_id")
    private UUID newCatalogId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_context_json", columnDefinition = "jsonb")
    private String oldContextJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_context_json", columnDefinition = "jsonb")
    private String newContextJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}