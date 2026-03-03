package com.apple.springboot.repository;

import com.apple.springboot.model.IngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {
    
    // Finds all jobs ordered by most recently created
    List<IngestionJob> findAllByOrderByCreatedAtDesc();
    
    // For future persona-based filtering
    List<IngestionJob> findByUsernameOrderByCreatedAtDesc(String username);
}
