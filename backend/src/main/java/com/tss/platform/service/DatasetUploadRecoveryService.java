package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.ImportJobRepository;
import io.minio.StatObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class DatasetUploadRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(DatasetUploadRecoveryService.class);
    private static final String STATUS_UPLOADING = "UPLOADING";
    private static final String STATUS_COMPLETING = "COMPLETING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String VERSION_STATUS_DRAFT = "DRAFT";
    private static final String UPLOAD_PURPOSE_APPEND = "APPEND_PACKAGE";
    private static final String PACKAGE_ROLE_PRIMARY = "PRIMARY";
    private static final String PACKAGE_ROLE_APPEND = "APPEND";
    private static final String PACKAGE_STATUS_READY = "READY";
    private static final String IMPORT_STATUS_PENDING = "PENDING";
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private final DatasetUploadSessionRepository sessionRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetPackageRepository packageRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final ImportJobRepository jobRepo;
    private final MinioService minioService;
    private final MinioDeleteTaskService deleteTaskService;
    private final ImportJobLauncher launcher;
    private final TransactionTemplate transactionTemplate;

    public DatasetUploadRecoveryService(
            DatasetUploadSessionRepository sessionRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            ImportJobRepository jobRepo,
            MinioService minioService,
            MinioDeleteTaskService deleteTaskService,
            ImportJobLauncher launcher,
            PlatformTransactionManager transactionManager
    ) {
        this.sessionRepo = sessionRepo;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.packageRepo = packageRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.jobRepo = jobRepo;
        this.minioService = minioService;
        this.deleteTaskService = deleteTaskService;
        this.launcher = launcher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void recoverStaleSessions() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        for (DatasetUploadSession session :
                sessionRepo.findByStatusAndUpdatedAtBefore(STATUS_COMPLETING, cutoff)) {
            try {
                recover(session.getId());
            } catch (RuntimeException exception) {
                log.warn(
                        "Unable to recover dataset upload session {}: {}",
                        session.getId(),
                        exception.getMessage()
                );
            }
        }
    }

    public void recover(String uploadId) {
        RecoverySnapshot snapshot = transactionTemplate.execute(status ->
                sessionRepo.findByIdForUpdate(uploadId)
                        .filter(session -> STATUS_COMPLETING.equals(session.getStatus()))
                        .map(this::snapshot)
                        .orElse(null)
        );
        if (snapshot == null) {
            return;
        }

        boolean objectExists = false;
        boolean sizeMatches = false;
        long objectSize = 0L;
        try {
            StatObjectResponse stat = minioService.stat(snapshot.objectName());
            objectExists = true;
            objectSize = stat.size();
            sizeMatches = objectSize == snapshot.expectedSize();
        } catch (Exception ignored) {
            // A missing or unreadable reserved object is rolled back to UPLOADING.
        }

        if (!sizeMatches) {
            rollbackReservation(snapshot);
            if (objectExists) {
                deleteTaskService.enqueueDefaultBucketDeleteImmediately(
                        snapshot.objectName(),
                        MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_ROLLBACK,
                        snapshot.uploadId(),
                        snapshot.ownerUserId()
                );
            }
            return;
        }

        long recoveredObjectSize = objectSize;
        String importJobId = transactionTemplate.execute(status ->
                finalizeRecoveredUpload(snapshot, recoveredObjectSize)
        );
        if (importJobId != null) {
            launcher.launch(importJobId);
        }
    }

    private RecoverySnapshot snapshot(DatasetUploadSession session) {
        boolean appendPackage = UPLOAD_PURPOSE_APPEND.equals(session.getUploadPurpose());
        return new RecoverySnapshot(
                session.getId(),
                session.getVersionId(),
                session.getAssetId(),
                Boolean.TRUE.equals(session.getAssetCreatedByUpload()),
                session.getOwnerUserId(),
                session.getFileSize(),
                appendPackage
                        ? DatasetUploadService.appendPackageDestinationObject(session)
                        : DatasetUploadService.manifestDestinationObject(session),
                appendPackage
        );
    }

    private String finalizeRecoveredUpload(RecoverySnapshot snapshot, long objectSize) {
        if (snapshot.appendPackage()) {
            return finalizeRecoveredAppendPackage(snapshot, objectSize);
        }
        return finalizeRecoveredInitialUpload(snapshot, objectSize);
    }

    private String finalizeRecoveredInitialUpload(RecoverySnapshot snapshot, long objectSize) {
        DatasetUploadSession session = sessionRepo.findByIdForUpdate(snapshot.uploadId())
                .orElse(null);
        if (session == null || !STATUS_COMPLETING.equals(session.getStatus())) {
            return null;
        }
        if (!snapshot.versionId().equals(session.getVersionId())
                || !snapshot.assetId().equals(session.getAssetId())) {
            return null;
        }

        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(snapshot.versionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset version not found: " + snapshot.versionId()
                ));
        if (!VERSION_STATUS_DRAFT.equals(version.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset version must be DRAFT: " + version.getId()
            );
        }

        Instant now = Instant.now();
        version.setFileName(session.getFileName());
        version.setStoragePath(snapshot.objectName());
        version.setSizeBytes(objectSize);
        versionRepo.saveAndFlush(version);

        DatasetPackage datasetPackage = new DatasetPackage();
        datasetPackage.setId("dataset-pkg-" + UUID.randomUUID().toString().replace("-", ""));
        datasetPackage.setDatasetAssetId(version.getAssetId());
        datasetPackage.setStoragePath(snapshot.objectName());
        datasetPackage.setFileName(session.getFileName());
        datasetPackage.setSizeBytes(objectSize);
        datasetPackage.setManifestPath(session.getManifestPath());
        datasetPackage.setStatus(PACKAGE_STATUS_READY);
        datasetPackage.setCreatedAt(now);
        datasetPackage.setDeleted(false);
        datasetPackage = packageRepo.saveAndFlush(datasetPackage);

        DatasetVersionPackage versionPackage = new DatasetVersionPackage();
        versionPackage.setDatasetVersionId(version.getId());
        versionPackage.setPackageId(datasetPackage.getId());
        versionPackage.setPackageRole(PACKAGE_ROLE_PRIMARY);
        versionPackage.setPackageOrder(0);
        versionPackage.setCreatedAt(now);
        versionPackageRepo.saveAndFlush(versionPackage);

        DatasetPackage primaryPackage = datasetPackage;
        ImportJob job = jobRepo
                .findByDatasetVersionIdAndPackageId(version.getId(), primaryPackage.getId())
                .orElseGet(() -> {
                    ImportJob value = new ImportJob();
                    value.setId("ijob-" + UUID.randomUUID().toString().replace("-", ""));
                    value.setDatasetVersionId(version.getId());
                    value.setPackageId(primaryPackage.getId());
                    value.setStatus(IMPORT_STATUS_PENDING);
                    value.setProgress(0);
                    value.setImportedSamples(0);
                    value.setOwnerUserId(session.getOwnerUserId());
                    value.setCreatedAt(now);
                    value.setUpdatedAt(now);
                    return jobRepo.saveAndFlush(value);
                });

        session.setStoragePath(snapshot.objectName());
        session.setImportJobId(job.getId());
        session.setStatus(STATUS_COMPLETED);
        session.setUpdatedAt(now);
        sessionRepo.saveAndFlush(session);
        return IMPORT_STATUS_PENDING.equals(job.getStatus()) ? job.getId() : null;
    }

    private String finalizeRecoveredAppendPackage(RecoverySnapshot snapshot, long objectSize) {
        DatasetUploadSession session = sessionRepo.findByIdForUpdate(snapshot.uploadId())
                .orElse(null);
        if (session == null || !STATUS_COMPLETING.equals(session.getStatus())) {
            return null;
        }
        if (!snapshot.versionId().equals(session.getVersionId())
                || !snapshot.assetId().equals(session.getAssetId())) {
            return null;
        }

        DatasetVersion draft = versionRepo.findByIdAndDeletedFalseForUpdate(snapshot.versionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset workspace version not found: " + snapshot.versionId()
                ));
        if (!snapshot.assetId().equals(draft.getAssetId())) {
            return null;
        }
        if (!VERSION_STATUS_DRAFT.equals(draft.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset version must be DRAFT: " + draft.getId()
            );
        }
        DatasetAsset asset = assetRepo.findById(draft.getAssetId())
                .filter(value -> !Boolean.TRUE.equals(value.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset asset not found: " + draft.getAssetId()
                ));

        Instant now = Instant.now();
        DatasetPackage datasetPackage = new DatasetPackage();
        datasetPackage.setId("dataset-pkg-" + UUID.randomUUID().toString().replace("-", ""));
        datasetPackage.setDatasetAssetId(asset.getId());
        datasetPackage.setStoragePath(snapshot.objectName());
        datasetPackage.setFileName(session.getFileName());
        datasetPackage.setSizeBytes(objectSize);
        datasetPackage.setManifestPath(session.getManifestPath());
        datasetPackage.setStatus(IMPORT_STATUS_PENDING);
        datasetPackage.setCreatedAt(now);
        datasetPackage.setDeleted(false);
        datasetPackage = packageRepo.saveAndFlush(datasetPackage);

        Integer maxOrder = versionPackageRepo
                .findMaxPackageOrderByDatasetVersionId(draft.getId());
        DatasetVersionPackage relation = new DatasetVersionPackage();
        relation.setDatasetVersionId(draft.getId());
        relation.setPackageId(datasetPackage.getId());
        relation.setPackageRole(PACKAGE_ROLE_APPEND);
        relation.setPackageOrder((maxOrder == null ? -1 : maxOrder) + 1);
        relation.setCreatedAt(now);
        versionPackageRepo.saveAndFlush(relation);

        ImportJob job = new ImportJob();
        job.setId("ijob-" + UUID.randomUUID().toString().replace("-", ""));
        job.setDatasetVersionId(draft.getId());
        job.setPackageId(datasetPackage.getId());
        job.setStatus(IMPORT_STATUS_PENDING);
        job.setProgress(0);
        job.setImportedSamples(0);
        job.setOwnerUserId(asset.getOwnerUserId());
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job = jobRepo.saveAndFlush(job);

        session.setStoragePath(snapshot.objectName());
        session.setImportJobId(job.getId());
        session.setStatus(STATUS_COMPLETED);
        session.setUpdatedAt(now);
        sessionRepo.saveAndFlush(session);
        return job.getId();
    }

    private void rollbackReservation(RecoverySnapshot snapshot) {
        if (snapshot.appendPackage()) {
            rollbackAppendReservation(snapshot);
            return;
        }
        rollbackInitialReservation(snapshot);
    }

    private void rollbackInitialReservation(RecoverySnapshot snapshot) {
        transactionTemplate.executeWithoutResult(status -> {
            DatasetUploadSession session = sessionRepo.findByIdForUpdate(snapshot.uploadId())
                    .orElse(null);
            if (session == null || !STATUS_COMPLETING.equals(session.getStatus())) {
                return;
            }
            if (!snapshot.versionId().equals(session.getVersionId())
                    || !snapshot.assetId().equals(session.getAssetId())) {
                return;
            }

            DatasetVersion version = versionRepo.findByIdAndDeletedFalse(snapshot.versionId())
                    .orElse(null);
            session.setStatus(STATUS_UPLOADING);
            session.setStoragePath(null);
            session.setVersionId(null);
            session.setVersionNo(null);
            session.setImportJobId(null);
            if (snapshot.assetCreatedByUpload()) {
                session.setAssetId(null);
            }
            session.setAssetCreatedByUpload(false);
            session.setUpdatedAt(Instant.now());
            sessionRepo.saveAndFlush(session);

            if (version != null) {
                versionRepo.delete(version);
                versionRepo.flush();
            }
            if (snapshot.assetCreatedByUpload()
                    && versionRepo.countByAssetIdAndDeletedFalse(snapshot.assetId()) == 0) {
                assetRepo.findById(snapshot.assetId())
                        .filter(asset -> asset.getCurrentVersionId() == null)
                        .ifPresent(assetRepo::delete);
            }
        });
    }

    private void rollbackAppendReservation(RecoverySnapshot snapshot) {
        transactionTemplate.executeWithoutResult(status -> {
            DatasetUploadSession session = sessionRepo.findByIdForUpdate(snapshot.uploadId())
                    .orElse(null);
            if (session == null || !STATUS_COMPLETING.equals(session.getStatus())) {
                return;
            }
            if (!snapshot.versionId().equals(session.getVersionId())
                    || !snapshot.assetId().equals(session.getAssetId())) {
                return;
            }

            session.setStatus(STATUS_UPLOADING);
            session.setStoragePath(null);
            session.setImportJobId(null);
            session.setAssetCreatedByUpload(false);
            session.setUpdatedAt(Instant.now());
            sessionRepo.saveAndFlush(session);
        });
    }

    private record RecoverySnapshot(
            String uploadId,
            String versionId,
            String assetId,
            boolean assetCreatedByUpload,
            Integer ownerUserId,
            long expectedSize,
            String objectName,
            boolean appendPackage
    ) {
    }
}
