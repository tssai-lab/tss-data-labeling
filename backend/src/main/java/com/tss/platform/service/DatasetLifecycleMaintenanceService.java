package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

@Service
public class DatasetLifecycleMaintenanceService {

    private static final Logger log =
            LoggerFactory.getLogger(DatasetLifecycleMaintenanceService.class);
    private static final Duration FAILED_DRAFT_RETENTION = Duration.ofDays(7);
    private static final Duration SOFT_DELETED_RETENTION = Duration.ofDays(30);

    private final ImportJobRepository jobRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetUploadSessionRepository sessionRepo;
    private final TrainingExperimentVersionRepository trainingVersionRepo;
    private final DatasetPackageCleanupPlannerService cleanupPlanner;
    private final MinioDeleteTaskService deleteTaskService;
    private final TransactionTemplate transactionTemplate;

    public DatasetLifecycleMaintenanceService(
            ImportJobRepository jobRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetUploadSessionRepository sessionRepo,
            TrainingExperimentVersionRepository trainingVersionRepo,
            DatasetPackageCleanupPlannerService cleanupPlanner,
            MinioDeleteTaskService deleteTaskService,
            PlatformTransactionManager transactionManager
    ) {
        this.jobRepo = jobRepo;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.sessionRepo = sessionRepo;
        this.trainingVersionRepo = trainingVersionRepo;
        this.cleanupPlanner = cleanupPlanner;
        this.deleteTaskService = deleteTaskService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void cleanupFailedDrafts() {
        Instant cutoff = Instant.now().minus(FAILED_DRAFT_RETENTION);
        for (ImportJob job : jobRepo.findByStatusAndFinishedAtBefore("FAILED", cutoff)) {
            try {
                transactionTemplate.executeWithoutResult(status -> softDeleteFailedDraft(job.getId()));
            } catch (RuntimeException exception) {
                log.warn(
                        "Unable to clean failed dataset import {}: {}",
                        job.getId(),
                        exception.getMessage()
                );
            }
        }
    }

    public void purgeSoftDeletedVersions() {
        Instant cutoff = Instant.now().minus(SOFT_DELETED_RETENTION);
        for (DatasetVersion candidate : versionRepo.findByDeletedTrueAndDeletedAtBefore(cutoff)) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                        purgeVersion(candidate.getId(), cutoff)
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "Unable to purge dataset version {}: {}",
                        candidate.getId(),
                        exception.getMessage()
                );
            }
        }
    }

    private void softDeleteFailedDraft(String importJobId) {
        ImportJob job = jobRepo.findById(importJobId).orElse(null);
        if (job == null || !"FAILED".equals(job.getStatus())) {
            return;
        }
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(job.getDatasetVersionId())
                .orElse(null);
        if (version == null || !"DRAFT".equals(version.getStatus())) {
            return;
        }
        DatasetAsset asset = assetRepo.findById(version.getAssetId()).orElse(null);
        if (asset != null && version.getId().equals(asset.getCurrentVersionId())) {
            return;
        }

        Instant now = Instant.now();
        version.setDeleted(true);
        version.setDeletedAt(now);
        versionRepo.saveAndFlush(version);
        if (job.getPackageId() != null && !job.getPackageId().isBlank()) {
            cleanupPlanner.enqueueIfSafe(job.getPackageId());
        } else if (version.getStoragePath() != null
                && !version.getStoragePath().isBlank()
                && !versionRepo.existsByStoragePathAndDeletedFalseAndIdNot(
                        version.getStoragePath(),
                        version.getId()
                )) {
            deleteTaskService.enqueueDefaultBucketDelete(
                    version.getStoragePath(),
                    MinioDeleteTaskService.SOURCE_DATASET_VERSION,
                    version.getId(),
                    version.getOwnerUserId()
            );
        }

        DatasetUploadSession session = sessionRepo.findByImportJobId(job.getId()).orElse(null);
        if (asset != null
                && session != null
                && Boolean.TRUE.equals(session.getAssetCreatedByUpload())
                && asset.getCurrentVersionId() == null
                && versionRepo.countByAssetIdAndDeletedFalse(asset.getId()) == 0) {
            asset.setDeleted(true);
            asset.setDeletedAt(now);
            asset.setUpdatedAt(now);
            assetRepo.saveAndFlush(asset);
        }
    }

    private void purgeVersion(String versionId, Instant cutoff) {
        DatasetVersion version = versionRepo.findById(versionId).orElse(null);
        if (version == null
                || !Boolean.TRUE.equals(version.getDeleted())
                || version.getDeletedAt() == null
                || !version.getDeletedAt().isBefore(cutoff)) {
            return;
        }
        DatasetAsset asset = assetRepo.findById(version.getAssetId()).orElse(null);
        if (asset != null && version.getId().equals(asset.getCurrentVersionId())) {
            return;
        }
        if (versionRepo.countByParentVersionId(version.getId()) > 0
                || trainingVersionRepo.countByDatasetVersionId(version.getId()) > 0) {
            return;
        }
        versionRepo.delete(version);
        versionRepo.flush();
    }
}
