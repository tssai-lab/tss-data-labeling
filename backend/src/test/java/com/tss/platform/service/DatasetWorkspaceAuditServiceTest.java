package com.tss.platform.service;

import com.tss.platform.dto.DatasetWorkspaceAuditLogDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetWorkspaceAuditLog;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetWorkspaceAuditLogRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetWorkspaceAuditServiceTest {

    @Test
    void listByVersionChecksOwnerAndDoesNotExposeStorageFields() {
        Fixture fixture = new Fixture();
        fixture.stubAuthorizedVersion();
        DatasetWorkspaceAuditLog log = new DatasetWorkspaceAuditLog();
        log.setId("audit-1");
        log.setDatasetAssetId(fixture.asset.getId());
        log.setDatasetVersionId(fixture.version.getId());
        log.setOperation("IMPORT_JOB_FAILED");
        log.setActorType("SYSTEM");
        log.setOwnerUserId(7);
        log.setImportJobId("ijob-1");
        log.setPackageId("pkg-1");
        log.setDetails(Map.of(
                "errorCode", "DUPLICATE_SAMPLE",
                "storagePath", "users/7/datasets/asset-1/secret.zip",
                "objectName", "secret-object"
        ));
        log.setCreatedAt(Instant.parse("2026-07-04T00:00:00Z"));
        when(fixture.auditRepo
                .findByDatasetVersionIdOrderByCreatedAtDescIdDesc(
                        eq(fixture.version.getId()),
                        any(Pageable.class)
                ))
                .thenReturn(new PageImpl<>(List.of(log)));

        PageResponse<DatasetWorkspaceAuditLogDto> result =
                fixture.service.listByVersion(fixture.version.getId(), 1, 20);

        assertEquals(1, result.getTotal());
        DatasetWorkspaceAuditLogDto dto = result.getData().get(0);
        assertEquals("IMPORT_JOB_FAILED", dto.getOperation());
        assertEquals("ijob-1", dto.getImportJobId());
        assertEquals("pkg-1", dto.getPackageId());
        assertEquals("DUPLICATE_SAMPLE", dto.getDetails().get("errorCode"));
        assertFalse(dto.getDetails().containsKey("storagePath"));
        assertFalse(dto.getDetails().containsKey("objectName"));
        verify(fixture.authContext).canAccessOwner(fixture.asset.getOwnerUserId());
    }

    @Test
    void listByVersionRejectsAnotherOwnerBeforeQueryingLogs() {
        Fixture fixture = new Fixture();
        fixture.stubAuthorizedVersion();
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId()))
                .thenReturn(false);

        assertThrows(
                DatasetWorkspaceAuditService.DatasetWorkspaceAuditAccessException.class,
                () -> fixture.service.listByVersion(fixture.version.getId(), 1, 20)
        );
    }

    @Test
    void recordFullRetryAppendsUserAuditWithCurrentActor() {
        Fixture fixture = new Fixture();
        fixture.stubCurrentUser();
        ImportJob job = new ImportJob();
        job.setId("ijob-1");
        job.setDatasetVersionId(fixture.version.getId());
        job.setPackageId("pkg-1");

        fixture.service.recordFullRetry(fixture.asset, fixture.version, job);

        ArgumentCaptor<DatasetWorkspaceAuditLog> captor =
                ArgumentCaptor.forClass(DatasetWorkspaceAuditLog.class);
        verify(fixture.auditRepo).save(captor.capture());
        DatasetWorkspaceAuditLog saved = captor.getValue();
        assertEquals("IMPORT_JOB_RETRIED", saved.getOperation());
        assertEquals("USER", saved.getActorType());
        assertEquals(7, saved.getActorUserId());
        assertEquals(fixture.asset.getId(), saved.getDatasetAssetId());
        assertEquals(fixture.version.getId(), saved.getDatasetVersionId());
        assertEquals("FULL", saved.getDetails().get("retryMode"));
    }

    private static final class Fixture {
        private final DatasetWorkspaceAuditLogRepository auditRepo =
                mock(DatasetWorkspaceAuditLogRepository.class);
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetWorkspaceAuditService service =
                new DatasetWorkspaceAuditService(
                        auditRepo,
                        versionRepo,
                        assetRepo,
                        authContext
                );
        private final DatasetVersion version = version();
        private final DatasetAsset asset = asset();

        private void stubAuthorizedVersion() {
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId()))
                    .thenReturn(true);
        }

        private void stubCurrentUser() {
            when(authContext.currentUserId()).thenReturn(7);
        }

        private static DatasetVersion version() {
            DatasetVersion value = new DatasetVersion();
            value.setId("draft-1");
            value.setAssetId("asset-1");
            value.setParentVersionId("ready-1");
            value.setStatus("DRAFT");
            value.setOwnerUserId(7);
            value.setDeleted(false);
            return value;
        }

        private static DatasetAsset asset() {
            DatasetAsset value = new DatasetAsset();
            value.setId("asset-1");
            value.setOwnerUserId(7);
            value.setCurrentVersionId("ready-1");
            value.setDeleted(false);
            return value;
        }
    }
}
