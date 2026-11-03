package com.tss.platform.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DatasetCatalogReadinessRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DatasetCatalogReadinessRepository(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DatasetCatalogReadinessSnapshot inspect(
            String workspaceId,
            String assetId
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("assetId", assetId);
        return jdbcTemplate.queryForObject(
                DatasetVersionRepository.CATALOG_READINESS_SQL,
                parameters,
                (result, rowNumber) -> new DatasetCatalogReadinessSnapshot(
                        result.getBoolean("activeUpload"),
                        result.getBoolean("uploadNotSuccessful"),
                        result.getBoolean("importNotSuccessful"),
                        result.getBoolean("packageRelationInvalid"),
                        result.getBoolean("packageNotReady"),
                        result.getBoolean("importPackageInvalid"),
                        result.getBoolean("noActiveSample"),
                        result.getBoolean("duplicateExternalId"),
                        result.getBoolean("duplicateSampleIndex"),
                        result.getBoolean("emptySample"),
                        result.getBoolean("resourceDescriptorInvalid"),
                        result.getBoolean("resourceStorageInvalid"),
                        result.getBoolean("annotationTargetInvalid")
                )
        );
    }
}
