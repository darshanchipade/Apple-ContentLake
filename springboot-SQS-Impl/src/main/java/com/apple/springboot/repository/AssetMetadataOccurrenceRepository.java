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
         * Projection for deriving site/subpage options from stored paths.
         */
        interface SitePathProjection {
                String getSite();

                String getSourceUri();

                String getSectionPath();

                String getSectionUri();
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
         * Loads latest occurrence rows for a source URI (active + inactive).
         */
        List<AssetMetadataOccurrence> findBySourceUri(String sourceUri);

        /**
         * Counts occurrence rows for a raw_data_store record.
         */
        long countByRawDataId(UUID rawDataId);

        /**
         * Performs Asset Finder filtering with optional exact-match filters.
         */
        @Query(value = """
                        select distinct on (coalesce(c.interactive_path, c.preview_uri)) o.*
                        from public.asset_metadata_occurrence o
                        inner join public.asset_metadata_catalog c on o.catalog_id = c.id
                        where (:tenant is null or lower(convert_from(cast(o.tenant as bytea), 'UTF8')) = lower(cast(:tenant as text)))
                          and o.active = true
                          and (:environment is null or lower(convert_from(cast(o.environment as bytea), 'UTF8')) = lower(cast(:environment as text)))
                          and (:project is null or lower(convert_from(cast(o.project as bytea), 'UTF8')) = lower(cast(:project as text)))
                          and (
                                :site is null
                                or lower(convert_from(cast(o.site as bytea), 'UTF8')) = lower(cast(:site as text))
                              )
                          and (:geo is null or lower(convert_from(cast(o.geo as bytea), 'UTF8')) = lower(cast(:geo as text)))
                          and (:locale is null or lower(convert_from(cast(o.locale as bytea), 'UTF8')) = lower(cast(:locale as text)))
                          and (c.interactive_path is null or c.interactive_path not like '%unresolved-css-sprite%')
                        order by coalesce(c.interactive_path, c.preview_uri), o.created_at desc
                        """, countQuery = """
                        select count(distinct coalesce(c.interactive_path, c.preview_uri))
                        from public.asset_metadata_occurrence o
                        inner join public.asset_metadata_catalog c on o.catalog_id = c.id
                        where (:tenant is null or lower(convert_from(cast(o.tenant as bytea), 'UTF8')) = lower(cast(:tenant as text)))
                          and o.active = true
                          and (:environment is null or lower(convert_from(cast(o.environment as bytea), 'UTF8')) = lower(cast(:environment as text)))
                          and (:project is null or lower(convert_from(cast(o.project as bytea), 'UTF8')) = lower(cast(:project as text)))
                          and (
                                :site is null
                                or lower(convert_from(cast(o.site as bytea), 'UTF8')) = lower(cast(:site as text))
                              )
                          and (:geo is null or lower(convert_from(cast(o.geo as bytea), 'UTF8')) = lower(cast(:geo as text)))
                          and (:locale is null or lower(convert_from(cast(o.locale as bytea), 'UTF8')) = lower(cast(:locale as text)))
                          and (c.interactive_path is null or c.interactive_path not like '%unresolved-css-sprite%')
                        """, nativeQuery = true)
        Page<AssetMetadataOccurrence> search(
                        @Param("tenant") String tenant,
                        @Param("environment") String environment,
                        @Param("project") String project,
                        @Param("site") String site,
                        @Param("geo") String geo,
                        @Param("locale") String locale,
                        Pageable pageable);

        /**
         * Loads distinct site values for UI options.
         */
        @Query(value = """
                        select distinct convert_from(cast(o.site as bytea), 'UTF8') as site
                        from public.asset_metadata_occurrence o
                        where o.active = true
                          and o.site is not null
                          and convert_from(cast(o.site as bytea), 'UTF8') <> ''
                        order by site
                        """, nativeQuery = true)
        List<String> findDistinctSites();

        /**
         * Loads distinct site + path tuples for deriving page-context options.
         */
        @Query(value = """
                        select distinct
                            convert_from(cast(o.site as bytea), 'UTF8') as site,
                            convert_from(cast(o.source_uri as bytea), 'UTF8') as sourceUri,
                            convert_from(cast(o.section_path as bytea), 'UTF8') as sectionPath,
                            convert_from(cast(o.section_uri as bytea), 'UTF8') as sectionUri
                        from public.asset_metadata_occurrence o
                        where o.active = true
                        """, nativeQuery = true)
        List<SitePathProjection> findDistinctSitePathTuples();

        /**
         * Loads distinct geo/locale pairs from current extracted asset rows.
         */
        @Query(value = """
                        select distinct
                            convert_from(cast(o.geo as bytea), 'UTF8') as geo,
                            convert_from(cast(o.locale as bytea), 'UTF8') as locale
                        from public.asset_metadata_occurrence o
                        where o.active = true
                          and o.geo is not null
                          and convert_from(cast(o.geo as bytea), 'UTF8') <> ''
                          and o.locale is not null
                          and convert_from(cast(o.locale as bytea), 'UTF8') <> ''
                        order by geo, locale
                        """, nativeQuery = true)
        List<GeoLocaleProjection> findDistinctGeoLocalePairs();

        /**
         * Returns image interactive paths and metadata for the given section paths (for semantic search image enrichment).
         * Each element in the returned list is an Object[] of [sectionPath, assetModel, altText, interactivePath].
         */
        @Query("select o.sectionPath, c.assetModel, c.altText, c.interactivePath from AssetMetadataOccurrence o " +
               "join AssetMetadataCatalog c on o.catalogId = c.id " +
               "where o.sectionPath in :sectionPaths and o.active = true " +
               "and c.interactivePath is not null and c.interactivePath not like '#%' " +
               "and c.interactivePath not like '%unresolved-css-sprite%'")
        List<Object[]> findImageUrlsBySectionPaths(@Param("sectionPaths") List<String> sectionPaths);

        /**
         * Returns image interactive paths and metadata for the given section URIs (for highly granular semantic search image enrichment).
         * Each element in the returned list is an Object[] of [sectionUri, assetModel, altText, interactivePath].
         */
        @Query("select o.sectionUri, c.assetModel, c.altText, c.interactivePath from AssetMetadataOccurrence o " +
               "join AssetMetadataCatalog c on o.catalogId = c.id " +
               "where o.sectionUri in :sectionUris and o.active = true " +
               "and c.interactivePath is not null and c.interactivePath not like '#%' " +
               "and c.interactivePath not like '%unresolved-css-sprite%'")
        List<Object[]> findImageUrlsBySectionUris(@Param("sectionUris") List<String> sectionUris);

        /**
         * Returns image interactive paths and metadata for the given section paths, including their sectionUris.
         * Each element in the returned list is an Object[] of [sectionPath, sectionUri, assetModel, altText, interactivePath].
         */
        @Query("select o.sectionPath, o.sectionUri, c.assetModel, c.altText, c.interactivePath from AssetMetadataOccurrence o " +
               "join AssetMetadataCatalog c on o.catalogId = c.id " +
               "where o.sectionPath in :sectionPaths and o.active = true " +
               "and c.interactivePath is not null and c.interactivePath not like '#%' " +
               "and c.interactivePath not like '%unresolved-css-sprite%'")
        List<Object[]> findImageUrlsWithUrisBySectionPaths(@Param("sectionPaths") List<String> sectionPaths);

        /**
         * Returns image interactive paths and metadata for the given source URIs (page URLs).
         * Used as a fallback when sectionPath matching fails (e.g. HTML content sections).
         * Each element is an Object[] of [sectionPath, sectionUri, assetModel, altText, interactivePath].
         */
        @Query("select o.sectionPath, o.sectionUri, c.assetModel, c.altText, c.interactivePath, o.sourceUri from AssetMetadataOccurrence o " +
               "join AssetMetadataCatalog c on o.catalogId = c.id " +
               "where o.sourceUri in :sourceUris and o.active = true " +
               "and c.interactivePath is not null and c.interactivePath not like '#%' " +
               "and c.interactivePath not like '%unresolved-css-sprite%'")
        List<Object[]> findImageUrlsWithUrisBySourceUris(@Param("sourceUris") List<String> sourceUris);
}