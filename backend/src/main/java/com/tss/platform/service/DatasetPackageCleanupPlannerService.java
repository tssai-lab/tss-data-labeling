package com.tss.platform.service;

import com.tss.platform.dto.DatasetPackageCleanupBlockerDto;
import com.tss.platform.dto.DatasetPackageCleanupPlanDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.MinioDeleteTask;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DatasetPackageCleanupPlannerService {

    private static final String PRIMARY = "PRIMARY";
    private static final String READY = "READY";
    private static final String DRAFT = "DRAFT";

    private final DatasetPackageRepository packageRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final ImportJobRepository importJobRepo;
    private final TrainingExperimentVersionRepository trainingRepo;
    private final MinioDeleteTaskService deleteTaskService;

    public DatasetPackageCleanupPlannerService(
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            ImportJobRepository importJobRepo,
            TrainingExperimentVersionRepository trainingRepo,
            MinioDeleteTaskService deleteTaskService
    ) {
        this.packageRepo = packageRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.importJobRepo = importJobRepo;
        this.trainingRepo = trainingRepo;
        this.deleteTaskService = deleteTaskService;
    }

    @Transactional(readOnly = true)
    public DatasetPackageCleanupPlanDto dryRun(String packageId) {
        return buildPlan(packageId, false);
    }

    @Transactional
    public DatasetPackageCleanupPlanDto enqueueIfSafe(String packageId) {
        DatasetPackageCleanupPlanDto plan = buildPlan(packageId, true);
        if (!plan.isCanDelete()) {
            return plan;
        }
        Integer ownerUserId = assetRepo
                .findByIdAndDeletedFalse(plan.getDatasetAssetId())
                .map(DatasetAsset::getOwnerUserId)
                .orElse(null);
        MinioDeleteTask task = deleteTaskService.enqueueDefaultBucketDelete(
                plan.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_PACKAGE,
                plan.getPackageId(),
                ownerUserId
        );
        plan.setEnqueued(true);
        if (task != null) {
            plan.setMinioDeleteTaskId(task.getId());
        }
        return plan;
    }

    @Transactional(readOnly = true)
    public boolean hasPackageRelations(String datasetVersionId) {
        String normalizedVersionId = requireText(
                datasetVersionId,
                "datasetVersionId cannot be empty"
        );
        return !versionPackageRepo
                .findByDatasetVersionIdOrderByPackageOrderAsc(normalizedVersionId)
                .isEmpty();
    }

    @Transactional
    public List<DatasetPackageCleanupPlanDto> enqueueVersionPackagesIfSafe(
            String datasetVersionId
    ) {
        String normalizedVersionId = requireText(
                datasetVersionId,
                "datasetVersionId cannot be empty"
        );
        List<DatasetVersionPackage> relations = versionPackageRepo
                .findByDatasetVersionIdOrderByPackageOrderAsc(normalizedVersionId);
        Set<String> packageIds = new LinkedHashSet<>();
        for (DatasetVersionPackage relation : relations) {
            if (relation.getPackageId() != null && !relation.getPackageId().isBlank()) {
                packageIds.add(relation.getPackageId());
            }
        }
        List<DatasetPackageCleanupPlanDto> plans = new ArrayList<>();
        for (String packageId : packageIds) {
            plans.add(enqueueIfSafe(packageId));
        }
        return plans;
    }

    private DatasetPackageCleanupPlanDto buildPlan(
            String packageId,
            boolean enqueueRequested
    ) {
        String normalizedPackageId = requireText(packageId, "packageId cannot be empty");
        DatasetPackage datasetPackage = packageRepo
                .findByIdAndDeletedFalse(normalizedPackageId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset package not found: " + normalizedPackageId
                ));

        DatasetPackageCleanupPlanDto plan = new DatasetPackageCleanupPlanDto();
        plan.setPackageId(datasetPackage.getId());
        plan.setDatasetAssetId(datasetPackage.getDatasetAssetId());
        plan.setStoragePath(datasetPackage.getStoragePath());
        plan.setEnqueueRequested(enqueueRequested);

        List<DatasetVersionPackage> relations =
                versionPackageRepo.findByPackageId(datasetPackage.getId());
        Set<String> versionIds = new LinkedHashSet<>();
        for (DatasetVersionPackage relation : relations) {
            if (relation.getDatasetVersionId() != null
                    && !relation.getDatasetVersionId().isBlank()) {
                versionIds.add(relation.getDatasetVersionId());
            }
        }
        plan.setReferencedVersionIds(new ArrayList<>(versionIds));

        Map<String, DatasetVersion> versionsById = loadVersions(versionIds);
        addVersionRelationBlockers(plan, relations, versionIds, versionsById);
        addMetadataBlockers(plan, datasetPackage.getId());
        if (datasetPackage.getStoragePath() == null
                || datasetPackage.getStoragePath().isBlank()) {
            addBlocker(
                    plan,
                    "PACKAGE_STORAGE_MISSING",
                    "dataset package has no storage path to enqueue",
                    "DatasetPackage",
                    datasetPackage.getId()
            );
        }

        plan.setCanDelete(plan.getBlockers().isEmpty());
        return plan;
    }

    private Map<String, DatasetVersion> loadVersions(Set<String> versionIds) {
        Map<String, DatasetVersion> versionsById = new LinkedHashMap<>();
        if (versionIds.isEmpty()) {
            return versionsById;
        }
        for (DatasetVersion version : versionRepo.findAllById(versionIds)) {
            versionsById.put(version.getId(), version);
        }
        return versionsById;
    }

    private void addVersionRelationBlockers(
            DatasetPackageCleanupPlanDto plan,
            List<DatasetVersionPackage> relations,
            Set<String> relationVersionIds,
            Map<String, DatasetVersion> versionsById
    ) {
        for (DatasetVersionPackage relation : relations) {
            String versionId = relation.getDatasetVersionId();
            DatasetVersion version = versionsById.get(versionId);
            if (version == null) {
                addBlocker(
                        plan,
                        "VERSION_RELATION_MISSING_VERSION",
                        "dataset_version_package references a missing DatasetVersion",
                        "DatasetVersion",
                        versionId
                );
                continue;
            }
            if (!Boolean.TRUE.equals(version.getDeleted())) {
                addBlocker(
                        plan,
                        "ACTIVE_VERSION_REFERENCE",
                        "package is still referenced by an active DatasetVersion: "
                                + version.getId() + ", status=" + version.getStatus(),
                        "DatasetVersion",
                        version.getId()
                );
            }
            if (READY.equals(version.getStatus())
                    && assetRepo.countByCurrentVersionIdAndDeletedFalse(
                            version.getId()
                    ) > 0) {
                addBlocker(
                        plan,
                        "CURRENT_READY_VERSION",
                        "package belongs to a DatasetAsset current READY version",
                        "DatasetVersion",
                        version.getId()
                );
            }
            if (PRIMARY.equals(relation.getPackageRole())
                    && READY.equals(version.getStatus())) {
                addDraftSharedPrimaryBlockers(plan, relationVersionIds, version);
            }
        }

        if (!relationVersionIds.isEmpty()
                && trainingRepo.countByDatasetVersionIdIn(
                        new ArrayList<>(relationVersionIds)
                ) > 0) {
            addBlocker(
                    plan,
                    "TRAINING_VERSION_REFERENCE",
                    "package is linked to DatasetVersion records referenced by training experiments",
                    "DatasetVersion",
                    String.join(",", relationVersionIds)
            );
        }
    }

    private void addDraftSharedPrimaryBlockers(
            DatasetPackageCleanupPlanDto plan,
            Set<String> relationVersionIds,
            DatasetVersion readyVersion
    ) {
        for (DatasetVersion child :
                versionRepo.findByParentVersionIdAndDeletedFalse(readyVersion.getId())) {
            if (DRAFT.equals(child.getStatus())
                    && relationVersionIds.contains(child.getId())) {
                addBlocker(
                        plan,
                        "DRAFT_SHARES_PARENT_PRIMARY_PACKAGE",
                        "parent READY and child DRAFT share the same PRIMARY package",
                        "DatasetVersion",
                        child.getId()
                );
            }
        }
    }

    private void addMetadataBlockers(
            DatasetPackageCleanupPlanDto plan,
            String packageId
    ) {
        addCountBlocker(
                plan,
                sampleRepo.countActiveByCreatedByPackageId(packageId),
                "ACTIVE_SAMPLE_REFERENCE",
                "active DatasetSample.createdByPackageId references package",
                "DatasetSample",
                packageId
        );
        addCountBlocker(
                plan,
                dataRepo.countActiveByPackageId(packageId),
                "ACTIVE_SAMPLE_DATA_REFERENCE",
                "active DatasetSampleData.packageId references package",
                "DatasetSampleData",
                packageId
        );
        addCountBlocker(
                plan,
                annotationRepo.countActiveByPackageId(packageId),
                "ACTIVE_ANNOTATION_REFERENCE",
                "active DatasetAnnotation.packageId references package",
                "DatasetAnnotation",
                packageId
        );
        addCountBlocker(
                plan,
                importJobRepo.countActiveByPackageId(packageId),
                "ACTIVE_IMPORT_JOB_REFERENCE",
                "active ImportJob.packageId references package",
                "ImportJob",
                packageId
        );
    }

    private void addCountBlocker(
            DatasetPackageCleanupPlanDto plan,
            long count,
            String code,
            String message,
            String referenceType,
            String referenceId
    ) {
        if (count <= 0) {
            return;
        }
        addBlocker(
                plan,
                code,
                message + ": " + count,
                referenceType,
                referenceId
        );
    }

    private void addBlocker(
            DatasetPackageCleanupPlanDto plan,
            String code,
            String message,
            String referenceType,
            String referenceId
    ) {
        DatasetPackageCleanupBlockerDto blocker =
                new DatasetPackageCleanupBlockerDto();
        blocker.setCode(code);
        blocker.setMessage(message);
        blocker.setReferenceType(referenceType);
        blocker.setReferenceId(referenceId);
        plan.getBlockers().add(blocker);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
