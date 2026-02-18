package com.apple.springboot.repository;

import com.apple.springboot.model.AssetRegionLocaleRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetRegionLocaleRefRepository extends JpaRepository<AssetRegionLocaleRef, UUID> {

    /**
     * Loads active mappings ordered for deterministic option payloads.
     */
    List<AssetRegionLocaleRef> findByActiveTrueOrderByGeoCodeAscDisplayNameAscApplePathAsc();

    /**
     * Finds one row by its storefront path/display tuple.
     */
    Optional<AssetRegionLocaleRef> findByApplePathAndDisplayName(String applePath, String displayName);
}
