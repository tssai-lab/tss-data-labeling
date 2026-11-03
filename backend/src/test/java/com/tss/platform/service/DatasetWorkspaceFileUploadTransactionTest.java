package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.v2.V2DatasetUploadCompleteRequest;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetWorkspaceFileUploadTransactionTest {

    @Test
    void composeAndHashRunOutsideDatabaseTransactions() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubStableChunks();
        fixture.stubObjectStore();
        fixture.stubPersistence();

        DatasetUploadSession completed = fixture.service.complete(
                fixture.session.getId(),
                new V2DatasetUploadCompleteRequest(5L)
        );

        assertEquals("COMPLETED", completed.getStatus());
        assertEquals("resource-1", completed.getTargetResourceId());
        verify(fixture.rawStorageService).attachRawObject(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
        verify(fixture.sessionRepo).saveAndFlush(fixture.session);
    }

    @Test
    void changedChunkSnapshotCannotBeAttached() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadChunk replacement = fixture.chunk("chunk-new");
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(
                fixture.session.getId()
        )).thenReturn(List.of(fixture.chunk), List.of(replacement));
        fixture.stubObjectStore();

        V2BusinessException exception = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.complete(
                        fixture.session.getId(),
                        new V2DatasetUploadCompleteRequest(5L)
                )
        );

        assertEquals(
                "UPLOAD_CHANGED_DURING_COMPLETION",
                exception.getErrorCode()
        );
        verify(fixture.rawStorageService, never()).attachRawObject(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
        verify(fixture.deleteTaskService)
                .enqueueDefaultBucketDeleteImmediately(
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    private static final class Fixture {
        private final byte[] content =
                "workspace-file".getBytes(StandardCharsets.UTF_8);
        private final AtomicInteger activeTransactions = new AtomicInteger();
        private final DatasetWorkspaceCommandService commandService =
                mock(DatasetWorkspaceCommandService.class);
        private final DatasetWorkspaceTextFilePolicy textFilePolicy =
                mock(DatasetWorkspaceTextFilePolicy.class);
        private final DatasetWorkspaceRawStorageService rawStorageService =
                mock(DatasetWorkspaceRawStorageService.class);
        private final V2DatasetWorkspaceResourceService resourceService =
                mock(V2DatasetWorkspaceResourceService.class);
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final DatasetUploadSessionRepository sessionRepo =
                mock(DatasetUploadSessionRepository.class);
        private final DatasetUploadChunkRepository chunkRepo =
                mock(DatasetUploadChunkRepository.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final MinioClient minioClient = mock(MinioClient.class);
        private final DatasetAsset asset = new DatasetAsset();
        private final DatasetVersion workspace = new DatasetVersion();
        private final DatasetUploadSession session = new DatasetUploadSession();
        private final DatasetUploadChunk chunk = chunk("chunk-original");
        private final DatasetWorkspaceFileUploadService service;

        private Fixture() throws Exception {
            asset.setId("asset-1");
            asset.setName("dataset");
            asset.setOwnerUserId(7);
            workspace.setId("workspace-1");
            workspace.setAssetId(asset.getId());
            workspace.setStatus("DRAFT");
            workspace.setWorkspaceRevision(5L);
            session.setId("upload-1");
            session.setUploadPurpose(DatasetWorkspaceFileUploadService.PURPOSE);
            session.setAssetId(asset.getId());
            session.setVersionId(workspace.getId());
            session.setOwnerUserId(asset.getOwnerUserId());
            session.setFileName("data.json");
            session.setFileSize((long) content.length);
            session.setChunkSize(5 * 1024 * 1024);
            session.setTotalChunks(1);
            session.setExpectedSha256(sha256(content));
            session.setTargetKind("DATA");
            session.setTargetOperation("CREATE");
            session.setTargetSampleId("sample-1");
            session.setStatus("UPLOADING");
            session.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            session.setUpdatedAt(session.getCreatedAt());

            DatasetWorkspaceCommandService.WorkspaceAccess access =
                    new DatasetWorkspaceCommandService.WorkspaceAccess(
                            asset,
                            workspace
                    );
            when(commandService.lockForOperationSettlement(
                    workspace.getId(),
                    5L,
                    session.getId()
            )).thenReturn(access);
            when(sessionRepo.findById(session.getId()))
                    .thenReturn(Optional.of(session));
            when(sessionRepo.findByIdForUpdate(session.getId()))
                    .thenAnswer(invocation -> {
                        assertTrue(activeTransactions.get() > 0);
                        return Optional.of(session);
                    });

            MinioConfig minioConfig = new MinioConfig();
            minioConfig.setBucket("test-bucket");
            service = new DatasetWorkspaceFileUploadService(
                    commandService,
                    textFilePolicy,
                    rawStorageService,
                    resourceService,
                    auditService,
                    sessionRepo,
                    chunkRepo,
                    mock(DatasetSampleRepository.class),
                    mock(DatasetSampleDataRepository.class),
                    mock(DatasetAnnotationRepository.class),
                    deleteTaskService,
                    minioClient,
                    minioConfig,
                    transactionManager()
            );
        }

        private DatasetUploadChunk chunk(String objectName) {
            DatasetUploadChunk value = new DatasetUploadChunk();
            value.setId("chunk-1");
            value.setUploadId("upload-1");
            value.setPartIndex(0);
            value.setObjectName(objectName);
            value.setSizeBytes((long) content.length);
            value.setEtag("etag-1");
            return value;
        }

        private void stubStableChunks() {
            when(chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                    .thenReturn(List.of(chunk));
        }

        private void stubObjectStore() throws Exception {
            when(minioClient.composeObject(any())).thenAnswer(invocation -> {
                assertEquals(0, activeTransactions.get());
                return mock(ObjectWriteResponse.class);
            });
            StatObjectResponse stat = mock(StatObjectResponse.class);
            when(stat.size()).thenReturn((long) content.length);
            when(minioClient.statObject(any())).thenAnswer(invocation -> {
                assertEquals(0, activeTransactions.get());
                return stat;
            });
            GetObjectResponse response = mock(GetObjectResponse.class);
            AtomicBoolean read = new AtomicBoolean();
            when(response.read(any(byte[].class))).thenAnswer(invocation -> {
                assertEquals(0, activeTransactions.get());
                if (!read.compareAndSet(false, true)) {
                    return -1;
                }
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(content, 0, buffer, 0, content.length);
                return content.length;
            });
            when(minioClient.getObject(any())).thenAnswer(invocation -> {
                assertEquals(0, activeTransactions.get());
                return response;
            });
        }

        private void stubPersistence() {
            DatasetPackage datasetPackage = new DatasetPackage();
            datasetPackage.setId("package-1");
            when(rawStorageService.attachRawObject(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            )).thenAnswer(invocation -> {
                assertTrue(activeTransactions.get() > 0);
                return datasetPackage;
            });
            when(resourceService.attachUploadedFile(
                    any(),
                    any(),
                    any(),
                    anyLong(),
                    any()
            )).thenReturn(
                    new V2DatasetWorkspaceResourceService.AttachedResource(
                            "DATA",
                            "resource-1",
                            "sample-1",
                            sha256(content)
                    )
            );
            when(sessionRepo.saveAndFlush(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(commandService.incrementRevision(workspace)).thenReturn(6L);
        }

        private PlatformTransactionManager transactionManager() {
            return new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(
                        TransactionDefinition definition
                ) {
                    activeTransactions.incrementAndGet();
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                    activeTransactions.decrementAndGet();
                }

                @Override
                public void rollback(TransactionStatus status) {
                    activeTransactions.decrementAndGet();
                }
            };
        }

        private static String sha256(byte[] value) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(value)
                );
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
