package com.tss.platform.persistence;

import com.tss.platform.repository.DatasetCatalogReadinessRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DatasetCatalogReadinessPostgresContainerTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:16.6-alpine")
            )
                    .withDatabaseName("dataset_readiness")
                    .withUsername("dataset_readiness")
                    .withPassword("dataset_readiness_password");

    static {
        com.tss.platform.testsupport.IsolatedIntegrationContainers.configure(POSTGRES);
    }

    @BeforeAll
    static void migrateFreshDatabase() {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void catalogReadinessAggregateQueryExecutesAndMapsStableAliases() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        DatasetCatalogReadinessRepository repository =
                new DatasetCatalogReadinessRepository(
                        new NamedParameterJdbcTemplate(dataSource)
                );

        var result = repository.inspect(
                "workspace-missing",
                "asset-missing"
        );

        assertTrue(result.packageRelationInvalid());
        assertTrue(result.noActiveSample());
        assertFalse(result.activeUpload());
        assertFalse(result.resourceStorageInvalid());
    }
}
