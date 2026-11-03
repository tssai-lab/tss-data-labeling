package com.tss.platform.repository;

public record DatasetCatalogReadinessSnapshot(
        boolean activeUpload,
        boolean uploadNotSuccessful,
        boolean importNotSuccessful,
        boolean packageRelationInvalid,
        boolean packageNotReady,
        boolean importPackageInvalid,
        boolean noActiveSample,
        boolean duplicateExternalId,
        boolean duplicateSampleIndex,
        boolean emptySample,
        boolean resourceDescriptorInvalid,
        boolean resourceStorageInvalid,
        boolean annotationTargetInvalid
) {
}
