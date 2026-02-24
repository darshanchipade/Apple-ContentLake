package com.apple.springboot.repository;

import com.apple.springboot.model.AssetMetadataOccurrenceAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AssetMetadataOccurrenceAuditRepository extends JpaRepository<AssetMetadataOccurrenceAudit, UUID> {
}