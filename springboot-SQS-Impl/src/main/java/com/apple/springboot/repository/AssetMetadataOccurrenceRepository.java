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
                    select o.*
                    from asset_metadata_occurrence o
                    where (:tenant is null or lower(convert_from(cast(o.tenant as bytea), 'UTF8')) = lower(cast(:tenant as text)))
                      and (:environment is null or lower(convert_from(cast(o.environment as bytea), 'UTF8')) = lower(cast(:environment as text)))
                      and (:project is null or lower(convert_from(cast(o.project as bytea), 'UTF8')) = lower(cast(:project as text)))
                      and (:site is null or lower(convert_from(cast(o.site as bytea), 'UTF8')) = lower(cast(:site as text)))
                      and (:geo is null or lower(convert_from(cast(o.geo as bytea), 'UTF8')) = lower(cast(:geo as text)))
                      and (:locale is null or lower(convert_from(cast(o.locale as bytea), 'UTF8')) = lower(cast(:locale as text)))
                    order by o.created_at desc
                    """,
            countQuery = """
                    select count(*)
                    from asset_metadata_occurrence o
                    where (:tenant is null or lower(convert_from(cast(o.tenant as bytea), 'UTF8')) = lower(cast(:tenant as text)))
                      and (:environment is null or lower(convert_from(cast(o.environment as bytea), 'UTF8')) = lower(cast(:environment as text)))
                      and (:project is null or lower(convert_from(cast(o.project as bytea), 'UTF8')) = lower(cast(:project as text)))
                      and (:site is null or lower(convert_from(cast(o.site as bytea), 'UTF8')) = lower(cast(:site as text)))
                      and (:geo is null or lower(convert_from(cast(o.geo as bytea), 'UTF8')) = lower(cast(:geo as text)))
                      and (:locale is null or lower(convert_from(cast(o.locale as bytea), 'UTF8')) = lower(cast(:locale as text)))
                    """,
            nativeQuery = true
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
    @Query(
            value = """
                    select distinct convert_from(cast(o.site as bytea), 'UTF8') as site
                    from asset_metadata_occurrence o
                    where o.site is not null
                      and convert_from(cast(o.site as bytea), 'UTF8') <> ''
                    order by site
                    """,
            nativeQuery = true
    )
    List<String> findDistinctSites();

    /**
     * Loads distinct geo/locale pairs from current extracted asset rows.
     */
    @Query(
            value = """
                    select distinct
                        convert_from(cast(o.geo as bytea), 'UTF8') as geo,
                        convert_from(cast(o.locale as bytea), 'UTF8') as locale
                    from asset_metadata_occurrence o
                    where o.geo is not null
                      and convert_from(cast(o.geo as bytea), 'UTF8') <> ''
                      and o.locale is not null
                      and convert_from(cast(o.locale as bytea), 'UTF8') <> ''
                    order by geo, locale
                    """,
            nativeQuery = true
    )
    List<GeoLocaleProjection> findDistinctGeoLocalePairs();
}
