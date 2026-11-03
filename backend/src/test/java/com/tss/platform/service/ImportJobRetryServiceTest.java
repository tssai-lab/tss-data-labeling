package com.tss.platform.service;

import com.tss.platform.dto.ImportJobStatusDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.ImportJobSampleFailureRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportJobRetryServiceTest {

    @Test
    void retryFailedJobResetsErrorFieldsAndLaunchesFullImport() {
        Fixture fixture = new Fixture();
        fixture.job.setStatus("FAILED");
        fixture.job.setProgress(80);
        fixture.job.setImportedSamples(3);
        fixture.job.setErrorCode("MANIFEST_VALIDATION_FAILED");
        fixture.job.setErrorMessage("bad manifest");
        fixture.job.setErrorDetailsJson("{\"field\":\"samples\"}");
        fixture.job.setExecutorId("executor-old");
        fixture.job.setStartedAt(Instant.parse("2026-06-28T10:00:00Z"));
        fixture.job.setHeartbeatAt(Instant.parse("2026-06-28T10:01:00Z"));
        fixture.job.setFinishedAt(Instant.parse("2026-06-28T10:02:00Z"));
        fixture.version.setStatus("DRAFT");
        when(fixture.sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdIsNull(
                fixture.version.getId()
        )).thenReturn(0L);

        ImportJobStatusDto dto = fixture.service.retry("ijob-1", "FULL");

        assertEquals("PENDING", dto.getStatus());
        assertEquals(0, dto.getProgress());
        assertEquals(0, dto.getImportedSamples());
        assertNull(dto.getErrorCode());
        assertNull(dto.getErrorMessage());
        assertNull(dto.getErrorDetailsJson());
        assertNull(fixture.job.getExecutorId());
        assertNull(fixture.job.getStartedAt());
        assertNull(fixture.job.getHeartbeatAt());
        assertNull(fixture.job.getFinishedAt());
        verify(fixture.importJobLauncher).launch("ijob-1");
        verify(fixture.auditService).recordFullRetry(
                fixture.asset,
                fixture.version,
                fixture.job
        );
    }

    @Test
    void retryRejectsNonFailedJobs() {
        Fixture fixture = new Fixture();
        fixture.job.setStatus("RUNNING");

        ImportJobQueryService.ImportJobRetryRejectedException error = assertThrows(
                ImportJobQueryService.ImportJobRetryRejectedException.class,
                () -> fixture.service.retry("ijob-1", "FULL")
        );

        assertTrue(error.getMessage().contains("only FAILED ImportJob can be retried"));
        verify(fixture.importJobLauncher, never()).launch(anyString());
    }

    @Test
    void retryRejectsPartialMode() {
        Fixture fixture = new Fixture();
        fixture.job.setStatus("FAILED");

        ImportJobQueryService.ImportJobRetryRejectedException error = assertThrows(
                ImportJobQueryService.ImportJobRetryRejectedException.class,
                () -> fixture.service.retry("ijob-1", "PARTIAL")
        );

        assertTrue(error.getMessage().contains("retry mode must be FULL or INCREMENTAL"));
        verify(fixture.importJobLauncher, never()).launch(anyString());
    }

    @Test
    void fullRetryDoesNotAcceptPartialJobs() {
        Fixture fixture = new Fixture();
        fixture.job.setStatus("PARTIAL");

        ImportJobQueryService.ImportJobRetryRejectedException error = assertThrows(
                ImportJobQueryService.ImportJobRetryRejectedException.class,
                () -> fixture.service.retry("ijob-1", "FULL")
        );

        assertTrue(error.getMessage().contains("PARTIAL ImportJob must use INCREMENTAL retry"));
        verify(fixture.importJobLauncher, never()).launch(anyString());
    }

    @Test
    void incrementalRetryPartialJobMarksFailedRowsRetryingAndLaunchesImport() {
        Fixture fixture = new Fixture();
        fixture.job.setStatus("PARTIAL");
        fixture.job.setProgress(50);
        fixture.job.setTotalSamples(2);
        fixture.job.setImportedSamples(1);
        fixture.job.setErrorCode("PARTIAL_IMPORT_FAILED");
        fixture.job.setErrorMessage("部分样本导入失败");
        fixture.version.setStatus("DRAFT");
        when(fixture.failureRepo.countByImportJobIdAndStatus("ijob-1", "FAILED"))
                .thenReturn(1L);
        when(fixture.failureRepo.markStatusByImportJobId(
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class)
        )).thenReturn(1);

        ImportJobStatusDto dto = fixture.service.retry("ijob-1", "INCREMENTAL");

        assertEquals("PENDING", dto.getStatus());
        assertEquals(50, dto.getProgress());
        assertEquals(1, dto.getImportedSamples());
        assertNull(dto.getErrorCode());
        assertNull(dto.getErrorMessage());
        verify(fixture.failureRepo).markStatusByImportJobId(
                "ijob-1",
                "FAILED",
                "RETRYING",
                fixture.job.getUpdatedAt()
        );
        verify(fixture.importJobLauncher).launch("ijob-1");
        verify(fixture.auditService).recordIncrementalRetry(
                fixture.asset,
                fixture.version,
                fixture.job,
                1
        );
    }

    @Test
    void retryRejectsJobsWithPersistedSamples() {
        Fixture fixture = new Fixture();
        fixture.job.setStatus("FAILED");
        fixture.version.setStatus("DRAFT");
        when(fixture.sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdIsNull(
                fixture.version.getId()
        )).thenReturn(1L);

        ImportJobQueryService.ImportJobRetryRejectedException error = assertThrows(
                ImportJobQueryService.ImportJobRetryRejectedException.class,
                () -> fixture.service.retry("ijob-1", "FULL")
        );

        assertTrue(error.getMessage().contains("already has imported samples"));
        verify(fixture.importJobLauncher, never()).launch(anyString());
    }

    @Test
    void retryRejectsFailedJobWhenSameVersionHasActiveImport() {
        Fixture fixture = new Fixture();
        fixture.job.setStatus("FAILED");
        ImportJob running = Fixture.job();
        running.setId("ijob-running");
        running.setStatus("RUNNING");
        when(fixture.sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdIsNull(
                fixture.version.getId()
        )).thenReturn(0L);
        when(fixture.importJobRepo.findByDatasetVersionId(fixture.version.getId()))
                .thenReturn(List.of(fixture.job, running));

        ImportJobQueryService.ImportJobRetryRejectedException error = assertThrows(
                ImportJobQueryService.ImportJobRetryRejectedException.class,
                () -> fixture.service.retry("ijob-1", "FULL")
        );

        assertTrue(error.getMessage().contains("dataset version already has an active ImportJob"));
        verify(fixture.importJobLauncher, never()).launch(anyString());
    }

    private static final class Fixture {
        private final ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final ImportJobLauncher importJobLauncher = mock(ImportJobLauncher.class);
        private final DatasetSampleRepository sampleRepo = mock(DatasetSampleRepository.class);
        private final ImportJobSampleFailureRepository failureRepo =
                mock(ImportJobSampleFailureRepository.class);
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final ImportJob job = job();
        private final DatasetVersion version = version();
        private final DatasetAsset asset = asset();
        private final ImportJobQueryService service = new ImportJobQueryService(
                importJobRepo,
                versionRepo,
                assetRepo,
                authContext,
                importJobLauncher,
                sampleRepo,
                failureRepo,
                auditService
        );

        private Fixture() {
            when(importJobRepo.findById(job.getId())).thenReturn(Optional.of(job));
            when(importJobRepo.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
            when(versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId())).thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
            when(importJobRepo.saveAndFlush(job)).thenReturn(job);
        }

        private static ImportJob job() {
            ImportJob value = new ImportJob();
            value.setId("ijob-1");
            value.setDatasetVersionId("version-1");
            value.setStatus("FAILED");
            value.setProgress(0);
            value.setImportedSamples(0);
            value.setCreatedAt(Instant.parse("2026-06-28T09:00:00Z"));
            return value;
        }

        private static DatasetVersion version() {
            DatasetVersion value = new DatasetVersion();
            value.setId("version-1");
            value.setAssetId("asset-1");
            value.setStatus("DRAFT");
            value.setOwnerUserId(7);
            value.setDeleted(false);
            return value;
        }

        private static DatasetAsset asset() {
            DatasetAsset value = new DatasetAsset();
            value.setId("asset-1");
            value.setOwnerUserId(7);
            value.setDeleted(false);
            return value;
        }
    }
}
