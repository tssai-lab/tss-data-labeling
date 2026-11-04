package com.tss.platform.service;

import com.tss.platform.dto.ImportJobStatusDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportJobQueryServiceTest {

    @Test
    void returnsStatusAfterCheckingVersionAndAssetOwnership() {
        Fixture fixture = new Fixture();
        ImportJob job = fixture.job();
        DatasetVersion version = fixture.version(job);
        DatasetAsset asset = fixture.asset(version);
        when(fixture.jobRepo.findById(job.getId())).thenReturn(Optional.of(job));
        when(fixture.versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
        when(fixture.assetRepo.findByIdAndDeletedFalse(asset.getId())).thenReturn(Optional.of(asset));
        when(fixture.authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);

        ImportJobStatusDto status = fixture.service.getStatus(job.getId());

        assertEquals(job.getId(), status.getImportJobId());
        assertEquals(version.getId(), status.getDatasetVersionId());
        assertEquals("PENDING", status.getStatus());
        assertEquals(0, status.getProgress());
    }

    @Test
    void rejectsStatusLookupForAnotherOwnersAsset() {
        Fixture fixture = new Fixture();
        ImportJob job = fixture.job();
        DatasetVersion version = fixture.version(job);
        DatasetAsset asset = fixture.asset(version);
        when(fixture.jobRepo.findById(job.getId())).thenReturn(Optional.of(job));
        when(fixture.versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
        when(fixture.assetRepo.findByIdAndDeletedFalse(asset.getId())).thenReturn(Optional.of(asset));
        when(fixture.authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(false);

        assertThrows(
                ImportJobQueryService.ImportJobAccessException.class,
                () -> fixture.service.getStatus(job.getId())
        );
    }

    @Test
    void importJobBusinessExceptionsDoNotExtendIllegalArgumentException() {
        RuntimeException access =
                new ImportJobQueryService.ImportJobAccessException(
                        "importJob not found or no permission"
                );
        RuntimeException rejected =
                new ImportJobQueryService.ImportJobRetryRejectedException(
                        "only FAILED ImportJob can be retried"
                );

        assertFalse(access instanceof IllegalArgumentException);
        assertFalse(rejected instanceof IllegalArgumentException);
    }

    private static final class Fixture {
        private final ImportJobRepository jobRepo = mock(ImportJobRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final ImportJobLauncher importJobLauncher = mock(ImportJobLauncher.class);
        private final DatasetSampleRepository sampleRepo = mock(DatasetSampleRepository.class);
        private final ImportJobQueryService service =
                new ImportJobQueryService(
                        jobRepo,
                        versionRepo,
                        assetRepo,
                        authContext,
                        importJobLauncher,
                        sampleRepo
                );

        private ImportJob job() {
            ImportJob job = new ImportJob();
            job.setId("ijob-1");
            job.setDatasetVersionId("version-1");
            job.setStatus("PENDING");
            job.setProgress(0);
            job.setImportedSamples(0);
            job.setCreatedAt(Instant.now());
            return job;
        }

        private DatasetVersion version(ImportJob job) {
            DatasetVersion version = new DatasetVersion();
            version.setId(job.getDatasetVersionId());
            version.setAssetId("asset-1");
            version.setStatus("DRAFT");
            version.setDeleted(false);
            return version;
        }

        private DatasetAsset asset(DatasetVersion version) {
            DatasetAsset asset = new DatasetAsset();
            asset.setId(version.getAssetId());
            asset.setOwnerUserId(7);
            asset.setDeleted(false);
            return asset;
        }
    }
}
