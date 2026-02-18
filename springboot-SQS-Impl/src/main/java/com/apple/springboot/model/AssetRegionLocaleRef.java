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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reference mapping between Apple storefront paths and normalized geo/locale values.
 */
@Setter
@Getter
@Entity
@Table(
        name = "asset_region_locale_ref",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_asset_region_locale_ref_path_display",
                        columnNames = {"apple_path", "display_name"}
                ),
                @UniqueConstraint(
                        name = "uk_asset_region_locale_ref_source_locale",
                        columnNames = {"source_type", "locale_code"}
                )
        },
        indexes = {
                @Index(name = "idx_asset_region_locale_ref_geo", columnList = "geo_code"),
                @Index(name = "idx_asset_region_locale_ref_locale", columnList = "locale_code"),
                @Index(name = "idx_asset_region_locale_ref_path", columnList = "apple_path"),
                @Index(name = "idx_asset_region_locale_ref_source_type", columnList = "source_type"),
                @Index(name = "idx_asset_region_locale_ref_active", columnList = "active")
        }
)
public class AssetRegionLocaleRef {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "geo_code", nullable = false, columnDefinition = "TEXT")
    private String geoCode;

    @Column(name = "locale_code", columnDefinition = "TEXT")
    private String localeCode;

    @Column(name = "display_name", nullable = false, columnDefinition = "TEXT")
    private String displayName;

    @Column(name = "apple_path", nullable = false, columnDefinition = "TEXT")
    private String applePath;

    @Column(name = "source_type", nullable = false, columnDefinition = "TEXT")
    private String sourceType = "APPLE";

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "seen_count", nullable = false)
    private Long seenCount = 1L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Initializes timestamps and default state before insert.
     */
    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (active == null) {
            active = true;
        }
        if (sourceType == null) {
            sourceType = "APPLE";
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
        if (seenCount == null || seenCount < 1) {
            seenCount = 1L;
        }
        updatedAt = now;
    }

    /**
     * Updates timestamps before update.
     */
    @PreUpdate
    void onUpdate() {
        if (lastSeenAt == null) {
            lastSeenAt = OffsetDateTime.now();
        }
        if (seenCount == null || seenCount < 1) {
            seenCount = 1L;
        }
        updatedAt = OffsetDateTime.now();
    }
}
