package com.tss.platform.service;

import com.tss.platform.dto.DatasetWorkspacePublishDto;
import com.tss.platform.dto.v2.V2DatasetPublishReadiness;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DatasetWorkspacePublishService {

    private static final String DRAFT = "DRAFT";
    private static final String READY = "READY";
    private static final String SUCCESS = "SUCCESS";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String PRIMARY = "PRIMARY";
    private static final String APPEND = "APPEND";
    private static final String OVERLAY = "OVERLAY";
    private static final String NOT_FOUND =
            "dataset workspace version not found or no permission";

    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final ImportJobRepository importJobRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetPackageRepository packageRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final AuthContext authContext;
    private final DatasetWorkspaceAuditService auditService;
    private final DatasetWorkspaceReadinessService readinessService;
    private final DatasetWorkspaceRawStorageService rawStorageService;

    @Autowired
    public DatasetWorkspacePublishService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            ImportJobRepository importJobRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetPackageRepository packageRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            AuthContext authContext,
            DatasetWorkspaceAuditService auditService,
            DatasetWorkspaceReadinessService readinessService,
            DatasetWorkspaceRawStorageService rawStorageService
    ) {
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.importJobRepo = importJobRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.packageRepo = packageRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.authContext = authContext;
        this.auditService = auditService;
        this.readinessService = readinessService;
        this.rawStorageService = rawStorageService;
    }

    DatasetWorkspacePublishService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            ImportJobRepository importJobRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetPackageRepository packageRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            AuthContext authContext
    ) {
        this(
                versionRepo,
                assetRepo,
                importJobRepo,
                versionPackageRepo,
                packageRepo,
                sampleRepo,
                dataRepo,
                annotationRepo,
                authContext,
                null,
                null,
                null
        );
    }

    DatasetWorkspacePublishService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            ImportJobRepository importJobRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetPackageRepository packageRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            AuthContext authContext,
            DatasetWorkspaceAuditService auditService
    ) {
        this(
                versionRepo,
                assetRepo,
                importJobRepo,
                versionPackageRepo,
                packageRepo,
                sampleRepo,
                dataRepo,
                annotationRepo,
                authContext,
                auditService,
                null,
                null
        );
    }

    @Transactional
    public DatasetWorkspacePublishDto publish(String draftVersionId) {
        if (draftVersionId == null || draftVersionId.isBlank()) {
            throw new IllegalArgumentException(NOT_FOUND);
        }

        DatasetVersion snapshot = versionRepo.findByIdAndDeletedFalse(draftVersionId)
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
        DatasetAsset asset = assetRepo
                .findByIdAndDeletedFalseForUpdate(snapshot.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
        DatasetVersion draft = versionRepo
                .findByIdAndDeletedFalseForUpdate(draftVersionId)
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));

        if (!asset.getId().equals(draft.getAssetId())
                || !authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new IllegalArgumentException(NOT_FOUND);
        }
        if (!DRAFT.equals(draft.getStatus())) {
            throw new IllegalArgumentException("dataset version must be DRAFT");
        }

        if (readinessService != null) {
            V2DatasetPublishReadiness readiness =
                    readinessService.evaluate(asset, draft);
            if (!readiness.canPublish()) {
                throw new IllegalArgumentException(
                        "DATASET_NOT_PUBLISHABLE: " + readiness.blockers()
                );
            }
        } else {
            validateLineage(asset, draft);
            List<ImportJob> importJobs = validateImportJobs(draft.getId());
            Set<String> linkedPackageIds = validatePackages(asset, draft);
            validateImportJobPackages(importJobs, linkedPackageIds);
            validateSamples(draft.getId());
            validateMetadataPackageReferences(draft.getId(), linkedPackageIds);
        }
        purgeDeletedContent(asset, draft);

        Instant now = Instant.now();
        draft.setStatus(READY);
        draft.setPublishedAt(now);
        draft.setUpdatedAt(now);
        draft.setFileCount(
                dataRepo.countByDatasetVersionIdAndDeletedFalse(draft.getId())
                        + annotationRepo.countByDatasetVersionIdAndDeletedFalse(
                                draft.getId()
                        )
        );
        versionRepo.saveAndFlush(draft);

        asset.setCurrentVersionId(draft.getId());
        asset.setUpdatedAt(now);
        assetRepo.saveAndFlush(asset);
        if (auditService != null) {
            auditService.recordVersionPublished(asset, draft);
        }

        DatasetWorkspacePublishDto dto = new DatasetWorkspacePublishDto();
        dto.setDatasetVersionId(draft.getId());
        dto.setDatasetAssetId(asset.getId());
        dto.setParentVersionId(draft.getParentVersionId());
        dto.setVersionNo(draft.getVersionNo());
        dto.setVersionLabel(displayVersionLabel(draft));
        dto.setStatus(draft.getStatus());
        dto.setPublishedAt(draft.getPublishedAt());
        dto.setCurrentVersionId(asset.getCurrentVersionId());
        dto.setMessage("dataset workspace published");
        return dto;
    }

    private String displayVersionLabel(DatasetVersion version) {
        return version.getVersionLabel() == null
                || version.getVersionLabel().isBlank()
                ? version.getVersion()
                : version.getVersionLabel();
    }

    private void validateLineage(DatasetAsset asset, DatasetVersion draft) {
        if (draft.getParentVersionId() == null || draft.getParentVersionId().isBlank()) {
            throw new IllegalArgumentException("dataset workspace parent READY version is missing");
        }
        DatasetVersion parent = versionRepo
                .findByIdAndDeletedFalse(draft.getParentVersionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset workspace parent READY version is missing"
                ));
        if (!asset.getId().equals(parent.getAssetId()) || !READY.equals(parent.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset workspace parent version must be READY"
            );
        }

        String currentVersionId = asset.getCurrentVersionId();
        if (currentVersionId == null || currentVersionId.isBlank()) {
            throw new IllegalArgumentException("dataset asset current READY version is missing");
        }
        if (draft.getWorkspaceHeadVersionId() == null
                || !currentVersionId.equals(draft.getWorkspaceHeadVersionId())) {
            throw new IllegalArgumentException(
                    "BASE_VERSION_STALE: dataset current READY version changed"
            );
        }
        DatasetVersion current = versionRepo.findByIdAndDeletedFalse(currentVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset asset current READY version is missing"
                ));
        if (!asset.getId().equals(current.getAssetId()) || !READY.equals(current.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset asset current version must be READY"
            );
        }
        if (draft.getVersionNo() == null
                || current.getVersionNo() == null
                || draft.getVersionNo() <= current.getVersionNo()) {
            throw new IllegalArgumentException(
                    "draft version must be newer than current READY version"
            );
        }
    }

    private List<ImportJob> validateImportJobs(String draftVersionId) {
        List<ImportJob> importJobs =
                importJobRepo.findByDatasetVersionId(draftVersionId);
        for (ImportJob job : importJobs) {
            if (!isPublishTerminalJobStatus(job.getStatus())) {
                throw new IllegalArgumentException(
                        "dataset workspace has non-success ImportJob: " + job.getStatus()
                );
            }
        }
        return importJobs;
    }

    private void validateImportJobPackages(
            List<ImportJob> importJobs,
            Set<String> linkedPackageIds
    ) {
        for (ImportJob job : importJobs) {
            if (SUPERSEDED.equals(job.getStatus())) {
                continue;
            }
            if (job.getPackageId() == null
                    || job.getPackageId().isBlank()
                    || !linkedPackageIds.contains(job.getPackageId())) {
                throw new IllegalArgumentException(
                        "dataset workspace ImportJob references unlinked package: "
                                + job.getPackageId()
                );
            }
        }
    }

    private Set<String> validatePackages(DatasetAsset asset, DatasetVersion draft) {
        List<DatasetVersionPackage> relations =
                versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                        draft.getId()
                );
        if (relations.isEmpty()) {
            throw new IllegalArgumentException("dataset workspace has no package relation");
        }

        Set<String> allPackageIds = new LinkedHashSet<>();
        for (int index = 0; index < relations.size(); index++) {
            DatasetVersionPackage relation = relations.get(index);
            if (!draft.getId().equals(relation.getDatasetVersionId())
                    || relation.getPackageOrder() == null
                    || relation.getPackageOrder() != index) {
                throw new IllegalArgumentException(
                        "dataset workspace package order is incomplete"
                );
            }
            if (!PRIMARY.equals(relation.getPackageRole())
                    && !APPEND.equals(relation.getPackageRole())
                    && !OVERLAY.equals(relation.getPackageRole())) {
                throw new IllegalArgumentException(
                        "dataset workspace package role is invalid: "
                                + relation.getPackageRole()
                );
            }
            if (relation.getPackageId() == null
                    || relation.getPackageId().isBlank()
                    || !allPackageIds.add(relation.getPackageId())) {
                throw new IllegalArgumentException(
                        "dataset workspace package relation is invalid"
                );
            }
        }

        Map<String, DatasetPackage> packagesById = new HashMap<>();
        for (DatasetPackage datasetPackage :
                packageRepo.findAllById(List.copyOf(allPackageIds))) {
            packagesById.put(datasetPackage.getId(), datasetPackage);
        }
        Set<String> activePackageIds = new LinkedHashSet<>();
        for (DatasetVersionPackage relation : relations) {
            String packageId = relation.getPackageId();
            DatasetPackage datasetPackage = packagesById.get(packageId);
            if (datasetPackage == null
                    || Boolean.TRUE.equals(datasetPackage.getDeleted())
                    || !asset.getId().equals(datasetPackage.getDatasetAssetId())) {
                throw new IllegalArgumentException(
                        "dataset workspace package is missing: " + packageId
                );
            }
            if (SUPERSEDED.equals(datasetPackage.getStatus())) {
                continue;
            }
            if (PRIMARY.equals(relation.getPackageRole())) {
                if (relation.getPackageOrder() == null
                        || relation.getPackageOrder() != 0) {
                    throw new IllegalArgumentException(
                            "dataset workspace PRIMARY package must be first"
                    );
                }
            }
            if (!READY.equals(datasetPackage.getStatus())) {
                throw new IllegalArgumentException(
                        "dataset workspace package is not READY: "
                                + packageId + ", status=" + datasetPackage.getStatus()
                );
            }
            if (datasetPackage.getStoragePath() == null
                    || datasetPackage.getStoragePath().isBlank()) {
                throw new IllegalArgumentException(
                        "dataset workspace package storage is missing: " + packageId
                );
            }
            activePackageIds.add(packageId);
        }
        return activePackageIds;
    }

    private static boolean isPublishTerminalJobStatus(String status) {
        return SUCCESS.equals(status) || SUPERSEDED.equals(status);
    }

    private void validateSamples(String draftVersionId) {
        if (sampleRepo.countByDatasetVersionIdAndDeletedFalse(draftVersionId) == 0) {
            throw new IllegalArgumentException(
                    "dataset workspace must contain at least one undeleted sample"
            );
        }
        List<String> duplicateExternalIds =
                sampleRepo.findDuplicateExternalIdsByDatasetVersionId(draftVersionId);
        if (!duplicateExternalIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "duplicate externalId in dataset workspace: "
                            + duplicateExternalIds.get(0)
            );
        }
        List<Integer> duplicateSampleIndexes =
                sampleRepo.findDuplicateSampleIndexesByDatasetVersionId(draftVersionId);
        if (!duplicateSampleIndexes.isEmpty()) {
            throw new IllegalArgumentException(
                    "duplicate sampleIndex in dataset workspace: "
                            + duplicateSampleIndexes.get(0)
            );
        }
    }

    private void validateMetadataPackageReferences(
            String draftVersionId,
            Set<String> linkedPackageIds
    ) {
        if (readinessService == null) {
            validateLegacyMetadataPackageReferences(
                    draftVersionId,
                    linkedPackageIds
            );
            return;
        }
        Set<String> referencedPackageIds = new LinkedHashSet<>();
        Set<String> activeSampleIds = new LinkedHashSet<>(
                sampleRepo.findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        draftVersionId
                ).stream().map(DatasetSample::getId).toList()
        );
        for (DatasetSampleData data : dataRepo.findByDatasetVersionId(
                draftVersionId
        )) {
            if (Boolean.TRUE.equals(data.getDeleted())
                    || !activeSampleIds.contains(data.getSampleId())) {
                continue;
            }
            if (data.getPackageId() == null || data.getPackageId().isBlank()) {
                throw new IllegalArgumentException(
                        "dataset workspace sample data packageId is missing"
                );
            }
            referencedPackageIds.add(data.getPackageId());
        }
        for (DatasetAnnotation annotation : annotationRepo
                .findByDatasetVersionId(draftVersionId)) {
            if (Boolean.TRUE.equals(annotation.getDeleted())
                    || !activeSampleIds.contains(annotation.getSampleId())) {
                continue;
            }
            if (annotation.getPackageId() == null
                    || annotation.getPackageId().isBlank()) {
                throw new IllegalArgumentException(
                        "dataset workspace annotation packageId is missing"
                );
            }
            referencedPackageIds.add(annotation.getPackageId());
        }
        for (String packageId : referencedPackageIds) {
            if (!linkedPackageIds.contains(packageId)) {
                throw new IllegalArgumentException(
                        "dataset workspace metadata references unlinked package: "
                                + packageId
                );
            }
        }
    }

    private void validateLegacyMetadataPackageReferences(
            String draftVersionId,
            Set<String> linkedPackageIds
    ) {
        if (sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdIsNull(
                draftVersionId
        ) > 0) {
            throw new IllegalArgumentException(
                    "dataset workspace sample packageId is missing"
            );
        }
        if (dataRepo.countByDatasetVersionIdAndPackageIdIsNull(
                draftVersionId
        ) > 0) {
            throw new IllegalArgumentException(
                    "dataset workspace sample data packageId is missing"
            );
        }
        if (annotationRepo.countByDatasetVersionIdAndPackageIdIsNull(
                draftVersionId
        ) > 0) {
            throw new IllegalArgumentException(
                    "dataset workspace annotation packageId is missing"
            );
        }
        Set<String> referenced = new LinkedHashSet<>();
        referenced.addAll(
                sampleRepo.findDistinctCreatedByPackageIdsByDatasetVersionId(
                        draftVersionId
                )
        );
        referenced.addAll(
                dataRepo.findDistinctPackageIdsByDatasetVersionId(draftVersionId)
        );
        referenced.addAll(
                annotationRepo.findDistinctPackageIdsByDatasetVersionId(
                        draftVersionId
                )
        );
        for (String packageId : referenced) {
            if (!linkedPackageIds.contains(packageId)) {
                throw new IllegalArgumentException(
                        "dataset workspace metadata references unlinked package: "
                                + packageId
                );
            }
        }
    }

    private void purgeDeletedContent(
            DatasetAsset asset,
            DatasetVersion draft
    ) {
        Set<String> deletedSampleIds = new LinkedHashSet<>();
        for (DatasetSample sample : sampleRepo.findByDatasetVersionId(
                draft.getId()
        )) {
            if (Boolean.TRUE.equals(sample.getDeleted())) {
                deletedSampleIds.add(sample.getId());
            }
        }
        List<DatasetAnnotation> annotations = annotationRepo
                .findByDatasetVersionId(draft.getId());
        annotationRepo.deleteAll(annotations.stream()
                .filter(annotation -> Boolean.TRUE.equals(annotation.getDeleted())
                        || deletedSampleIds.contains(annotation.getSampleId()))
                .toList());
        annotationRepo.flush();
        List<DatasetSampleData> data = dataRepo.findByDatasetVersionId(draft.getId());
        dataRepo.deleteAll(data.stream()
                .filter(item -> Boolean.TRUE.equals(item.getDeleted())
                        || deletedSampleIds.contains(item.getSampleId()))
                .toList());
        dataRepo.flush();
        sampleRepo.deleteByDatasetVersionIdAndDeletedTrue(draft.getId());
        sampleRepo.flush();

        if (rawStorageService != null) {
            List<String> overlayIds = versionPackageRepo
                    .findByDatasetVersionIdOrderByPackageOrderAsc(draft.getId())
                    .stream()
                    .filter(relation -> OVERLAY.equals(relation.getPackageRole()))
                    .map(DatasetVersionPackage::getPackageId)
                    .toList();
            for (String packageId : overlayIds) {
                rawStorageService.releaseIfUnreferenced(
                        draft,
                        packageId,
                        asset.getOwnerUserId()
                );
            }
        }
    }
}
