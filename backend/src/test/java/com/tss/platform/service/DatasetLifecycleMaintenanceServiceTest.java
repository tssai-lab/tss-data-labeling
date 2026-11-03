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
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetLifecycleMaintenanceServiceTest {

    @Test
    void softDeletesOldFailedDraftAndEmptyCreatedAsset() {
        Fixture fixture = new Fixture();
        ImportJob job = fixture.failedJob();
        DatasetVersion version = fixture.version();
        DatasetAsset asset = fixture.asset();
        DatasetUploadSession session = fixture.session(job, version);
        when(fixture.jobRepo.findByStatusAndFinishedAtBefore(eq("FAILED"), any()))
                .thenReturn(List.of(job));
        when(fixture.jobRepo.findById(job.getId())).thenReturn(Optional.of(job));
        when(fixture.versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
        when(fixture.assetRepo.findById(version.getAssetId())).thenReturn(Optional.of(asset));
        when(fixture.sessionRepo.findByImportJobId(job.getId())).thenReturn(Optional.of(session));
        when(fixture.versionRepo.countByAssetIdAndDeletedFalse(asset.getId())).thenReturn(0L);
        when(fixture.versionRepo.existsByStoragePathAndDeletedFalseAndIdNot(
                version.getStoragePath(),
                version.getId()
        )).thenReturn(false);

        fixture.service.cleanupFailedDrafts();

        assertTrue(version.getDeleted());
        assertTrue(asset.getDeleted());
        verify(fixture.deleteTaskService).enqueueDefaultBucketDelete(
                version.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_VERSION,
                version.getId(),
                version.getOwnerUserId()
        );
    }

    @Test
    void doesNotDeleteStorageSharedWithActiveReadyVersion() {
        Fixture fixture = new Fixture();
        ImportJob job = fixture.failedJob();
        DatasetVersion version = fixture.version();
        DatasetAsset asset = fixture.asset();
        when(fixture.jobRepo.findByStatusAndFinishedAtBefore(eq("FAILED"), any()))
                .thenReturn(List.of(job));
        when(fixture.jobRepo.findById(job.getId())).thenReturn(Optional.of(job));
        when(fixture.versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
        when(fixture.assetRepo.findById(version.getAssetId())).thenReturn(Optional.of(asset));
        when(fixture.versionRepo.existsByStoragePathAndDeletedFalseAndIdNot(
                version.getStoragePath(),
                version.getId()
        )).thenReturn(true);

        fixture.service.cleanupFailedDrafts();

        assertTrue(version.getDeleted());
        verify(fixture.deleteTaskService, never()).enqueueDefaultBucketDelete(
                version.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_VERSION,
                version.getId(),
                version.getOwnerUserId()
        );
    }

    @Test
    void failedDraftCleanupUsesPackagePlannerWhenImportJobHasPackage() {
        Fixture fixture = new Fixture();
        ImportJob job = fixture.failedJob();
        job.setPackageId("package-1");
        DatasetVersion version = fixture.version();
        DatasetAsset asset = fixture.asset();
        when(fixture.jobRepo.findByStatusAndFinishedAtBefore(eq("FAILED"), any()))
                .thenReturn(List.of(job));
        when(fixture.jobRepo.findById(job.getId())).thenReturn(Optional.of(job));
        when(fixture.versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
        when(fixture.assetRepo.findById(version.getAssetId())).thenReturn(Optional.of(asset));

        fixture.service.cleanupFailedDrafts();

        assertTrue(version.getDeleted());
        verify(fixture.cleanupPlanner).enqueueIfSafe(job.getPackageId());
        verify(fixture.deleteTaskService, never()).enqueueDefaultBucketDelete(
                version.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_VERSION,
                version.getId(),
                version.getOwnerUserId()
        );
    }

    @Test
    void physicallyDeletesOldSoftDeletedUnreferencedVersion() {
        Fixture fixture = new Fixture();
        DatasetVersion version = fixture.version();
        version.setDeleted(true);
        version.setDeletedAt(Instant.now().minusSeconds(31L * 24 * 3600));
        DatasetAsset asset = fixture.asset();
        when(fixture.versionRepo.findByDeletedTrueAndDeletedAtBefore(any())).thenReturn(List.of(version));
        when(fixture.versionRepo.findById(version.getId())).thenReturn(Optional.of(version));
        when(fixture.assetRepo.findById(version.getAssetId())).thenReturn(Optional.of(asset));
        when(fixture.versionRepo.countByParentVersionId(version.getId())).thenReturn(0L);
        when(fixture.trainingRepo.countByDatasetVersionId(version.getId())).thenReturn(0L);

        fixture.service.purgeSoftDeletedVersions();

        verify(fixture.versionRepo).delete(version);
        verify(fixture.versionRepo).flush();
    }

    @Test
    void doesNotPhysicallyDeleteCurrentOrReferencedVersion() {
        Fixture fixture = new Fixture();
        DatasetVersion version = fixture.version();
        version.setDeleted(true);
        version.setDeletedAt(Instant.now().minusSeconds(31L * 24 * 3600));
        DatasetAsset asset = fixture.asset();
        asset.setCurrentVersionId(version.getId());
        when(fixture.versionRepo.findByDeletedTrueAndDeletedAtBefore(any())).thenReturn(List.of(version));
        when(fixture.versionRepo.findById(version.getId())).thenReturn(Optional.of(version));
        when(fixture.assetRepo.findById(version.getAssetId())).thenReturn(Optional.of(asset));

        fixture.service.purgeSoftDeletedVersions();

        verify(fixture.versionRepo, never()).delete(any());
    }

    private static final class Fixture {
        private final ImportJobRepository jobRepo = mock(ImportJobRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        private final TrainingExperimentVersionRepository trainingRepo =
                mock(TrainingExperimentVersionRepository.class);
        private final DatasetPackageCleanupPlannerService cleanupPlanner =
                mock(DatasetPackageCleanupPlannerService.class);
        private final MinioDeleteTaskService deleteTaskService = mock(MinioDeleteTaskService.class);
        private final DatasetLifecycleMaintenanceService service = new DatasetLifecycleMaintenanceService(
                jobRepo,
                versionRepo,
                assetRepo,
                sessionRepo,
                trainingRepo,
                cleanupPlanner,
                deleteTaskService,
                new NoOpTransactionManager()
        );

        private ImportJob failedJob() {
            ImportJob job = new ImportJob();
            job.setId("ijob-1");
            job.setDatasetVersionId("version-1");
            job.setStatus("FAILED");
            job.setFinishedAt(Instant.now().minusSeconds(8L * 24 * 3600));
            return job;
        }

        private DatasetVersion version() {
            DatasetVersion version = new DatasetVersion();
            version.setId("version-1");
            version.setAssetId("asset-1");
            version.setVersion("v1");
            version.setStatus("DRAFT");
            version.setStoragePath("users/7/datasets/asset-1/v1/dataset.zip");
            version.setOwnerUserId(7);
            version.setDeleted(false);
            return version;
        }

        private DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setName("asset");
            asset.setDeleted(false);
            return asset;
        }

        private DatasetUploadSession session(ImportJob job, DatasetVersion version) {
            DatasetUploadSession session = new DatasetUploadSession();
            session.setId("upload-1");
            session.setImportJobId(job.getId());
            session.setVersionId(version.getId());
            session.setAssetId(version.getAssetId());
            session.setAssetCreatedByUpload(true);
            return session;
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
