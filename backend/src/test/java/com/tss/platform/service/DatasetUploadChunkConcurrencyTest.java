package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetUploadChunkConcurrencyTest {

    @Test
    void lateChunkDoesNotOverwriteCompletedSession() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession snapshot = fixture.session("UPLOADING");
        DatasetUploadSession completed = fixture.session("COMPLETED");
        when(fixture.sessionRepo.findById(snapshot.getId()))
                .thenReturn(Optional.of(snapshot));
        when(fixture.sessionRepo.findByIdForUpdate(snapshot.getId()))
                .thenReturn(Optional.of(completed));
        fixture.stubObjectStore();

        var progress = fixture.service.saveChunk(
                snapshot.getId(),
                0,
                fixture.file()
        );

        assertEquals("COMPLETED", progress.getStatus());
        verify(fixture.chunkRepo, never()).save(any());
        verify(fixture.sessionRepo, never()).save(any());
        verify(fixture.deleteTaskService)
                .enqueueDefaultBucketDeleteImmediately(
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    void stateChangeAfterObjectUploadRejectsMetadataAndCleansObject()
            throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession snapshot = fixture.session("UPLOADING");
        DatasetUploadSession discarded = fixture.session("DISCARDED");
        when(fixture.sessionRepo.findById(snapshot.getId()))
                .thenReturn(Optional.of(snapshot));
        when(fixture.sessionRepo.findByIdForUpdate(snapshot.getId()))
                .thenReturn(Optional.of(discarded));
        fixture.stubObjectStore();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.saveChunk(
                        snapshot.getId(),
                        0,
                        fixture.file()
                )
        );

        assertTrue(exception.getMessage().contains("DISCARDED"));
        verify(fixture.chunkRepo, never()).save(any());
        verify(fixture.deleteTaskService)
                .enqueueDefaultBucketDeleteImmediately(
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    void objectIoRunsBeforeLockedMetadataTransaction() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.session("UPLOADING");
        when(fixture.sessionRepo.findById(session.getId()))
                .thenReturn(Optional.of(session));
        when(fixture.sessionRepo.findByIdForUpdate(session.getId()))
                .thenReturn(Optional.of(session));
        when(fixture.sessionRepo.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.chunkRepo.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        fixture.stubObjectStore();

        var progress = fixture.service.saveChunk(
                session.getId(),
                0,
                fixture.file()
        );

        assertEquals("UPLOADING", progress.getStatus());
        verify(fixture.sessionRepo).findByIdForUpdate(session.getId());
        verify(fixture.chunkRepo).save(any());
        verify(fixture.sessionRepo).save(session);
    }

    private static final class Fixture {
        private final MinioClient minioClient = mock(MinioClient.class);
        private final DatasetUploadSessionRepository sessionRepo =
                mock(DatasetUploadSessionRepository.class);
        private final DatasetUploadChunkRepository chunkRepo =
                mock(DatasetUploadChunkRepository.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final AtomicInteger activeTransactions = new AtomicInteger();
        private final DatasetUploadService service;

        private Fixture() {
            MinioConfig minioConfig = new MinioConfig();
            minioConfig.setBucket("test-bucket");
            PlatformTransactionManager transactionManager =
                    new PlatformTransactionManager() {
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
            service = new DatasetUploadService(
                    minioClient,
                    minioConfig,
                    sessionRepo,
                    chunkRepo,
                    mock(DatasetAssetRepository.class),
                    mock(DatasetVersionRepository.class),
                    mock(DatasetPackageRepository.class),
                    mock(DatasetVersionPackageRepository.class),
                    mock(ImportJobRepository.class),
                    authContext,
                    deleteTaskService,
                    transactionManager,
                    null
            );
        }

        private DatasetUploadSession session(String status) {
            DatasetUploadSession session = new DatasetUploadSession();
            session.setId("upload-1");
            session.setOwnerUserId(7);
            session.setFileName("part.bin");
            session.setFileSize(128L);
            session.setChunkSize(5 * 1024 * 1024);
            session.setTotalChunks(1);
            session.setStatus(status);
            session.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            session.setUpdatedAt(session.getCreatedAt());
            return session;
        }

        private MockMultipartFile file() {
            return new MockMultipartFile(
                    "file",
                    "part-0",
                    "application/octet-stream",
                    new byte[128]
            );
        }

        private void stubObjectStore() throws Exception {
            when(minioClient.putObject(any())).thenAnswer(invocation -> {
                assertEquals(0, activeTransactions.get());
                return mock(ObjectWriteResponse.class);
            });
            StatObjectResponse stat = mock(StatObjectResponse.class);
            when(stat.size()).thenReturn(128L);
            when(stat.etag()).thenReturn("etag-1");
            when(minioClient.statObject(any())).thenAnswer(invocation -> {
                assertEquals(0, activeTransactions.get());
                return stat;
            });
        }
    }
}
