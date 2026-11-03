package com.tss.platform.repository;

import com.tss.platform.entity.DatasetUploadSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
// 随测试类关闭连接池，避免容器停止后 Spring 退出时仍等待数据库。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.show_sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DatasetUploadFailurePostgresRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6-alpine"))
                    .withDatabaseName("dataset_upload_failure_it")
                    .withUsername("dataset_upload_failure_it")
                    .withPassword("dataset_upload_failure_it_password");

    static {
        com.tss.platform.testsupport.IsolatedIntegrationContainers.configure(POSTGRES);
    }

    @DynamicPropertySource
    static void registerPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DatasetUploadSessionRepository sessionRepo;

    @Autowired
    private EntityManager entityManager;

    @Test
    void failedSessionCanBeResumedAndClaimClearsFailureAtomically() {
        DatasetUploadSession session = failedSession("upload-failed");
        sessionRepo.saveAndFlush(session);

        DatasetUploadSession resumed = sessionRepo
                .findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(
                        "sha256:failed",
                        List.of("UPLOADING", "FAILED"),
                        7
                )
                .orElseThrow();
        assertEquals(session.getId(), resumed.getId());

        assertEquals(1, sessionRepo.updateStatusIfCurrent(
                session.getId(),
                7,
                "FAILED",
                "COMPLETING",
                NOW.plusSeconds(1)
        ));
        assertEquals(0, sessionRepo.updateStatusIfCurrent(
                session.getId(),
                7,
                "FAILED",
                "COMPLETING",
                NOW.plusSeconds(2)
        ));
        entityManager.clear();

        DatasetUploadSession claimed = sessionRepo.findById(session.getId())
                .orElseThrow();
        assertEquals("COMPLETING", claimed.getStatus());
        assertNull(claimed.getCompletionErrorCode());
        assertNull(claimed.getCompletionErrorMessage());
        assertNull(claimed.getCompletionErrorDetails());
        assertNull(claimed.getCompletionFailedAt());
    }

    @Test
    void failedStatusRequiresCompleteSafeErrorRecord() {
        DatasetUploadSession invalid = baseSession("upload-invalid");
        invalid.setStatus("FAILED");

        DataIntegrityViolationException error = assertThrows(
                DataIntegrityViolationException.class,
                () -> sessionRepo.saveAndFlush(invalid)
        );

        assertNotNull(error.getMessage());
    }

    @Test
    void nonFailedStatusCannotRetainFailureDetails() {
        DatasetUploadSession invalid = baseSession("upload-stale-error");
        invalid.setCompletionErrorCode("INVALID_DATASET_CONTENT");
        invalid.setCompletionErrorMessage("invalid");
        invalid.setCompletionErrorDetails(Map.of("stage", "VALIDATION"));
        invalid.setCompletionFailedAt(NOW);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> sessionRepo.saveAndFlush(invalid)
        );
    }

    private DatasetUploadSession failedSession(String id) {
        DatasetUploadSession session = baseSession(id);
        session.setStatus("FAILED");
        session.setCompletionErrorCode("INVALID_DATASET_CONTENT");
        session.setCompletionErrorMessage("数据集内容格式无效，请检查文件后重试");
        session.setCompletionErrorDetails(Map.of("stage", "VALIDATION"));
        session.setCompletionFailedAt(NOW);
        return session;
    }

    private DatasetUploadSession baseSession(String id) {
        DatasetUploadSession session = new DatasetUploadSession();
        session.setId(id);
        session.setFileFingerprint("sha256:failed");
        session.setUploadPurpose("INITIAL_DATASET");
        session.setFileName("corpus.txt");
        session.setFileSize(1L);
        session.setChunkSize(5 * 1024 * 1024);
        session.setTotalChunks(1);
        session.setDatasetName("Corpus");
        session.setVersion("v1");
        session.setVersionLabel("v1");
        session.setVersionLabelGenerated(true);
        session.setType("NLP");
        session.setStrictManifest(false);
        session.setAssetCreatedByUpload(false);
        session.setStatus("UPLOADING");
        session.setOwnerUserId(7);
        session.setCreatedAt(NOW);
        session.setUpdatedAt(NOW);
        return session;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = DatasetUploadSession.class)
    @EnableJpaRepositories(basePackageClasses = DatasetUploadSessionRepository.class)
    static class RepositoryTestConfig {
    }
}
