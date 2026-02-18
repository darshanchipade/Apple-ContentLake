package com.apple.springboot.repository;

import com.apple.springboot.model.AssetRegionLocaleRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetRegionLocaleRefRepository extends JpaRepository<AssetRegionLocaleRef, UUID> {

    /**
     * Loads active mappings ordered for deterministic option payloads.
     */
    List<AssetRegionLocaleRef> findByActiveTrueOrderByGeoCodeAscDisplayNameAscApplePathAsc();

    /**
     * Loads active mappings for a given source type.
     */
    List<AssetRegionLocaleRef> findByActiveTrueAndSourceTypeOrderByGeoCodeAscLocaleCodeAscDisplayNameAsc(String sourceType);

    /**
     * Finds one row by source + geo + locale tuple.
     */
    java.util.Optional<AssetRegionLocaleRef> findBySourceTypeAndGeoCodeAndLocaleCode(
            String sourceType,
            String geoCode,
            String localeCode
    );
}
