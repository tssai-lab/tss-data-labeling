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
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetUploadRecoveryServiceTest {

    @Test
    void completesStaleSessionWhenReservedObjectExists() throws Exception {
        assertCompletesStaleSession("MANIFEST", "manifest.json");
    }

    @Test
    void completesAutoDirectoryStaleSessionWithoutManifestPath() throws Exception {
        assertCompletesStaleSession("AUTO_DIRECTORY", null);
    }

    private void assertCompletesStaleSession(
            String sampleGrouping,
            String manifestPath
    ) throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.setSampleGrouping(sampleGrouping);
        fixture.session.setManifestPath(manifestPath);
        fixture.stubLockedState();
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(fixture.session.getFileSize());
        when(fixture.minioService.stat(fixture.objectName())).thenReturn(stat);
        when(fixture.jobRepo.findByDatasetVersionIdAndPackageId(any(), any()))
                .thenReturn(Optional.empty());
        when(fixture.packageRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetPackage value = invocation.getArgument(0);
            fixture.savedPackage.set(value);
            return value;
        });
        when(fixture.versionPackageRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetVersionPackage value = invocation.getArgument(0);
            fixture.savedVersionPackage.set(value);
            return value;
        });

        fixture.service.recover(fixture.session.getId());

        assertEquals("COMPLETED", fixture.session.getStatus());
        assertEquals(fixture.objectName(), fixture.session.getStoragePath());
        assertEquals(fixture.objectName(), fixture.version.getStoragePath());
        assertNotNull(fixture.session.getImportJobId());
        assertEquals("READY", fixture.savedPackage.get().getStatus());
        assertEquals(fixture.objectName(), fixture.savedPackage.get().getStoragePath());
        assertEquals(fixture.asset.getId(), fixture.savedPackage.get().getDatasetAssetId());
        assertEquals(manifestPath, fixture.savedPackage.get().getManifestPath());
        assertEquals(fixture.version.getId(), fixture.savedVersionPackage.get().getDatasetVersionId());
        assertEquals(fixture.savedPackage.get().getId(), fixture.savedVersionPackage.get().getPackageId());
        assertEquals("PRIMARY", fixture.savedVersionPackage.get().getPackageRole());
        assertEquals(0, fixture.savedVersionPackage.get().getPackageOrder());
        assertEquals(
                fixture.savedPackage.get().getId(),
                fixture.savedJob.get().getPackageId()
        );
        verify(fixture.launcher).launch(fixture.session.getImportJobId());
    }

    @Test
    void recoveryIsIdempotentWhenSessionAlreadyCompleted() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.setStatus("COMPLETED");
        fixture.session.setImportJobId("ijob-existing");
        when(fixture.sessionRepo.findByIdForUpdate(fixture.session.getId()))
                .thenReturn(Optional.of(fixture.session));

        fixture.service.recover(fixture.session.getId());

        verify(fixture.jobRepo, never()).saveAndFlush(any());
        verify(fixture.launcher, never()).launch(any());
    }

    @Test
    void rollsBackDraftAndCreatedAssetWhenObjectStatFails() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubLockedState();
        when(fixture.minioService.stat(fixture.objectName())).thenThrow(new RuntimeException("not found"));
        when(fixture.versionRepo.countByAssetIdAndDeletedFalse(fixture.asset.getId())).thenReturn(0L);

        fixture.service.recover(fixture.session.getId());

        assertEquals("UPLOADING", fixture.session.getStatus());
        assertNull(fixture.session.getVersionId());
        assertNull(fixture.session.getAssetId());
        verify(fixture.versionRepo).delete(fixture.version);
        verify(fixture.assetRepo).delete(fixture.asset);
    }

    @Test
    void recoversAppendPackageSessionAndLaunchesImportWhenObjectExists() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.setUploadPurpose("APPEND_PACKAGE");
        fixture.session.setAssetCreatedByUpload(false);
        fixture.stubLockedState();
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(fixture.session.getFileSize());
        when(fixture.minioService.stat(fixture.appendObjectName())).thenReturn(stat);
        when(fixture.versionPackageRepo.findMaxPackageOrderByDatasetVersionId(fixture.version.getId()))
                .thenReturn(2);
        when(fixture.packageRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetPackage value = invocation.getArgument(0);
            fixture.savedPackage.set(value);
            return value;
        });
        when(fixture.versionPackageRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetVersionPackage value = invocation.getArgument(0);
            fixture.savedVersionPackage.set(value);
            return value;
        });

        fixture.service.recover(fixture.session.getId());

        assertEquals("COMPLETED", fixture.session.getStatus());
        assertEquals(fixture.appendObjectName(), fixture.session.getStoragePath());
        assertNotNull(fixture.session.getImportJobId());
        assertEquals("PENDING", fixture.savedPackage.get().getStatus());
        assertEquals(fixture.appendObjectName(), fixture.savedPackage.get().getStoragePath());
        assertEquals(fixture.asset.getId(), fixture.savedPackage.get().getDatasetAssetId());
        assertEquals(fixture.version.getId(), fixture.savedVersionPackage.get().getDatasetVersionId());
        assertEquals(fixture.savedPackage.get().getId(), fixture.savedVersionPackage.get().getPackageId());
        assertEquals("APPEND", fixture.savedVersionPackage.get().getPackageRole());
        assertEquals(3, fixture.savedVersionPackage.get().getPackageOrder());
        assertEquals(fixture.savedPackage.get().getId(), fixture.savedJob.get().getPackageId());
        verify(fixture.launcher).launch(fixture.session.getImportJobId());
    }

    @Test
    void rollsBackAppendPackageSessionWithoutDeletingDraftWhenObjectStatFails() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.setUploadPurpose("APPEND_PACKAGE");
        fixture.session.setAssetCreatedByUpload(false);
        fixture.stubLockedState();
        when(fixture.minioService.stat(fixture.appendObjectName()))
                .thenThrow(new RuntimeException("not found"));

        fixture.service.recover(fixture.session.getId());

        assertEquals("UPLOADING", fixture.session.getStatus());
        assertEquals(fixture.version.getId(), fixture.session.getVersionId());
        assertEquals(fixture.asset.getId(), fixture.session.getAssetId());
        assertNull(fixture.session.getStoragePath());
        assertNull(fixture.session.getImportJobId());
        verify(fixture.versionRepo, never()).delete(any());
        verify(fixture.assetRepo, never()).delete(any());
    }

    private static final class Fixture {
        private final DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final DatasetPackageRepository packageRepo = mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final ImportJobRepository jobRepo = mock(ImportJobRepository.class);
        private final MinioService minioService = mock(MinioService.class);
        private final MinioDeleteTaskService deleteTaskService = mock(MinioDeleteTaskService.class);
        private final ImportJobLauncher launcher = mock(ImportJobLauncher.class);
        private final PlatformTransactionManager transactionManager = new NoOpTransactionManager();
        private final DatasetUploadSession session = session();
        private final DatasetVersion version = version();
        private final DatasetAsset asset = asset();
        private final AtomicReference<DatasetPackage> savedPackage = new AtomicReference<>();
        private final AtomicReference<DatasetVersionPackage> savedVersionPackage = new AtomicReference<>();
        private final AtomicReference<ImportJob> savedJob = new AtomicReference<>();
        private final DatasetUploadRecoveryService service = new DatasetUploadRecoveryService(
                sessionRepo,
                versionRepo,
                assetRepo,
                packageRepo,
                versionPackageRepo,
                jobRepo,
                minioService,
                deleteTaskService,
                launcher,
                transactionManager
        );

        private void stubLockedState() {
            when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
            when(sessionRepo.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
            when(sessionRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
            when(versionRepo.findByIdAndDeletedFalseForUpdate(version.getId())).thenReturn(Optional.of(version));
            when(versionRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(assetRepo.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(jobRepo.saveAndFlush(any())).thenAnswer(invocation -> {
                ImportJob value = invocation.getArgument(0);
                savedJob.set(value);
                return value;
            });
        }

        private String objectName() {
            return "users/7/datasets/asset-1/v2/dataset.zip";
        }

        private String appendObjectName() {
            return "users/7/datasets/asset-1/v2/packages/upload-1/dataset.zip";
        }

        private DatasetUploadSession session() {
            DatasetUploadSession value = new DatasetUploadSession();
            value.setId("upload-1");
            value.setOwnerUserId(7);
            value.setAssetId("asset-1");
            value.setVersionId("version-2");
            value.setVersionNo(2);
            value.setFileName("dataset.zip");
            value.setFileSize(4096L);
            value.setSampleGrouping("MANIFEST");
            value.setManifestPath("manifest.json");
            value.setAssetCreatedByUpload(true);
            value.setStatus("COMPLETING");
            value.setUpdatedAt(Instant.now().minusSeconds(3600));
            return value;
        }

        private DatasetVersion version() {
            DatasetVersion value = new DatasetVersion();
            value.setId("version-2");
            value.setAssetId("asset-1");
            value.setVersion("v2");
            value.setVersionNo(2);
            value.setStatus("DRAFT");
            value.setDeleted(false);
            return value;
        }

        private DatasetAsset asset() {
            DatasetAsset value = new DatasetAsset();
            value.setId("asset-1");
            value.setName("multimodal");
            value.setOwnerUserId(7);
            value.setCurrentVersionId(null);
            value.setDeleted(false);
            return value;
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
