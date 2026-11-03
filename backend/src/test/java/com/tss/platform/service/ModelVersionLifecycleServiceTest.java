package com.tss.platform.service;

import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelVersionLifecycleServiceTest {

    @Test
    void switchesToVerifiedReadyVersion() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(false);

        ModelAsset result = fixture.service.switchCurrent(
                scope.asset.getId(),
                scope.version.getId()
        );

        assertEquals(scope.version.getId(), result.getCurrentVersionId());
        verify(fixture.attestation).attestReady(scope.version.getId());
        verify(fixture.assetRepo).saveAndFlush(scope.asset);
    }

    @Test
    void currentVersionCannotBeRetiredOrDeleted() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.retire(scope.version.getId(), "ARCHIVED")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.deleteVersion(scope.version.getId())
        );
        verify(fixture.deleteTaskService, never()).enqueueDefaultBucketDelete(
                any(), any(), any(), any()
        );
    }

    @Test
    void lastVersionCanOnlyBeDeletedWithAsset() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(false);
        when(fixture.versionRepo.countByAssetIdAndDeletedFalse(scope.asset.getId()))
                .thenReturn(1L);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.deleteVersion(scope.version.getId())
        );

        assertEquals(
                "last model version can only be deleted with the model asset",
                error.getMessage()
        );
    }

    @Test
    void referencedNonCurrentVersionCannotBeDeleted() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(false);
        when(fixture.versionRepo.countByAssetIdAndDeletedFalse(scope.asset.getId()))
                .thenReturn(2L);
        when(fixture.trainingRepo.countByModelVersionId(scope.version.getId()))
                .thenReturn(1L);

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.deleteVersion(scope.version.getId())
        );
    }

    @Test
    void producedNonCurrentVersionCannotBeDeleted() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(false);
        when(fixture.versionRepo.countByAssetIdAndDeletedFalse(scope.asset.getId()))
                .thenReturn(2L);
        when(fixture.trainingRepo.countByProducedModelVersionId(scope.version.getId()))
                .thenReturn(1L);

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.deleteVersion(scope.version.getId())
        );
        verify(fixture.deleteTaskService, never()).enqueueDefaultBucketDelete(
                any(), any(), any(), any()
        );
    }

    @Test
    void assetContainingProducedVersionCannotBeDeleted() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(false);
        List<ModelVersion> versions = List.of(scope.version);
        when(fixture.versionRepo.findByAssetIdAndDeletedFalse(scope.asset.getId()))
                .thenReturn(versions);
        when(fixture.trainingRepo.countByProducedModelVersionIdIn(
                List.of(scope.version.getId())
        )).thenReturn(1L);

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.deleteAsset(scope.asset.getId())
        );
        verify(fixture.deleteTaskService, never()).enqueueDefaultBucketDelete(
                any(), any(), any(), any()
        );
    }

    @Test
    void nonCurrentUnreferencedVersionIsSoftDeleted() {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope(false);
        when(fixture.versionRepo.countByAssetIdAndDeletedFalse(scope.asset.getId()))
                .thenReturn(2L);
        when(fixture.trainingRepo.countByModelVersionId(scope.version.getId()))
                .thenReturn(0L);

        Map<String, Object> result = fixture.service.deleteVersion(scope.version.getId());

        assertEquals(true, result.get("deleted"));
        assertEquals(true, scope.version.getDeleted());
        verify(fixture.versionRepo).saveAndFlush(scope.version);
    }

    private record Scope(ModelVersion version, ModelAsset asset) {
    }

    private static final class Fixture {
        private final ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        private final ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        private final TrainingExperimentVersionRepository trainingRepo =
                mock(TrainingExperimentVersionRepository.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final ModelArtifactAttestationService attestation =
                mock(ModelArtifactAttestationService.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        private final ModelVersionLifecycleService service;

        private Fixture() {
            when(transactionManager.getTransaction(any()))
                    .thenReturn(new SimpleTransactionStatus());
            doAnswer(invocation -> null).when(transactionManager).commit(any());
            doAnswer(invocation -> null).when(transactionManager).rollback(any());
            service = new ModelVersionLifecycleService(
                    assetRepo,
                    versionRepo,
                    trainingRepo,
                    deleteTaskService,
                    attestation,
                    authContext,
                    transactionManager
            );
        }

        private Scope scope(boolean current) {
            ModelAsset asset = new ModelAsset();
            asset.setId("model-asset-1");
            asset.setOwnerUserId(7);
            ModelVersion version = new ModelVersion();
            version.setId("model-ver-1");
            version.setAssetId(asset.getId());
            version.setOwnerUserId(7);
            version.setStatus("READY");
            version.setArtifactSha256("a".repeat(64));
            version.setDeleted(false);
            if (current) {
                asset.setCurrentVersionId(version.getId());
            }
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(versionRepo.findByIdAndDeletedFalseForUpdate(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            return new Scope(version, asset);
        }
    }
}
