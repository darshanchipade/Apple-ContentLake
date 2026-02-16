package com.apple.springboot.repository;

import com.apple.springboot.model.AssetImageStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetImageStoreRepository extends JpaRepository<AssetImageStore, UUID> {

    /**
     * Deletes extracted asset rows for a raw_data_store record.
     */
    void deleteByRawDataId(UUID rawDataId);

    /**
     * Performs Asset Finder filtering with optional exact-match filters.
     */
    @Query(
            value = """
                    select a from AssetImageStore a
                    where (:tenant is null or lower(a.tenant) = lower(:tenant))
                      and (:environment is null or lower(a.environment) = lower(:environment))
                      and (:project is null or lower(a.project) = lower(:project))
                      and (:site is null or lower(a.site) = lower(:site))
                      and (:geo is null or lower(a.geo) = lower(:geo))
                      and (:locale is null or lower(a.locale) = lower(:locale))
                    """,
            countQuery = """
                    select count(a) from AssetImageStore a
                    where (:tenant is null or lower(a.tenant) = lower(:tenant))
                      and (:environment is null or lower(a.environment) = lower(:environment))
                      and (:project is null or lower(a.project) = lower(:project))
                      and (:site is null or lower(a.site) = lower(:site))
                      and (:geo is null or lower(a.geo) = lower(:geo))
                      and (:locale is null or lower(a.locale) = lower(:locale))
                    """
    )
    Page<AssetImageStore> search(
            @Param("tenant") String tenant,
            @Param("environment") String environment,
            @Param("project") String project,
            @Param("site") String site,
            @Param("geo") String geo,
            @Param("locale") String locale,
            Pageable pageable
    );

    /**
     * Loads distinct site values for UI options.
     */
    @Query("select distinct a.site from AssetImageStore a where a.site is not null and a.site <> '' order by a.site")
    List<String> findDistinctSites();

    /**
     * Loads distinct locale values for UI options.
     */
    @Query("select distinct a.locale from AssetImageStore a where a.locale is not null and a.locale <> '' order by a.locale")
    List<String> findDistinctLocales();
}
