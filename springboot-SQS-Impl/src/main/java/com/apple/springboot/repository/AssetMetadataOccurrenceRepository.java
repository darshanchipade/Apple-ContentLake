package com.apple.springboot.repository;

import com.apple.springboot.model.AssetMetadataOccurrence;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetMetadataOccurrenceRepository extends JpaRepository<AssetMetadataOccurrence, UUID> {

    /**
     * Projection for distinct geo/locale combinations.
     */
    interface GeoLocaleProjection {
        String getGeo();
        String getLocale();
    }

    /**
     * Deletes occurrence rows for a raw_data_store record.
     */
    void deleteByRawDataId(UUID rawDataId);

    /**
     * Deletes occurrence rows for a source/version pair.
     */
    void deleteBySourceUriAndSourceVersion(String sourceUri, Integer sourceVersion);

    /**
     * Counts occurrence rows for a raw_data_store record.
     */
    long countByRawDataId(UUID rawDataId);

    /**
     * Performs Asset Finder filtering with optional exact-match filters.
     */
    @Query(
            value = """
                    select o from AssetMetadataOccurrence o
                    where (:tenant is null or lower(o.tenant) = lower(:tenant))
                      and (:environment is null or lower(o.environment) = lower(:environment))
                      and (:project is null or lower(o.project) = lower(:project))
                      and (:site is null or lower(o.site) = lower(:site))
                      and (:geo is null or lower(o.geo) = lower(:geo))
                      and (:locale is null or lower(o.locale) = lower(:locale))
                    """,
            countQuery = """
                    select count(o) from AssetMetadataOccurrence o
                    where (:tenant is null or lower(o.tenant) = lower(:tenant))
                      and (:environment is null or lower(o.environment) = lower(:environment))
                      and (:project is null or lower(o.project) = lower(:project))
                      and (:site is null or lower(o.site) = lower(:site))
                      and (:geo is null or lower(o.geo) = lower(:geo))
                      and (:locale is null or lower(o.locale) = lower(:locale))
                    """
    )
    Page<AssetMetadataOccurrence> search(
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
    @Query("select distinct o.site from AssetMetadataOccurrence o where o.site is not null and o.site <> '' order by o.site")
    List<String> findDistinctSites();

    /**
     * Loads distinct geo/locale pairs from current extracted asset rows.
     */
    @Query("""
            select distinct o.geo as geo, o.locale as locale
            from AssetMetadataOccurrence o
            where o.geo is not null and o.geo <> ''
              and o.locale is not null and o.locale <> ''
            order by o.geo, o.locale
            """)
    List<GeoLocaleProjection> findDistinctGeoLocalePairs();
}
