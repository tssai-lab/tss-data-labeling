package com.tss.platform.service;

import com.tss.platform.dto.DatasetWorkspaceSampleMutationDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetWorkspaceSampleMutationServiceTest {

    @Test
    void softDeletesOnlyTheDraftSampleAndPreservesWorkspacePointers() {
        Fixture fixture = new Fixture();
        DatasetSample parentReadySample = fixture.parentReadySample();
        fixture.stubAuthorizedDraft();

        DatasetWorkspaceSampleMutationDto result =
                fixture.service.deleteSample(fixture.sample.getId());

        assertTrue(fixture.sample.getDeleted());
        assertEquals(fixture.sample.getDeletedAt(), fixture.sample.getUpdatedAt());
        assertEquals(fixture.sample.getDeletedAt(), result.getDeletedAt());
        assertTrue(result.getDeleted());
        assertEquals("DRAFT", fixture.version.getStatus());
        assertEquals("ready-1", fixture.asset.getCurrentVersionId());
        assertFalse(parentReadySample.getDeleted());
        assertNull(parentReadySample.getDeletedAt());
        verify(fixture.sampleRepo).saveAndFlush(fixture.sample);
        verify(fixture.auditService).recordSampleDeleted(
                fixture.asset,
                fixture.version,
                fixture.sample
        );
        verify(fixture.versionRepo, never()).save(any());
        verify(fixture.assetRepo, never()).save(any());
    }

    @Test
    void deletesAppendCreatedDraftSampleWithoutChangingItsPackageReference() {
        Fixture fixture = new Fixture();
        fixture.sample.setCreatedByPackageId("append-package-1");
        fixture.stubAuthorizedDraft();

        fixture.service.deleteSample(fixture.sample.getId());

        assertTrue(fixture.sample.getDeleted());
        assertEquals("append-package-1", fixture.sample.getCreatedByPackageId());
    }

    @Test
    void rejectsReadyAndUnauthorizedSamples() {
        Fixture ready = new Fixture();
        ready.version.setStatus("READY");
        ready.stubVersionAndAsset();
        when(ready.authContext.canAccessOwner(ready.asset.getOwnerUserId()))
                .thenReturn(true);

        IllegalArgumentException readyError = assertThrows(
                IllegalArgumentException.class,
                () -> ready.service.deleteSample(ready.sample.getId())
        );
        assertEquals(Fixture.NOT_FOUND, readyError.getMessage());
        verify(ready.sampleRepo, never()).saveAndFlush(any());

        Fixture unauthorized = new Fixture();
        unauthorized.stubVersionAndAsset();
        when(unauthorized.authContext.canAccessOwner(
                unauthorized.asset.getOwnerUserId()
        )).thenReturn(false);

        IllegalArgumentException unauthorizedError = assertThrows(
                IllegalArgumentException.class,
                () -> unauthorized.service.deleteSample(unauthorized.sample.getId())
        );
        assertEquals(Fixture.NOT_FOUND, unauthorizedError.getMessage());
        verify(unauthorized.sampleRepo, never()).saveAndFlush(any());
    }

    @Test
    void repeatedDeleteIsIdempotent() {
        Fixture fixture = new Fixture();
        Instant deletedAt = Instant.parse("2026-06-12T10:00:00Z");
        fixture.sample.setDeleted(true);
        fixture.sample.setDeletedAt(deletedAt);
        fixture.stubAuthorizedDraft();

        DatasetWorkspaceSampleMutationDto result =
                fixture.service.deleteSample(fixture.sample.getId());

        assertTrue(result.getDeleted());
        assertSame(deletedAt, result.getDeletedAt());
        verify(fixture.sampleRepo, never()).saveAndFlush(any());
        verify(fixture.auditService, never()).recordSampleDeleted(any(), any(), any());
    }

    @Test
    void restoresDeletedDraftSampleAndRepeatedRestoreIsIdempotent() {
        Fixture fixture = new Fixture();
        fixture.sample.setDeleted(true);
        fixture.sample.setDeletedAt(Instant.parse("2026-06-12T10:00:00Z"));
        fixture.stubAuthorizedDraft();

        DatasetWorkspaceSampleMutationDto restored =
                fixture.service.restoreSample(fixture.sample.getId());

        assertFalse(fixture.sample.getDeleted());
        assertNull(fixture.sample.getDeletedAt());
        assertFalse(restored.getDeleted());
        verify(fixture.sampleRepo).saveAndFlush(fixture.sample);
        verify(fixture.auditService).recordSampleRestored(
                fixture.asset,
                fixture.version,
                fixture.sample
        );

        Fixture repeated = new Fixture();
        repeated.stubAuthorizedDraft();

        DatasetWorkspaceSampleMutationDto unchanged =
                repeated.service.restoreSample(repeated.sample.getId());

        assertFalse(unchanged.getDeleted());
        assertNull(unchanged.getDeletedAt());
        verify(repeated.sampleRepo, never()).saveAndFlush(any());
        verify(repeated.auditService, never()).recordSampleRestored(any(), any(), any());
    }

    private static final class Fixture {
        private static final String NOT_FOUND =
                "dataset workspace sample not found or no permission";

        private final DatasetSampleRepository sampleRepo =
                mock(DatasetSampleRepository.class);
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final DatasetVersion version = version();
        private final DatasetAsset asset = asset();
        private final DatasetSample sample = sample();
        private final DatasetWorkspaceSampleMutationService service =
                new DatasetWorkspaceSampleMutationService(
                        sampleRepo,
                        versionRepo,
                        assetRepo,
                        authContext,
                        auditService
                );

        private void stubAuthorizedDraft() {
            stubVersionAndAsset();
            when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);
        }

        private void stubVersionAndAsset() {
            when(sampleRepo.findByIdForUpdate(sample.getId()))
                    .thenReturn(Optional.of(sample));
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(sampleRepo.saveAndFlush(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }

        private DatasetSample parentReadySample() {
            DatasetSample parent = new DatasetSample();
            parent.setId("parent-sample-1");
            parent.setDatasetVersionId("ready-1");
            parent.setExternalId(sample.getExternalId());
            parent.setSampleIndex(sample.getSampleIndex());
            parent.setDeleted(false);
            return parent;
        }

        private static DatasetSample sample() {
            DatasetSample sample = new DatasetSample();
            sample.setId("draft-sample-1");
            sample.setDatasetVersionId("draft-1");
            sample.setExternalId("scene-1");
            sample.setSampleIndex(1);
            sample.setDeleted(false);
            sample.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            return sample;
        }

        private static DatasetVersion version() {
            DatasetVersion version = new DatasetVersion();
            version.setId("draft-1");
            version.setAssetId("asset-1");
            version.setStatus("DRAFT");
            version.setDeleted(false);
            return version;
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setOwnerUserId(7);
            asset.setCurrentVersionId("ready-1");
            asset.setDeleted(false);
            return asset;
        }
    }
}
