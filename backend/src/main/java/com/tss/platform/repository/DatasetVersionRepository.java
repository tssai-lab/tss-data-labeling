package com.tss.platform.repository;

import com.tss.platform.entity.DatasetVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DatasetVersionRepository extends JpaRepository<DatasetVersion, String> {
    List<DatasetVersion> findByAssetId(String assetId);

    List<DatasetVersion> findByAssetIdAndDeletedFalse(String assetId);

    List<DatasetVersion> findByAssetIdIn(Collection<String> assetIds);

    List<DatasetVersion> findByAssetIdInAndDeletedFalse(Collection<String> assetIds);

    List<DatasetVersion> findByDeletedFalse();

    List<DatasetVersion> findByOwnerUserId(Integer ownerUserId);

    List<DatasetVersion> findByOwnerUserIdAndDeletedFalse(Integer ownerUserId);

    List<DatasetVersion> findByAssetIdAndOwnerUserId(String assetId, Integer ownerUserId);

    List<DatasetVersion> findByAssetIdAndOwnerUserIdAndDeletedFalse(String assetId, Integer ownerUserId);

    Optional<DatasetVersion> findByIdAndDeletedFalse(String id);

    List<DatasetVersion> findByIdInAndDeletedFalse(Collection<String> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DatasetVersion v where v.id = :id and v.deleted = false")
    Optional<DatasetVersion> findByIdAndDeletedFalseForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DatasetVersion v where v.id = :id")
    Optional<DatasetVersion> findByIdForUpdate(@Param("id") String id);

    @Modifying
    @Query("""
            update DatasetVersion v
            set v.fileCount = :count
            where v.id = :id
              and v.fileCount is null
              and v.deleted = false
            """)
    int updateFileCountIfAbsent(@Param("id") String id, @Param("count") Long count);

    Optional<DatasetVersion> findTopByAssetIdAndDeletedFalseOrderByCreatedAtDesc(String assetId);

    Optional<DatasetVersion> findTopByAssetIdAndArtifactSpecIdIsNotNullAndDeletedFalseOrderByVersionNoDesc(
            String assetId
    );

    Optional<DatasetVersion> findByAssetIdAndVersionNoAndDeletedFalse(String assetId, Integer versionNo);

    List<DatasetVersion> findByAssetIdAndDeletedFalseOrderByVersionNoDesc(String assetId);

    Optional<DatasetVersion> findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(String assetId, String status);

    @Query("""
            select v from DatasetVersion v
            where v.deleted = true
              and v.deletedAt < :deletedBefore
              and v.status <> 'ABANDONED'
            """)
    List<DatasetVersion> findByDeletedTrueAndDeletedAtBefore(
            @Param("deletedBefore") java.time.Instant deletedBefore
    );

    List<DatasetVersion> findByParentVersionIdAndDeletedFalse(String parentVersionId);

    long countByAssetIdAndDeletedFalse(String assetId);

    long countByParentVersionId(String parentVersionId);

    @Query("""
            select coalesce(max(v.versionNo), 0)
            from DatasetVersion v
            where v.assetId = :assetId
              and not (v.deleted = true and v.status = 'ABANDONED')
            """)
    Integer findMaxVersionNoByAssetId(@Param("assetId") String assetId);

    @Query("""
            select v from DatasetVersion v
            where v.assetId = :assetId
              and v.version = :version
              and not (v.deleted = true and v.status = 'ABANDONED')
            """)
    Optional<DatasetVersion> findByAssetIdAndVersion(
            @Param("assetId") String assetId,
            @Param("version") String version
    );

    @Query("""
            select (count(v) > 0) from DatasetVersion v
            where v.assetId = :assetId
              and v.version = :version
              and not (v.deleted = true and v.status = 'ABANDONED')
            """)
    boolean existsByAssetIdAndVersion(
            @Param("assetId") String assetId,
            @Param("version") String version
    );

    @Query("""
            select (count(v) > 0) from DatasetVersion v
            where v.assetId = :assetId
              and v.version = :version
              and v.id <> :id
              and not (v.deleted = true and v.status = 'ABANDONED')
            """)
    boolean existsByAssetIdAndVersionAndIdNot(
            @Param("assetId") String assetId,
            @Param("version") String version,
            @Param("id") String id
    );

    boolean existsByStoragePathAndDeletedFalseAndIdNot(String storagePath, String id);

    String CATALOG_READINESS_SQL = """
            select
                exists (
                    select 1
                    from dataset_upload_session upload
                    where upload.version_id = :workspaceId
                      and upload.status in ('UPLOADING', 'COMPLETING')
                ) as "activeUpload",
                exists (
                    select 1
                    from dataset_upload_session upload
                    where upload.version_id = :workspaceId
                      and upload.status not in (
                          'UPLOADING',
                          'COMPLETING',
                          'COMPLETED',
                          'DISCARDED'
                      )
                ) as "uploadNotSuccessful",
                exists (
                    select 1
                    from import_job job
                    where job.dataset_version_id = :workspaceId
                      and job.status not in ('SUCCESS', 'SUPERSEDED')
                ) as "importNotSuccessful",
                (
                    not exists (
                        select 1
                        from dataset_version_package relation
                        where relation.dataset_version_id = :workspaceId
                    )
                    or (
                        select count(*)
                        from dataset_version_package relation
                        where relation.dataset_version_id = :workspaceId
                          and relation.package_role = 'PRIMARY'
                    ) <> 1
                    or not exists (
                        select 1
                        from dataset_version_package relation
                        where relation.dataset_version_id = :workspaceId
                          and relation.package_role = 'PRIMARY'
                          and relation.package_order = 0
                    )
                    or (
                        select min(relation.package_order)
                        from dataset_version_package relation
                        where relation.dataset_version_id = :workspaceId
                    ) <> 0
                    or (
                        select max(relation.package_order)
                        from dataset_version_package relation
                        where relation.dataset_version_id = :workspaceId
                    ) <> (
                        select count(*) - 1
                        from dataset_version_package relation
                        where relation.dataset_version_id = :workspaceId
                    )
                    or exists (
                        select 1
                        from dataset_version_package relation
                        where relation.dataset_version_id = :workspaceId
                          and relation.package_role not in (
                              'PRIMARY',
                              'APPEND',
                              'OVERLAY'
                          )
                    )
                ) as "packageRelationInvalid",
                exists (
                    select 1
                    from dataset_version_package relation
                    left join dataset_package package
                      on package.id = relation.package_id
                    where relation.dataset_version_id = :workspaceId
                      and (
                          package.id is null
                          or package.deleted = true
                          or package.dataset_asset_id <> :assetId
                          or nullif(btrim(package.storage_path), '') is null
                          or package.status not in ('READY', 'SUPERSEDED')
                      )
                ) as "packageNotReady",
                exists (
                    select 1
                    from import_job job
                    where job.dataset_version_id = :workspaceId
                      and job.status <> 'SUPERSEDED'
                      and (
                          job.package_id is null
                          or not exists (
                              select 1
                              from dataset_version_package relation
                              join dataset_package package
                                on package.id = relation.package_id
                              where relation.dataset_version_id = :workspaceId
                                and relation.package_id = job.package_id
                                and package.deleted = false
                                and package.dataset_asset_id = :assetId
                                and package.status = 'READY'
                                and nullif(
                                    btrim(package.storage_path),
                                    ''
                                ) is not null
                          )
                      )
                ) as "importPackageInvalid",
                not exists (
                    select 1
                    from dataset_sample sample
                    where sample.dataset_version_id = :workspaceId
                      and sample.deleted = false
                ) as "noActiveSample",
                exists (
                    select 1
                    from dataset_sample sample
                    where sample.dataset_version_id = :workspaceId
                      and sample.deleted = false
                    group by sample.external_id
                    having count(*) > 1
                ) as "duplicateExternalId",
                exists (
                    select 1
                    from dataset_sample sample
                    where sample.dataset_version_id = :workspaceId
                      and sample.deleted = false
                    group by sample.sample_index
                    having count(*) > 1
                ) as "duplicateSampleIndex",
                exists (
                    select 1
                    from dataset_sample sample
                    where sample.dataset_version_id = :workspaceId
                      and sample.deleted = false
                      and not exists (
                          select 1
                          from dataset_sample_data data
                          where data.dataset_version_id = :workspaceId
                            and data.sample_id = sample.id
                            and data.deleted = false
                      )
                ) as "emptySample",
                exists (
                    select 1
                    from dataset_sample_data data
                    join dataset_sample sample
                      on sample.id = data.sample_id
                     and sample.dataset_version_id = :workspaceId
                     and sample.deleted = false
                    where data.dataset_version_id = :workspaceId
                      and data.deleted = false
                      and (
                          nullif(btrim(data.file_name), '') is null
                          or nullif(btrim(data.format), '') is null
                          or nullif(btrim(data.content_type), '') is null
                          or data.size_bytes is null
                          or data.size_bytes < 0
                      )
                    union all
                    select 1
                    from dataset_annotation annotation
                    join dataset_sample sample
                      on sample.id = annotation.sample_id
                     and sample.dataset_version_id = :workspaceId
                     and sample.deleted = false
                    where annotation.dataset_version_id = :workspaceId
                      and annotation.deleted = false
                      and (
                          nullif(btrim(annotation.file_name), '') is null
                          or nullif(btrim(annotation.format), '') is null
                          or nullif(
                              btrim(annotation.content_type),
                              ''
                          ) is null
                          or annotation.size_bytes is null
                          or annotation.size_bytes < 0
                      )
                ) as "resourceDescriptorInvalid",
                exists (
                    select 1
                    from dataset_sample_data data
                    join dataset_sample sample
                      on sample.id = data.sample_id
                     and sample.dataset_version_id = :workspaceId
                     and sample.deleted = false
                    where data.dataset_version_id = :workspaceId
                      and data.deleted = false
                      and (
                          not exists (
                              select 1
                              from dataset_version_package relation
                              join dataset_package package
                                on package.id = relation.package_id
                              where relation.dataset_version_id = :workspaceId
                                and relation.package_id = data.package_id
                                and package.deleted = false
                                and package.dataset_asset_id = :assetId
                                and package.status = 'READY'
                                and nullif(
                                    btrim(package.storage_path),
                                    ''
                                ) is not null
                          )
                          or exists (
                              select 1
                              from dataset_package package
                              where package.id = data.package_id
                                and package.deleted = false
                                and package.dataset_asset_id = :assetId
                                and package.status = 'READY'
                                and (
                                    (
                                        package.storage_kind = 'RAW'
                                        and (
                                            data.checksum is null
                                            or data.checksum
                                                !~ '^[0-9A-Fa-f]{64}$'
                                        )
                                    )
                                    or (
                                        package.storage_kind <> 'RAW'
                                        and (
                                            data.zip_data_offset is null
                                            or data.zip_data_offset < 0
                                            or data.compressed_size is null
                                            or data.compressed_size < 0
                                            or data.compression_method is null
                                            or upper(data.compression_method)
                                                not in ('STORED', 'DEFLATED')
                                        )
                                    )
                                )
                          )
                      )
                    union all
                    select 1
                    from dataset_annotation annotation
                    join dataset_sample sample
                      on sample.id = annotation.sample_id
                     and sample.dataset_version_id = :workspaceId
                     and sample.deleted = false
                    where annotation.dataset_version_id = :workspaceId
                      and annotation.deleted = false
                      and (
                          not exists (
                              select 1
                              from dataset_version_package relation
                              join dataset_package package
                                on package.id = relation.package_id
                              where relation.dataset_version_id = :workspaceId
                                and relation.package_id =
                                    annotation.package_id
                                and package.deleted = false
                                and package.dataset_asset_id = :assetId
                                and package.status = 'READY'
                                and nullif(
                                    btrim(package.storage_path),
                                    ''
                                ) is not null
                          )
                          or exists (
                              select 1
                              from dataset_package package
                              where package.id = annotation.package_id
                                and package.deleted = false
                                and package.dataset_asset_id = :assetId
                                and package.status = 'READY'
                                and (
                                    (
                                        package.storage_kind = 'RAW'
                                        and (
                                            annotation.checksum is null
                                            or annotation.checksum
                                                !~ '^[0-9A-Fa-f]{64}$'
                                        )
                                    )
                                    or (
                                        package.storage_kind <> 'RAW'
                                        and (
                                            annotation.zip_data_offset is null
                                            or annotation.zip_data_offset < 0
                                            or annotation.compressed_size is null
                                            or annotation.compressed_size < 0
                                            or annotation.compression_method
                                                is null
                                            or upper(
                                                annotation.compression_method
                                            ) not in (
                                                'STORED',
                                                'DEFLATED'
                                            )
                                        )
                                    )
                                )
                          )
                      )
                ) as "resourceStorageInvalid",
                exists (
                    select 1
                    from dataset_annotation annotation
                    join dataset_sample sample
                      on sample.id = annotation.sample_id
                     and sample.dataset_version_id = :workspaceId
                     and sample.deleted = false
                    left join dataset_sample_data data
                      on data.id = annotation.sample_data_id
                     and data.dataset_version_id = :workspaceId
                     and data.deleted = false
                    where annotation.dataset_version_id = :workspaceId
                      and annotation.deleted = false
                      and annotation.sample_data_id is not null
                      and (
                          data.id is null
                          or data.sample_id <> annotation.sample_id
                      )
                ) as "annotationTargetInvalid"
            """;
}
