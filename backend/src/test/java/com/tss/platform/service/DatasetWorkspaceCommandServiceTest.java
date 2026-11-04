package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetWorkspaceCommandServiceTest {

    @Test
    void rejectsMissingAndStaleWorkspaceRevisionWithStableErrors() {
        Fixture fixture = new Fixture();

        V2BusinessException missing = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.lockForMutation("workspace-1", null)
        );
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatus());
        assertEquals(
                "EXPECTED_WORKSPACE_REVISION_REQUIRED",
                missing.getErrorCode()
        );

        V2BusinessException stale = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.lockForMutation("workspace-1", 6L)
        );
        assertEquals(HttpStatus.CONFLICT, stale.getStatus());
        assertEquals("WORKSPACE_REVISION_CONFLICT", stale.getErrorCode());
        assertEquals(6L, stale.getDetails().get("expectedRevision"));
        assertEquals(7L, stale.getDetails().get("currentRevision"));
    }

    @Test
    void enforcesSingleWriterWhileUploadIsActive() {
        Fixture fixture = new Fixture();
        DatasetUploadSession upload = new DatasetUploadSession();
        upload.setId("upload-1");
        upload.setStatus("UPLOADING");
        when(fixture.uploadRepo.findByVersionIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq("workspace-1"),
                anyList()
        )).thenReturn(List.of(upload));

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.lockForMutation("workspace-1", 7L)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("WORKSPACE_BUSY", error.getErrorCode());
    }

    @Test
    void settlementMayIgnoreItsOwnActiveUpload() {
        Fixture fixture = new Fixture();
        DatasetUploadSession upload = new DatasetUploadSession();
        upload.setId("upload-1");
        upload.setStatus("COMPLETING");
        when(fixture.uploadRepo.findByVersionIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq("workspace-1"),
                anyList()
        )).thenReturn(List.of(upload));

        var access = fixture.service.lockForOperationSettlement(
                "workspace-1",
                7L,
                "upload-1"
        );

        assertEquals("workspace-1", access.workspace().getId());
    }

    private static final class Fixture {
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final DatasetUploadSessionRepository uploadRepo =
                mock(DatasetUploadSessionRepository.class);
        private final ImportJobRepository importRepo =
                mock(ImportJobRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetWorkspaceCommandService service =
                new DatasetWorkspaceCommandService(
                        versionRepo,
                        assetRepo,
                        uploadRepo,
                        importRepo,
                        authContext
                );

        private Fixture() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setOwnerUserId(7);
            DatasetVersion workspace = new DatasetVersion();
            workspace.setId("workspace-1");
            workspace.setAssetId("asset-1");
            workspace.setStatus("DRAFT");
            workspace.setWorkspaceRevision(7L);

            when(versionRepo.findByIdAndDeletedFalse("workspace-1"))
                    .thenReturn(Optional.of(workspace));
            when(assetRepo.findByIdAndDeletedFalseForUpdate("asset-1"))
                    .thenReturn(Optional.of(asset));
            when(versionRepo.findByIdAndDeletedFalseForUpdate("workspace-1"))
                    .thenReturn(Optional.of(workspace));
            when(authContext.canAccessOwner(7)).thenReturn(true);
            when(uploadRepo.findByVersionIdAndStatusIn(
                    org.mockito.ArgumentMatchers.eq("workspace-1"),
                    anyList()
            )).thenReturn(List.of());
            when(importRepo.findByDatasetVersionId("workspace-1"))
                    .thenReturn(List.of());
        }
    }
}
