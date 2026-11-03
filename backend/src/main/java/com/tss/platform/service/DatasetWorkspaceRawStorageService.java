package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class DatasetWorkspaceRawStorageService {

    private final MinioService minioService;
    private final MinioDeleteTaskService deleteTaskService;
    private final DatasetPackageRepository packageRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;

    public DatasetWorkspaceRawStorageService(
            MinioService minioService,
            MinioDeleteTaskService deleteTaskService,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo
    ) {
        this.minioService = minioService;
        this.deleteTaskService = deleteTaskService;
        this.packageRepo = packageRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
    }

    @Transactional
    public DatasetPackage storeText(
            DatasetAsset asset,
            DatasetVersion workspace,
            DatasetWorkspaceTextFilePolicy.ValidatedText text
    ) {
        String objectName = objectName(
                asset.getOwnerUserId(),
                asset.getId(),
                workspace.getId(),
                text.fileName()
        );
        try {
            minioService.uploadStream(
                    objectName,
                    new ByteArrayInputStream(text.bytes()),
                    text.bytes().length,
                    text.contentType()
            );
        } catch (Exception exception) {
            throw new V2BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATASET_STORAGE_UNAVAILABLE",
                    "数据集对象存储暂时不可用",
                    Map.of("workspaceId", workspace.getId())
            );
        }
        registerRollbackCleanup(objectName, asset.getOwnerUserId());
        return attachRawObject(
                asset,
                workspace,
                objectName,
                text.fileName(),
                (long) text.bytes().length,
                text.sha256()
        );
    }

    @Transactional
    public DatasetPackage attachRawObject(
            DatasetAsset asset,
            DatasetVersion workspace,
            String objectName,
            String fileName,
            Long sizeBytes,
            String checksum
    ) {
        Instant now = Instant.now();
        DatasetPackage datasetPackage = new DatasetPackage();
        datasetPackage.setId("dspkg-" + compactUuid());
        datasetPackage.setDatasetAssetId(asset.getId());
        datasetPackage.setStoragePath(objectName);
        datasetPackage.setFileName(fileName);
        datasetPackage.setSizeBytes(sizeBytes);
        datasetPackage.setChecksum(checksum);
        datasetPackage.setStatus("READY");
        datasetPackage.setStorageKind("RAW");
        datasetPackage.setCreatedAt(now);
        datasetPackage.setDeleted(false);
        packageRepo.save(datasetPackage);

        DatasetVersionPackage relation = new DatasetVersionPackage();
        relation.setDatasetVersionId(workspace.getId());
        relation.setPackageId(datasetPackage.getId());
        relation.setPackageRole("OVERLAY");
        Integer maxOrder = versionPackageRepo.findMaxPackageOrderByDatasetVersionId(
                workspace.getId()
        );
        relation.setPackageOrder((maxOrder == null ? -1 : maxOrder) + 1);
        relation.setCreatedAt(now);
        versionPackageRepo.save(relation);
        return datasetPackage;
    }

    /**
     * Releases a workspace-owned overlay only after every resource row has
     * stopped referencing it. Deleted rows count because they are restorable.
     */
    @Transactional
    public void releaseIfUnreferenced(
            DatasetVersion workspace,
            String packageId,
            Integer ownerUserId
    ) {
        if (packageId == null || packageId.isBlank()) {
            return;
        }
        DatasetVersionPackage relation = versionPackageRepo
                .findByDatasetVersionIdAndPackageId(workspace.getId(), packageId)
                .orElse(null);
        if (relation == null || !"OVERLAY".equals(relation.getPackageRole())) {
            return;
        }
        if (dataRepo.countByDatasetVersionIdAndPackageId(
                workspace.getId(),
                packageId
        ) > 0
                || annotationRepo.countByDatasetVersionIdAndPackageId(
                workspace.getId(),
                packageId
        ) > 0
                || versionPackageRepo.findByPackageId(packageId).size() != 1) {
            return;
        }
        DatasetPackage datasetPackage = packageRepo.findById(packageId).orElse(null);
        if (datasetPackage == null) {
            return;
        }
        Integer removedOrder = relation.getPackageOrder();
        versionPackageRepo.delete(relation);
        versionPackageRepo.flush();
        versionPackageRepo.compactPackageOrder(
                workspace.getId(),
                removedOrder
        );
        datasetPackage.setDeleted(true);
        datasetPackage.setDeletedAt(Instant.now());
        datasetPackage.setStatus("SUPERSEDED");
        packageRepo.save(datasetPackage);
        deleteTaskService.enqueueDefaultBucketDelete(
                datasetPackage.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_PACKAGE,
                datasetPackage.getId(),
                ownerUserId
        );
    }

    private String objectName(
            Integer ownerUserId,
            String assetId,
            String workspaceId,
            String fileName
    ) {
        String owner = ownerUserId == null ? "system" : ownerUserId.toString();
        return "users/" + owner
                + "/datasets/" + assetId
                + "/workspaces/" + workspaceId
                + "/overlays/" + compactUuid()
                + "/" + fileName;
    }

    private void registerRollbackCleanup(String objectName, Integer ownerUserId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            return;
                        }
                        try {
                            deleteTaskService.enqueueDefaultBucketDeleteImmediately(
                                    objectName,
                                    MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_ROLLBACK,
                                    objectName,
                                    ownerUserId
                            );
                        } catch (Exception ignored) {
                            // The original transaction failure remains authoritative.
                        }
                    }
                }
        );
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
