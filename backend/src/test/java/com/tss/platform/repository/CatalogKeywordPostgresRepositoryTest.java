package com.tss.platform.repository;

import com.tss.platform.config.AuditSchemaInitializer;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.module1.service.AuditHooks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
// 随测试类关闭连接池，避免容器停止后 Spring 退出时仍等待数据库。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.show_sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CatalogKeywordPostgresRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.6-alpine")
    )
            .withDatabaseName("catalog_keyword_it")
            .withUsername("catalog_keyword_it")
            .withPassword("catalog_keyword_it_password");

    static {
        com.tss.platform.testsupport.IsolatedIntegrationContainers.configure(POSTGRES);
    }

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final int OWNER_ID = 61_001;
    private static final int OTHER_OWNER_ID = 61_002;

    @MockBean
    private AuditSchemaInitializer auditSchemaInitializer;

    @MockBean
    private AuditHooks auditHooks;

    @Autowired
    private ModelAssetRepository modelAssetRepo;

    @Autowired
    private ModelVersionRepository modelVersionRepo;

    @Autowired
    private DatasetAssetRepository datasetAssetRepo;

    @Autowired
    private DatasetVersionRepository datasetVersionRepo;

    @Test
    void modelSingleLetterKeywordMatchesNameOnlyAndPreservesScopeAndType() {
        saveModel("model-speech", "Speech Encoder", "CV", OWNER_ID,
                "ordinary", "v1", "speech.zip");
        saveModel("model-detector", "Detector", "CV", OWNER_ID,
                "pipeline z", "z-release", "weights.zip");
        saveModel("model-zebra", "Zebra Vision", "CV", OWNER_ID,
                "ordinary", "v1", "zebra.zip");
        saveModel("model-foreign", "Speech Remote", "CV", OTHER_OWNER_ID,
                "ordinary", "v1", "foreign.zip");
        saveModel("model-nlp", "Speech Language", "NLP", OWNER_ID,
                "ordinary", "v1", "language.zip");

        Page<ModelVersion> pMatches = modelVersionRepo.searchVisibleCatalog(
                OWNER_ID,
                "CV",
                "%p%",
                Pageable.unpaged()
        );
        Page<ModelVersion> zMatches = modelVersionRepo.searchVisibleCatalog(
                OWNER_ID,
                "CV",
                "%z%",
                Pageable.unpaged()
        );

        assertEquals(List.of("model-speech-version"), ids(pMatches));
        assertEquals(List.of("model-zebra-version"), ids(zMatches));
    }

    @Test
    void datasetSingleLetterKeywordMatchesNameOnlyForOwnerAndAdmin() {
        saveDataset("dataset-data", "Data One", "CV", OWNER_ID,
                "hidden f and h", "f-release", "history-file.zip", "hidden h");
        saveDataset("dataset-fresh", "Fresh Set", "CV", OWNER_ID,
                "ordinary", "v1", "fresh.zip", "ordinary");
        saveDataset("dataset-foreign", "Foreign Data", "CV", OTHER_OWNER_ID,
                "ordinary", "v1", "foreign.zip", "ordinary");
        saveDataset("dataset-nlp", "Data Language", "NLP", OWNER_ID,
                "ordinary", "v1", "language.zip", "ordinary");

        Page<DatasetAsset> ownerA = datasetAssetRepo.searchCatalogForOwner(
                OWNER_ID,
                "CV",
                "a",
                Pageable.unpaged()
        );
        Page<DatasetAsset> ownerF = datasetAssetRepo.searchCatalogForOwner(
                OWNER_ID,
                "CV",
                "f",
                Pageable.unpaged()
        );
        Page<DatasetAsset> ownerH = datasetAssetRepo.searchCatalogForOwner(
                OWNER_ID,
                "CV",
                "h",
                Pageable.unpaged()
        );
        Page<DatasetAsset> adminA = datasetAssetRepo.searchCatalogForAdmin(
                "CV",
                "a",
                PageRequest.of(0, 1)
        );

        assertEquals(List.of("dataset-data"), datasetIds(ownerA));
        assertEquals(List.of("dataset-fresh"), datasetIds(ownerF));
        assertEquals(List.of("dataset-fresh"), datasetIds(ownerH));
        assertEquals(2, adminA.getTotalElements());
        assertEquals(1, adminA.getContent().size());
        assertEquals(Set.of("dataset-data", "dataset-foreign"),
                Set.copyOf(datasetAssetRepo.searchCatalogForAdmin(
                        "CV",
                        "a",
                        Pageable.unpaged()
                ).getContent().stream().map(DatasetAsset::getId).toList()));
    }

    @Test
    void likeMetacharactersAreMatchedLiterally() {
        saveModel("model-literal", "100%_\\! Vision", "CV", OWNER_ID,
                "ordinary", "v1", "literal.zip");
        saveModel("model-hidden-literal", "Ordinary Model", "CV", OWNER_ID,
                "100%_\\! hidden", "100%_\\!", "100%_\\!.zip");
        saveDataset("dataset-literal", "100%_\\! Data", "CV", OWNER_ID,
                "ordinary", "v1", "literal.zip", "ordinary");
        saveDataset("dataset-hidden-literal", "Ordinary Dataset", "CV", OWNER_ID,
                "100%_\\! hidden", "100%_\\!", "100%_\\!.zip", "100%_\\! hidden");

        Page<ModelVersion> modelMatches = modelVersionRepo.searchVisibleCatalog(
                OWNER_ID,
                "CV",
                "%100!%!_\\!!%",
                Pageable.unpaged()
        );
        Page<DatasetAsset> datasetMatches = datasetAssetRepo.searchCatalogForOwner(
                OWNER_ID,
                "CV",
                "100!%!_\\!!",
                Pageable.unpaged()
        );

        assertEquals(List.of("model-literal-version"), ids(modelMatches));
        assertEquals(List.of("dataset-literal"), datasetIds(datasetMatches));
    }

    private void saveModel(
            String assetId,
            String name,
            String type,
            int ownerId,
            String remark,
            String version,
            String fileName
    ) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        ModelAsset asset = new ModelAsset();
        asset.setId(assetId);
        asset.setName(name);
        asset.setType(type);
        asset.setRemark(remark);
        asset.setOwnerUserId(ownerId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset.setDeleted(false);
        modelAssetRepo.saveAndFlush(asset);

        ModelVersion modelVersion = new ModelVersion();
        modelVersion.setId(assetId + "-version");
        modelVersion.setAssetId(assetId);
        modelVersion.setVersion(version);
        modelVersion.setFileName(fileName);
        modelVersion.setStoragePath("users/" + ownerId + "/models/" + assetId + "/" + fileName);
        modelVersion.setSizeBytes(1L);
        modelVersion.setStatus("READY");
        modelVersion.setOwnerUserId(ownerId);
        modelVersion.setCreatedAt(now);
        modelVersion.setDeleted(false);
        modelVersionRepo.saveAndFlush(modelVersion);
    }

    private void saveDataset(
            String assetId,
            String name,
            String type,
            int ownerId,
            String remark,
            String version,
            String fileName,
            String description
    ) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        DatasetAsset asset = new DatasetAsset();
        asset.setId(assetId);
        asset.setName(name);
        asset.setType(type);
        asset.setRemark(remark);
        asset.setOwnerUserId(ownerId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset.setDeleted(false);
        datasetAssetRepo.saveAndFlush(asset);

        DatasetVersion datasetVersion = new DatasetVersion();
        datasetVersion.setId(assetId + "-version");
        datasetVersion.setAssetId(assetId);
        datasetVersion.setVersion(version);
        datasetVersion.setVersionNo(1);
        datasetVersion.setVersionLabel(version);
        datasetVersion.setFileName(fileName);
        datasetVersion.setRemark(remark);
        datasetVersion.setDescription(description);
        datasetVersion.setStatus("READY");
        datasetVersion.setOwnerUserId(ownerId);
        datasetVersion.setCreatedAt(now);
        datasetVersion.setUpdatedAt(now);
        datasetVersion.setDeleted(false);
        datasetVersionRepo.saveAndFlush(datasetVersion);
    }

    private static List<String> ids(Page<ModelVersion> page) {
        return page.getContent().stream().map(ModelVersion::getId).toList();
    }

    private static List<String> datasetIds(Page<DatasetAsset> page) {
        return page.getContent().stream().map(DatasetAsset::getId).toList();
    }

    @SpringBootConfiguration
    @EntityScan(basePackageClasses = {
            ModelAsset.class,
            ModelVersion.class,
            DatasetAsset.class,
            DatasetVersion.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            ModelAssetRepository.class,
            ModelVersionRepository.class,
            DatasetAssetRepository.class,
            DatasetVersionRepository.class
    })
    static class RepositoryTestConfig {
    }
}
