package com.tss.platform.service;

import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelArtifactAttestationServiceTest {

    @Test
    void backfillsHistoricalShaAfterFullArtifactVerification() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(null);
        when(fixture.integrity.inspect(scope.version.getStoragePath(), 4L))
                .thenReturn(new ModelArtifactIntegrityService.Inspection(4, sha('a')));

        ModelArtifactAttestationService.AttestedArtifact result =
                fixture.service.attestReady(scope.version.getId());

        assertEquals(sha('a'), result.sha256());
        assertEquals(sha('a'), scope.version.getArtifactSha256());
        assertEquals(sha('a'), scope.version.getArtifactAttestedSha256());
        assertNotNull(scope.version.getArtifactAttestedAt());
        verify(fixture.versionRepo).saveAndFlush(scope.version);
    }

    @Test
    void deterministicShaMismatchDemotesVersionAndClearsCurrentPointer() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(sha('a'));
        when(fixture.integrity.inspect(scope.version.getStoragePath(), 4L))
                .thenReturn(new ModelArtifactIntegrityService.Inspection(4, sha('b')));

        ModelArtifactException error = assertThrows(
                ModelArtifactException.class,
                () -> fixture.service.attestReady(scope.version.getId())
        );

        assertFalse(error.isStorageUnavailable());
        assertEquals("DRAFT", scope.version.getStatus());
        assertNull(scope.asset.getCurrentVersionId());
        verify(fixture.versionRepo).saveAndFlush(scope.version);
        verify(fixture.assetRepo).saveAndFlush(scope.asset);
    }

    @Test
    void temporaryStorageFailureDoesNotChangeVersionState() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(sha('a'));
        when(fixture.integrity.inspect(scope.version.getStoragePath(), 4L))
                .thenThrow(new ModelArtifactException("temporary", true));

        ModelArtifactException error = assertThrows(
                ModelArtifactException.class,
                () -> fixture.service.attestReady(scope.version.getId())
        );

        assertEquals(true, error.isStorageUnavailable());
        assertEquals("READY", scope.version.getStatus());
        assertEquals(scope.version.getId(), scope.asset.getCurrentVersionId());
        verify(fixture.versionRepo, never()).saveAndFlush(any());
        verify(fixture.assetRepo, never()).saveAndFlush(any());
    }

    @Test
    void missingObjectDemotesVersionAndClearsCurrentPointer() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(sha('a'));
        when(fixture.integrity.inspect(scope.version.getStoragePath(), 4L))
                .thenThrow(new ModelArtifactException("missing", false));

        assertThrows(
                ModelArtifactException.class,
                () -> fixture.service.attestReady(scope.version.getId())
        );

        assertEquals("DRAFT", scope.version.getStatus());
        assertNull(scope.asset.getCurrentVersionId());
    }

    @Test
    void streamingVerificationBackfillsHistoricalShaWithoutClaimingZipAttestation() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(null);

        fixture.service.recordStreamingVerification(
                scope.version.getId(),
                scope.version.getStoragePath(),
                scope.version.getSizeBytes(),
                sha('c')
        );

        assertEquals(sha('c'), scope.version.getArtifactSha256());
        assertNull(scope.version.getArtifactAttestedSha256());
        assertNull(scope.version.getArtifactAttestedAt());
        verify(fixture.versionRepo).saveAndFlush(scope.version);
    }

    @Test
    void streamingVerificationDoesNotOverwriteConcurrentLifecycleTransition() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(null);
        scope.version.setStatus("DEPRECATED");

        assertThrows(
                ModelArtifactException.class,
                () -> fixture.service.recordStreamingVerification(
                        scope.version.getId(),
                        scope.version.getStoragePath(),
                        scope.version.getSizeBytes(),
                        sha('c')
                )
        );

        assertEquals("DEPRECATED", scope.version.getStatus());
        assertEquals(scope.version.getId(), scope.asset.getCurrentVersionId());
        verify(fixture.versionRepo, never()).saveAndFlush(any());
        verify(fixture.assetRepo, never()).saveAndFlush(any());
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }

    private record Scope(ModelVersion version, ModelAsset asset) {
    }

    private static final class Fixture {
        private final ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        private final ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        private final ModelArtifactIntegrityService integrity =
                mock(ModelArtifactIntegrityService.class);
        private final PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        private final ModelArtifactAttestationService service;

        private Fixture() {
            when(transactionManager.getTransaction(any()))
                    .thenReturn(new SimpleTransactionStatus());
            doAnswer(invocation -> null).when(transactionManager).commit(any());
            doAnswer(invocation -> null).when(transactionManager).rollback(any());
            service = new ModelArtifactAttestationService(
                    versionRepo,
                    assetRepo,
                    integrity,
                    transactionManager
            );
        }

        private Scope scope(String artifactSha) {
            ModelVersion version = new ModelVersion();
            version.setId("model-ver-1");
            version.setAssetId("model-asset-1");
            version.setStatus("READY");
            version.setStoragePath("users/7/models/model-asset-1/v1/model.zip");
            version.setSizeBytes(4L);
            version.setArtifactSha256(artifactSha);
            ModelAsset asset = new ModelAsset();
            asset.setId("model-asset-1");
            asset.setCurrentVersionId(version.getId());
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(versionRepo.findByIdAndDeletedFalseForUpdate(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId()))
                    .thenReturn(Optional.of(asset));
            return new Scope(version, asset);
        }
    }
}
