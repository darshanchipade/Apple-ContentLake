package com.apple.springboot.repository;

import com.apple.springboot.model.AssetMetadataCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetMetadataCatalogRepository extends JpaRepository<AssetMetadataCatalog, UUID> {

    /**
     * Finds a canonical catalog row by metadata hash.
     */
    Optional<AssetMetadataCatalog> findByMetadataHash(String metadataHash);
}
