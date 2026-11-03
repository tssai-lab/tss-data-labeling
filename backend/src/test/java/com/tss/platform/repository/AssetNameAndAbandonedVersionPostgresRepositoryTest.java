package com.tss.platform.repository;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class AssetNameAndAbandonedVersionPostgresRepositoryTest {

    private static final int OWNER_ID = 62_001;
    private static final int OTHER_OWNER_ID = 62_002;
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6-alpine"))
                    .withDatabaseName("asset_name_workspace_it")
                    .withUsername("asset_name_workspace_it")
                    .withPassword("asset_name_workspace_it_password");

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
    private CodeAssetRepository codeAssetRepo;

    @Autowired
    private DatasetAssetRepository datasetAssetRepo;

    @Autowired
    private DatasetVersionRepository datasetVersionRepo;

    @Test
    void codeNamesAreTrimmedCaseInsensitiveAndOwnerScoped() {
        codeAssetRepo.saveAndFlush(codeAsset(
                "code-owner", "\tTrainer \n", OWNER_ID, false
        ));

        assertTrue(codeAssetRepo.existsActiveNormalizedName(
                OWNER_ID, "Trainer", null
        ));
        assertFalse(codeAssetRepo.existsActiveNormalizedName(
                OWNER_ID, "trainer", "code-owner"
        ));
        assertFalse(codeAssetRepo.existsActiveNormalizedName(
                OTHER_OWNER_ID, "TRAINER", null
        ));

        codeAssetRepo.saveAndFlush(codeAsset(
                "code-other-owner", " TRAINER ", OTHER_OWNER_ID, false
        ));

        DataIntegrityViolationException conflict = assertThrows(
                DataIntegrityViolationException.class,
                () -> codeAssetRepo.saveAndFlush(codeAsset(
                        "code-duplicate", "  TRAINER  ", OWNER_ID, false
                ))
        );
        assertTrue(containsConstraint(
                conflict, "uk_code_asset_owner_normalized_name"
        ));
    }

    @Test
    void softDeletedCodeAssetDoesNotReserveItsName() {
        codeAssetRepo.saveAndFlush(codeAsset(
                "code-deleted", "Reusable", OWNER_ID, true
        ));

        CodeAsset reused = codeAssetRepo.saveAndFlush(codeAsset(
                "code-reused", " reusable ", OWNER_ID, false
        ));

        assertEquals(" reusable ", reused.getName());
        assertTrue(codeAssetRepo.existsActiveNormalizedName(
                OWNER_ID, "Reusable", null
        ));
    }

    @Test
    void abandonedTombstonesAreHiddenFromAllocationAndPurgeQueries() {
        DatasetAsset asset = datasetAsset();
        datasetAssetRepo.saveAndFlush(asset);
        datasetVersionRepo.saveAndFlush(version(
                "version-ready", "v1", 1, "READY", false, null
        ));
        datasetVersionRepo.saveAndFlush(version(
                "version-abandoned", "v2", 2, "ABANDONED", true,
                NOW.minusSeconds(100)
        ));
        datasetVersionRepo.saveAndFlush(version(
                "version-archived", "v3", 3, "ARCHIVED", true,
                NOW.minusSeconds(100)
        ));

        assertEquals(3, datasetVersionRepo.findMaxVersionNoByAssetId(asset.getId()));
        assertTrue(datasetVersionRepo.findByAssetIdAndVersion(
                asset.getId(), "v2"
        ).isEmpty());
        assertFalse(datasetVersionRepo.existsByAssetIdAndVersion(
                asset.getId(), "v2"
        ));
        assertTrue(datasetVersionRepo.findByAssetIdAndVersion(
                asset.getId(), "v3"
        ).isPresent());
        assertEquals(
                Set.of("version-archived"),
                datasetVersionRepo.findByDeletedTrueAndDeletedAtBefore(NOW)
                        .stream()
                        .map(DatasetVersion::getId)
                        .collect(java.util.stream.Collectors.toSet())
        );

        datasetVersionRepo.saveAndFlush(version(
                "version-reused", "v2", 2, "READY", false, null
        ));

        assertEquals(
                Set.of("version-ready", "version-reused"),
                datasetVersionRepo.findByAssetIdAndDeletedFalse(asset.getId())
                        .stream()
                        .map(DatasetVersion::getId)
                        .collect(java.util.stream.Collectors.toSet())
        );
        assertTrue(datasetVersionRepo.existsByAssetIdAndVersion(
                asset.getId(), "v2"
        ));
    }

    private static CodeAsset codeAsset(
            String id,
            String name,
            int ownerId,
            boolean deleted
    ) {
        CodeAsset asset = new CodeAsset();
        asset.setId(id);
        asset.setName(name);
        asset.setOwnerUserId(ownerId);
        asset.setCreatedAt(NOW);
        asset.setUpdatedAt(NOW);
        asset.setDeleted(deleted);
        asset.setDeletedAt(deleted ? NOW : null);
        return asset;
    }

    private static DatasetAsset datasetAsset() {
        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset");
        asset.setName("Dataset asset");
        asset.setType("NLP");
        asset.setOwnerUserId(OWNER_ID);
        asset.setCreatedAt(NOW);
        asset.setUpdatedAt(NOW);
        asset.setDeleted(false);
        return asset;
    }

    private static DatasetVersion version(
            String id,
            String label,
            int number,
            String status,
            boolean deleted,
            Instant deletedAt
    ) {
        DatasetVersion version = new DatasetVersion();
        version.setId(id);
        version.setAssetId("dataset-asset");
        version.setVersion(label);
        version.setVersionNo(number);
        version.setVersionLabel(label);
        version.setStatus(status);
        version.setOwnerUserId(OWNER_ID);
        version.setCreatedBy(OWNER_ID);
        version.setCreatedAt(NOW);
        version.setUpdatedAt(NOW);
        version.setDeleted(deleted);
        version.setDeletedAt(deletedAt);
        return version;
    }

    private static boolean containsConstraint(Throwable error, String constraint) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(constraint)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
            CodeAsset.class,
            DatasetAsset.class,
            DatasetVersion.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            CodeAssetRepository.class,
            DatasetAssetRepository.class,
            DatasetVersionRepository.class
    })
    static class RepositoryTestConfig {
    }
}
