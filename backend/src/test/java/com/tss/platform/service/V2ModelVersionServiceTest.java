package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.v2.V2ModelConsumerManifest;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2ModelVersionServiceTest {

    @Test
    void consumerManifestReturnsOnlyPublicVerifiedContract() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asset.setCurrentVersionId(fixture.version.getId());
        fixture.version.setHyperParams(Map.of("epochs", 20));

        V2ModelConsumerManifest manifest =
                fixture.service.consumerManifest(fixture.version.getId());

        assertEquals(fixture.asset.getId(), manifest.modelAssetId());
        assertEquals(fixture.version.getId(), manifest.modelVersionId());
        assertEquals("a".repeat(64), manifest.artifactSha256());
        assertTrue(manifest.isCurrent());
        assertEquals(
                "/api/v2/model-versions/model-ver-1/download",
                manifest.downloadUrl()
        );
        String json = new ObjectMapper().writeValueAsString(manifest);
        assertFalse(json.contains("storagePath"));
        assertFalse(json.contains("bucket"));
        assertFalse(json.contains("objectName"));
    }

    @Test
    void deterministicArtifactFailureMapsTo422AndTemporaryFailureMapsTo503() {
        Fixture fixture = new Fixture();
        doThrow(new ModelArtifactException("bad", false))
                .when(fixture.attestation).attestReady(fixture.version.getId());
        V2BusinessException invalid = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.consumerManifest(fixture.version.getId())
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getStatus());

        doThrow(new ModelArtifactException("temporary", true))
                .when(fixture.attestation).attestReady(fixture.version.getId());
        V2BusinessException unavailable = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.consumerManifest(fixture.version.getId())
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatus());
    }

    @Test
    void filesRejectsAndInvalidatesFileDirectoryConflict() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.minio.downloadStream(fixture.version.getStoragePath()))
                .thenReturn(new ByteArrayInputStream(fileDirectoryConflictZip()));

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.files(fixture.version.getId())
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        verify(fixture.attestation).invalidate(fixture.version.getId());
    }

    @Test
    void attestedDownloadUsesOneStreamingIntegrityReadWithoutPrehash()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.version.setArtifactAttestedSha256(
                fixture.version.getArtifactSha256()
        );
        byte[] content = new byte[]{1, 2, 3, 4};
        when(fixture.integrity.openVerified(
                eq(fixture.version.getStoragePath()),
                eq(fixture.version.getSizeBytes()),
                eq(fixture.version.getArtifactSha256()),
                isNull(),
                any(Runnable.class)
        )).thenReturn(new ByteArrayInputStream(content));

        V2ModelVersionService.Download download =
                fixture.service.download(fixture.version.getId());

        assertEquals(fixture.version.getArtifactSha256(), download.sha256());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                content,
                download.inputStream().readAllBytes()
        );
        verify(fixture.attestation, never()).attestReady(fixture.version.getId());
        verify(fixture.integrity, times(1)).openVerified(
                eq(fixture.version.getStoragePath()),
                eq(fixture.version.getSizeBytes()),
                eq(fixture.version.getArtifactSha256()),
                isNull(),
                any(Runnable.class)
        );
        verify(fixture.minio, never()).downloadStream(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicalDownloadComputesAndBackfillsShaWithoutASeparatePrehash()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.version.setArtifactSha256(null);
        fixture.version.setArtifactAttestedSha256(null);
        AtomicReference<Consumer<ModelArtifactIntegrityService.Inspection>> callback =
                new AtomicReference<>();
        when(fixture.integrity.openVerified(
                eq(fixture.version.getStoragePath()),
                eq(fixture.version.getSizeBytes()),
                isNull(),
                any(Consumer.class),
                any(Runnable.class)
        )).thenAnswer(invocation -> {
            callback.set(invocation.getArgument(3));
            return new ByteArrayInputStream(new byte[]{1, 2, 3, 4});
        });

        V2ModelVersionService.Download download =
                fixture.service.download(fixture.version.getId());
        callback.get().accept(new ModelArtifactIntegrityService.Inspection(
                fixture.version.getSizeBytes(),
                "b".repeat(64)
        ));

        assertEquals(null, download.sha256());
        verify(fixture.attestation, never()).attestReady(fixture.version.getId());
        verify(fixture.attestation).recordStreamingVerification(
                fixture.version.getId(),
                fixture.version.getStoragePath(),
                fixture.version.getSizeBytes(),
                "b".repeat(64)
        );
    }

    private static byte[] fileDirectoryConflictZip() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("weights"));
            zip.write("file".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("weights/model.pt"));
            zip.write("model".getBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static final class Fixture {
        private final ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        private final ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        private final ModelArtifactAttestationService attestation =
                mock(ModelArtifactAttestationService.class);
        private final ModelArtifactIntegrityService integrity =
                mock(ModelArtifactIntegrityService.class);
        private final ModelVersionLifecycleService lifecycle =
                mock(ModelVersionLifecycleService.class);
        private final ModelCodePreviewService preview = mock(ModelCodePreviewService.class);
        private final MinioService minio = mock(MinioService.class);
        private final AuthContext auth = mock(AuthContext.class);
        private final ModelVersion version = new ModelVersion();
        private final ModelAsset asset = new ModelAsset();
        private final V2ModelVersionService service;

        private Fixture() {
            version.setId("model-ver-1");
            version.setAssetId("model-asset-1");
            version.setVersion("v1");
            version.setStatus("READY");
            version.setFileName("model.zip");
            version.setStoragePath("users/7/models/model-asset-1/v1/model.zip");
            version.setSizeBytes(128L);
            version.setArtifactSha256("a".repeat(64));
            version.setCommitInfo("git abc123");
            version.setOwnerUserId(7);
            asset.setId("model-asset-1");
            asset.setType("CV");
            asset.setOwnerUserId(7);
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(auth.canAccessOwner(7)).thenReturn(true);
            when(attestation.attestReady(version.getId())).thenReturn(
                    new ModelArtifactAttestationService.AttestedArtifact(
                            version,
                            asset,
                            128,
                            "a".repeat(64),
                            null
                    )
            );
            service = new V2ModelVersionService(
                    versionRepo,
                    assetRepo,
                    attestation,
                    integrity,
                    lifecycle,
                    preview,
                    minio,
                    auth
            );
        }
    }
}
