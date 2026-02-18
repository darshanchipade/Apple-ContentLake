package com.apple.springboot.repository;

import com.apple.springboot.model.AssetImageStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetImageStoreRepository extends JpaRepository<AssetImageStore, UUID> {

    @Query("SELECT DISTINCT a.tenant FROM AssetImageStore a WHERE a.tenant IS NOT NULL")
    List<String> findDistinctTenants();

    @Query("SELECT DISTINCT a.environment FROM AssetImageStore a WHERE a.environment IS NOT NULL")
    List<String> findDistinctEnvironments();

    @Query("SELECT DISTINCT a.project FROM AssetImageStore a WHERE a.project IS NOT NULL")
    List<String> findDistinctProjects();

    @Query("SELECT DISTINCT a.site FROM AssetImageStore a WHERE a.site IS NOT NULL")
    List<String> findDistinctSites();

    @Query("SELECT DISTINCT a.geo FROM AssetImageStore a WHERE a.geo IS NOT NULL")
    List<String> findDistinctGeos();

    @Query("SELECT DISTINCT a.locale FROM AssetImageStore a WHERE a.locale IS NOT NULL")
    List<String> findDistinctLocales();
}
