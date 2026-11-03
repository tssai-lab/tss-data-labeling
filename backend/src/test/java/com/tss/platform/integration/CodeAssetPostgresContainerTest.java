package com.tss.platform.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CodeAssetPostgresContainerTest {

    private static final String POSTGRES_IMAGE = "postgres:16.6-alpine";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private static final String ASSET_LOCK_SQL = """
            SELECT id
            FROM code_asset
            WHERE id = ? AND deleted = FALSE
            FOR UPDATE
            """;

    private static final String WORKSPACE_LOCK_SQL = """
            SELECT id, status, revision
            FROM code_workspace
            WHERE id = ? AND deleted = FALSE
            FOR UPDATE
            """;

    private static final String INSERT_WORKSPACE_SQL = """
            INSERT INTO code_workspace (
                id, asset_id, status, revision, owner_user_id,
                created_at, updated_at, deleted
            )
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
            """;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("code_asset_integration")
                    .withUsername("code_asset_test")
                    .withPassword("code_asset_test_password");

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

    @BeforeEach
    void resetSystemConfiguration() throws SQLException {
        executeUpdate("""
                UPDATE platform_system_config
                SET training_code_review_mode = 'STANDARD_REVIEW',
                    updated_by_user_id = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = 'GLOBAL'
                """);
    }

    @Test
    void flywayAppliesEveryAvailableMigration()
            throws SQLException {
        List<String> expectedVersions = new ArrayList<>(IntStream.rangeClosed(1, 30)
                .mapToObj(Integer::toString)
                .toList());
        for (String upstreamMigration : List.of(
                "V31__training_produced_model.sql",
                "V32__training_plan_run_spec_snapshot.sql",
                "V33__training_output_evidence.sql",
                "V34__training_mode.sql",
                "V36__compute_server.sql",
                "V37__server_metric_snapshot.sql",
                "V38__task_queue_fields.sql",
                "V39__server_metric_history.sql",
                "V40__compute_server_capacity.sql",
                "V42__compute_server_labels_enabled.sql",
                "V47__compute_server_gpu_count.sql"
        )) {
            if (Thread.currentThread()
                    .getContextClassLoader()
                    .getResource("db/migration/" + upstreamMigration) != null) {
                expectedVersions.add(upstreamMigration.substring(
                        1,
                        upstreamMigration.indexOf("__")
                ));
            }
        }
        expectedVersions.add("41");
        expectedVersions.add("43");
        expectedVersions.add("44");
        expectedVersions.add("45");
        expectedVersions.add("46");
        expectedVersions.add("48");
        expectedVersions.add("49");
        expectedVersions.add("50");
        expectedVersions.add("51");
        expectedVersions.add("52");
        expectedVersions.add("53");
        expectedVersions.add("54");
        expectedVersions.add("55");
        expectedVersions.add("56");
        expectedVersions.add("57");
        expectedVersions.add("58");
        expectedVersions.add("59");
        expectedVersions.add("60");
        expectedVersions.add("61");
        expectedVersions.add("62");
        expectedVersions.add("63");
        expectedVersions.add("64");
        expectedVersions.add("65");
        expectedVersions.add("66");
        expectedVersions.add("67");
        Collections.sort(expectedVersions, Comparator.comparingInt(Integer::parseInt));
        List<String> installedVersions = queryStrings("""
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE AND version IS NOT NULL
                ORDER BY installed_rank
                """);

        assertEquals(expectedVersions, installedVersions);
        String lastVersion = installedVersions.get(installedVersions.size() - 1);
        assertNotNull(lastVersion);
        assertTrue(Integer.parseInt(lastVersion) >= 46);
        for (String table : List.of(
                "code_workspace",
                "code_workspace_file_delta",
                "code_validation_run",
                "code_approval_record",
                "code_asset_audit_log",
                "code_risk_assessment",
                "code_risk_finding",
                "platform_system_config",
                "training_plan_definition",
                "user_api_policies"
        )) {
            assertEquals(
                    1L,
                    queryLong("""
                            SELECT COUNT(*)
                            FROM information_schema.tables
                            WHERE table_schema = 'public' AND table_name = ?
                            """, table),
                    () -> "code asset table was not created: " + table
            );
        }
        assertEquals(
                List.of("STANDARD_REVIEW"),
                queryStrings("""
                        SELECT training_code_review_mode
                        FROM platform_system_config
                        WHERE id = 'GLOBAL'
                        """)
        );
        assertEquals(3L, queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'platform_system_config'
                  AND column_name IN (
                      'model_cache_max_bytes',
                      'model_cache_min_free_bytes',
                      'model_cache_runtime_reserve_bytes'
                  )
                """));
        assertEquals(
                List.of("1073741824,3221225472,10737418240"),
                queryStrings("""
                        SELECT CONCAT_WS(',',
                            model_cache_max_bytes,
                            model_cache_min_free_bytes,
                            model_cache_runtime_reserve_bytes)
                        FROM platform_system_config
                        WHERE id = 'GLOBAL'
                        """)
        );
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void v48BackfillsWorkspaceHeadFromCurrentWhileKeepingHistoricalParent()
            throws SQLException {
        String schema = "v48_" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 16);
        try {
            migrateSchema(schema, MigrationVersion.fromVersion("47"));
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword()
            );
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.executeUpdate("""
                        INSERT INTO dataset_asset (
                            id, name, type, owner_user_id,
                            created_at, updated_at, deleted
                        )
                        VALUES (
                            'v48-asset', 'V48 asset', 'MULTIMODAL', 7,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE
                        )
                        """);
                statement.executeUpdate("""
                        INSERT INTO dataset_version (
                            id, asset_id, version, version_no, version_label,
                            status, owner_user_id, created_by,
                            created_at, updated_at, deleted,
                            active_draft_asset_id
                        )
                        VALUES
                            ('v48-history', 'v48-asset', 'v1', 1, 'v1',
                             'READY', 7, 7, CURRENT_TIMESTAMP,
                             CURRENT_TIMESTAMP, FALSE, NULL),
                            ('v48-current', 'v48-asset', 'v2', 2, 'v2',
                             'READY', 7, 7, CURRENT_TIMESTAMP,
                             CURRENT_TIMESTAMP, FALSE, NULL),
                            ('v48-workspace', 'v48-asset', 'v3', 3, 'v3',
                             'DRAFT', 7, 7, CURRENT_TIMESTAMP,
                             CURRENT_TIMESTAMP, FALSE, 'v48-asset')
                        """);
                statement.executeUpdate("""
                        UPDATE dataset_version
                        SET parent_version_id = 'v48-history'
                        WHERE id = 'v48-workspace'
                        """);
                statement.executeUpdate("""
                        UPDATE dataset_asset
                        SET current_version_id = 'v48-current'
                        WHERE id = 'v48-asset'
                        """);
            }

            migrateSchema(schema, null);

            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword()
            );
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet result = statement.executeQuery("""
                        SELECT parent_version_id, workspace_head_version_id
                        FROM dataset_version
                        WHERE id = 'v48-workspace'
                        """)) {
                    assertTrue(result.next());
                    assertEquals("v48-history", result.getString("parent_version_id"));
                    assertEquals("v48-current", result.getString("workspace_head_version_id"));
                }
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword()
            );
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void v52PreservesHistoricalCodeDuplicatesAndReleasesOnlyAbandonedVersions()
            throws SQLException {
        String schema = "v52_" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 16);
        try {
            migrateSchema(schema, MigrationVersion.fromVersion("51"));
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword()
            );
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.executeUpdate("""
                        INSERT INTO code_asset (
                            id, name, owner_user_id,
                            created_at, updated_at, deleted
                        )
                        VALUES
                            ('v52-code-a', ' Trainer ', 7,
                             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
                            ('v52-code-b', 'trainer', 7,
                             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
                            ('v52-code-deleted', 'Reusable', 7,
                             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, TRUE)
                        """);
                statement.executeUpdate("""
                        INSERT INTO dataset_asset (
                            id, name, type, owner_user_id,
                            created_at, updated_at, deleted
                        )
                        VALUES (
                            'v52-dataset', 'V52 dataset', 'NLP', 7,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE
                        )
                        """);
                statement.executeUpdate("""
                        INSERT INTO dataset_version (
                            id, asset_id, version, version_no, version_label,
                            status, owner_user_id, created_by,
                            created_at, updated_at, deleted, deleted_at,
                            active_draft_asset_id
                        )
                        VALUES
                            ('v52-ready', 'v52-dataset', 'v1', 1, 'v1',
                             'READY', 7, 7, CURRENT_TIMESTAMP,
                             CURRENT_TIMESTAMP, FALSE, NULL, NULL),
                            ('v52-abandoned', 'v52-dataset', 'v2', 2, 'v2',
                             'ABANDONED', 7, 7, CURRENT_TIMESTAMP,
                             CURRENT_TIMESTAMP, FALSE, NULL, NULL),
                            ('v52-reserved', 'v52-dataset', 'v3', 3, 'v3',
                             'ARCHIVED', 7, 7, CURRENT_TIMESTAMP,
                             CURRENT_TIMESTAMP, TRUE, CURRENT_TIMESTAMP, NULL)
                        """);
                statement.executeUpdate("""
                        INSERT INTO dataset_workspace_audit_log (
                            id, dataset_asset_id, dataset_version_id,
                            operation, actor_type, owner_user_id, created_at
                        )
                        VALUES (
                            'v52-audit', 'v52-dataset', 'v52-abandoned',
                            'WORKSPACE_ABANDONED', 'USER', 7, CURRENT_TIMESTAMP
                        )
                        """);
            }

            migrateSchema(schema, null);

            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword()
            );
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet result = statement.executeQuery("""
                        SELECT deleted, deleted_at IS NOT NULL, active_draft_asset_id
                        FROM dataset_version
                        WHERE id = 'v52-abandoned'
                        """)) {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1));
                    assertTrue(result.getBoolean(2));
                    assertEquals(null, result.getString(3));
                }
                try (ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*), COUNT(normalized_name)
                        FROM code_asset
                        WHERE owner_user_id = 7
                          AND deleted = FALSE
                          AND normalize_asset_name(name) = 'trainer'
                        """)) {
                    assertTrue(result.next());
                    assertEquals(2L, result.getLong(1));
                    assertEquals(1L, result.getLong(2));
                }

                SQLException duplicateCode = assertThrows(
                        SQLException.class,
                        () -> statement.executeUpdate("""
                                INSERT INTO code_asset (
                                    id, name, owner_user_id,
                                    created_at, updated_at, deleted
                                )
                                VALUES (
                                    'v52-code-new', ' TRAINER ', 7,
                                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE
                                )
                                """)
                );
                assertEquals(UNIQUE_VIOLATION, duplicateCode.getSQLState());
                assertTrue(lowerCaseMessage(duplicateCode).contains(
                        "uk_code_asset_owner_normalized_name"
                ));

                statement.executeUpdate("""
                        INSERT INTO code_asset (
                            id, name, owner_user_id,
                            created_at, updated_at, deleted
                        )
                        VALUES
                            ('v52-code-other-owner', 'TRAINER', 8,
                             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
                            ('v52-code-reused', ' reusable ', 7,
                             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                        """);

                SQLException blankCode = assertThrows(
                        SQLException.class,
                        () -> statement.executeUpdate("""
                                INSERT INTO code_asset (
                                    id, name, owner_user_id,
                                    created_at, updated_at, deleted
                                )
                                VALUES (
                                    'v52-code-blank', E'\\t\\r\\n', 7,
                                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE
                                )
                                """)
                );
                assertEquals(CHECK_VIOLATION, blankCode.getSQLState());
                assertTrue(lowerCaseMessage(blankCode).contains(
                        "ck_code_asset_name_not_blank"
                ));

                statement.executeUpdate("""
                        INSERT INTO dataset_version (
                            id, asset_id, version, version_no, version_label,
                            status, owner_user_id, created_by,
                            created_at, updated_at, deleted,
                            active_draft_asset_id
                        )
                        VALUES (
                            'v52-reused', 'v52-dataset', 'v2', 2, 'v2',
                            'READY', 7, 7, CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP, FALSE, NULL
                        )
                        """);

                SQLException reservedLabel = assertThrows(
                        SQLException.class,
                        () -> statement.executeUpdate("""
                                INSERT INTO dataset_version (
                                    id, asset_id, version, version_no, version_label,
                                    status, owner_user_id, created_by,
                                    created_at, updated_at, deleted,
                                    active_draft_asset_id
                                )
                                VALUES (
                                    'v52-label-conflict', 'v52-dataset',
                                    'v3', 4, 'v3', 'READY', 7, 7,
                                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                                    FALSE, NULL
                                )
                                """)
                );
                assertEquals(UNIQUE_VIOLATION, reservedLabel.getSQLState());
                assertTrue(lowerCaseMessage(reservedLabel).contains(
                        "uk_dataset_version_asset_version"
                ));

                SQLException reservedNumber = assertThrows(
                        SQLException.class,
                        () -> statement.executeUpdate("""
                                INSERT INTO dataset_version (
                                    id, asset_id, version, version_no, version_label,
                                    status, owner_user_id, created_by,
                                    created_at, updated_at, deleted,
                                    active_draft_asset_id
                                )
                                VALUES (
                                    'v52-number-conflict', 'v52-dataset',
                                    'v4', 3, 'v4', 'READY', 7, 7,
                                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                                    FALSE, NULL
                                )
                                """)
                );
                assertEquals(UNIQUE_VIOLATION, reservedNumber.getSQLState());
                assertTrue(lowerCaseMessage(reservedNumber).contains(
                        "uk_dataset_version_asset_version_no"
                ));

                try (ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*)
                        FROM dataset_workspace_audit_log
                        WHERE id = 'v52-audit'
                          AND dataset_version_id = 'v52-abandoned'
                        """)) {
                    assertTrue(result.next());
                    assertEquals(1L, result.getLong(1));
                }
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword()
            );
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void migrateSchema(
            String schema,
            MigrationVersion target
    ) {
        var configuration = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    @Test
    void assetNameConstraintsRejectBlankNamesAndSerializeRapidDuplicates()
            throws Exception {
        assertCheckViolation(
                "ck_model_asset_name_not_blank",
                """
                        INSERT INTO model_asset (
                            id, name, type, owner_user_id,
                            created_at, updated_at, deleted
                        )
                        VALUES (?, '   ', 'CV', 7,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                        """,
                id("blank-model")
        );
        assertCheckViolation(
                "ck_dataset_asset_name_not_blank",
                """
                        INSERT INTO dataset_asset (
                            id, name, type, owner_user_id,
                            created_at, updated_at, deleted
                        )
                        VALUES (?, E'\\t\\r\\n', 'NLP', 7,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                        """,
                id("blank-dataset")
        );

        assertOneRapidAssetInsert(
                "model_asset",
                "uk_model_asset_owner_normalized_name",
                "Rapid Model " + UUID.randomUUID()
        );
        assertOneRapidAssetInsert(
                "dataset_asset",
                "uk_dataset_asset_owner_normalized_name",
                "Rapid Dataset " + UUID.randomUUID()
        );
        assertOneRapidAssetInsert(
                "code_asset",
                "uk_code_asset_owner_normalized_name",
                "Rapid Code " + UUID.randomUUID()
        );
    }

    @Test
    void partialUniqueIndexAllowsOnlyOneConcurrentOpenWorkspacePerAsset() throws Exception {
        String assetId = id("asset-open-race");
        insertAsset(assetId);
        insertWorkspace(
                id("workspace-published"),
                assetId,
                "PUBLISHED",
                1L,
                7
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<InsertAttempt> first = executor.submit(() -> concurrentOpenInsert(
                id("workspace-open-a"), assetId, ready, start
        ));
        Future<InsertAttempt> second = executor.submit(() -> concurrentOpenInsert(
                id("workspace-open-b"), assetId, ready, start
        ));

        List<InsertAttempt> attempts;
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "insert workers did not become ready");
            start.countDown();
            attempts = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertTrue(
                executor.awaitTermination(5, TimeUnit.SECONDS),
                "insert workers did not terminate"
        );

        assertEquals(1L, attempts.stream().filter(InsertAttempt::inserted).count());
        InsertAttempt rejected = attempts.stream()
                .filter(attempt -> !attempt.inserted())
                .findFirst()
                .orElseThrow();
        assertEquals(UNIQUE_VIOLATION, rejected.sqlState());
        assertTrue(
                rejected.message().contains("uk_code_workspace_open_asset"),
                rejected.message()
        );
        assertEquals(1L, queryLong("""
                SELECT COUNT(*)
                FROM code_workspace
                WHERE asset_id = ? AND status = 'OPEN' AND deleted = FALSE
                """, assetId));
        assertEquals(2L, queryLong(
                "SELECT COUNT(*) FROM code_workspace WHERE asset_id = ?",
                assetId
        ));
    }

    @Test
    void v29LifecycleChecksRejectInvalidStatusesAndRevision() throws SQLException {
        String assetId = id("asset-checks");
        String versionId = id("version-checks");
        insertAsset(assetId);
        insertVersion(versionId, assetId);

        assertCheckViolation(
                "ck_code_version_status",
                """
                        INSERT INTO code_version (
                            id, asset_id, version, status, approval_status,
                            validation_status, owner_user_id, deleted
                        )
                        VALUES (?, ?, ?, 'DRAFT', 'PENDING', 'NOT_RUN', 7, FALSE)
                        """,
                id("bad-version-status"), assetId, id("label")
        );
        assertCheckViolation(
                "ck_code_version_validation_status",
                """
                        INSERT INTO code_version (
                            id, asset_id, version, status, approval_status,
                            validation_status, owner_user_id, deleted
                        )
                        VALUES (?, ?, ?, 'READY', 'PENDING', 'RUNNING', 7, FALSE)
                        """,
                id("bad-validation-status"), assetId, id("label")
        );
        assertCheckViolation(
                "ck_code_version_approval_status",
                """
                        INSERT INTO code_version (
                            id, asset_id, version, status, approval_status,
                            validation_status, owner_user_id, deleted
                        )
                        VALUES (?, ?, ?, 'READY', 'UNKNOWN', 'NOT_RUN', 7, FALSE)
                        """,
                id("bad-approval-status"), assetId, id("label")
        );
        assertCheckViolation(
                "ck_code_workspace_status",
                INSERT_WORKSPACE_SQL,
                id("bad-workspace-status"), assetId, "DRAFT", 0L, 7
        );
        assertCheckViolation(
                "ck_code_workspace_revision",
                INSERT_WORKSPACE_SQL,
                id("bad-workspace-revision"), assetId, "OPEN", -1L, 7
        );
        assertCheckViolation(
                "ck_code_validation_run_status",
                """
                        INSERT INTO code_validation_run (
                            id, version_id, artifact_sha256, policy_version, status,
                            requested_by_user_id, created_at
                        )
                        VALUES (?, ?, repeat('a', 64), 'policy-v1', 'RUNNING', 7,
                                CURRENT_TIMESTAMP)
                        """,
                id("bad-validation-run"), versionId
        );
        assertCheckViolation(
                "ck_code_approval_record_decision",
                """
                        INSERT INTO code_approval_record (
                            id, version_id, decision, reviewer_user_id, created_at
                        )
                        VALUES (?, ?, 'UNKNOWN', 7, CURRENT_TIMESTAMP)
                        """,
                id("bad-approval-decision"), versionId
        );
    }

    @Test
    void v29ForeignKeysRejectOrphansAcrossTheCodeAssetAggregate() throws SQLException {
        String assetId = id("asset-fk");
        String versionId = id("version-fk");
        insertAsset(assetId);
        insertVersion(versionId, assetId);

        assertForeignKeyViolation(
                "fk_code_version_asset",
                """
                        INSERT INTO code_version (
                            id, asset_id, version, status, approval_status,
                            validation_status, deleted
                        )
                        VALUES (?, ?, ?, 'READY', 'PENDING', 'NOT_RUN', FALSE)
                        """,
                id("orphan-version"), id("missing-asset"), id("label")
        );
        assertForeignKeyViolation(
                "fk_code_workspace_asset",
                INSERT_WORKSPACE_SQL,
                id("orphan-workspace"), id("missing-asset"), "PUBLISHED", 0L, 7
        );
        assertForeignKeyViolation(
                "fk_code_workspace_base_version",
                """
                        INSERT INTO code_workspace (
                            id, asset_id, base_version_id, status, revision,
                            owner_user_id, created_at, updated_at, deleted
                        )
                        VALUES (?, ?, ?, 'PUBLISHED', 0, 7,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                        """,
                id("orphan-base-workspace"), assetId, id("missing-version")
        );
        assertForeignKeyViolation(
                "fk_code_workspace_closed_version",
                """
                        INSERT INTO code_workspace (
                            id, asset_id, closed_version_id, status, revision,
                            owner_user_id, created_at, updated_at, deleted
                        )
                        VALUES (?, ?, ?, 'PUBLISHED', 0, 7,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                        """,
                id("orphan-closed-workspace"), assetId, id("missing-version")
        );
        assertForeignKeyViolation(
                "fk_code_workspace_file_delta_workspace",
                """
                        INSERT INTO code_workspace_file_delta (
                            id, workspace_id, path, operation, created_at, updated_at
                        )
                        VALUES (?, ?, 'src/deleted.py', 'DELETE',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                id("orphan-delta"), id("missing-workspace")
        );
        assertForeignKeyViolation(
                "fk_code_validation_run_version",
                """
                        INSERT INTO code_validation_run (
                            id, version_id, artifact_sha256, policy_version, status,
                            requested_by_user_id, created_at
                        )
                        VALUES (?, ?, repeat('a', 64), 'policy-v1', 'NOT_RUN', 7,
                                CURRENT_TIMESTAMP)
                        """,
                id("orphan-validation"), id("missing-version")
        );
        assertForeignKeyViolation(
                "fk_code_approval_record_version",
                """
                        INSERT INTO code_approval_record (
                            id, version_id, decision, reviewer_user_id, created_at
                        )
                        VALUES (?, ?, 'PENDING', 7, CURRENT_TIMESTAMP)
                        """,
                id("orphan-approval-version"), id("missing-version")
        );
        assertForeignKeyViolation(
                "fk_code_approval_record_validation_run",
                """
                        INSERT INTO code_approval_record (
                            id, version_id, validation_run_id, decision,
                            reviewer_user_id, created_at
                        )
                        VALUES (?, ?, ?, 'PENDING', 7, CURRENT_TIMESTAMP)
                        """,
                id("orphan-approval-run"), versionId, id("missing-validation")
        );
        assertForeignKeyViolation(
                "fk_code_asset_audit_log_asset",
                """
                        INSERT INTO code_asset_audit_log (
                            id, asset_id, action, actor_user_id, created_at
                        )
                        VALUES (?, ?, 'TEST', 7, CURRENT_TIMESTAMP)
                        """,
                id("orphan-audit-asset"), id("missing-asset")
        );
        assertForeignKeyViolation(
                "fk_code_asset_audit_log_version",
                """
                        INSERT INTO code_asset_audit_log (
                            id, asset_id, version_id, action, actor_user_id, created_at
                        )
                        VALUES (?, ?, ?, 'TEST', 7, CURRENT_TIMESTAMP)
                        """,
                id("orphan-audit-version"), assetId, id("missing-version")
        );
        assertForeignKeyViolation(
                "fk_code_asset_audit_log_workspace",
                """
                        INSERT INTO code_asset_audit_log (
                            id, asset_id, workspace_id, action, actor_user_id, created_at
                        )
                        VALUES (?, ?, ?, 'TEST', 7, CURRENT_TIMESTAMP)
                        """,
                id("orphan-audit-workspace"), assetId, id("missing-workspace")
        );
    }

    @Test
    void v30PersistsRiskEvidenceAutomaticDecisionsAndSystemAuditActors()
            throws SQLException {
        String assetId = id("risk-asset");
        String versionId = id("risk-version");
        String validationId = id("risk-validation");
        String lowAssessmentId = id("risk-low");
        String blockAssessmentId = id("risk-block");
        String artifactSha256 = "a".repeat(64);

        insertAsset(assetId);
        insertVersion(versionId, assetId);
        executeUpdate(
                "UPDATE code_version SET artifact_sha256 = ? WHERE id = ?",
                artifactSha256,
                versionId
        );
        executeUpdate("""
                INSERT INTO code_validation_run (
                    id, version_id, artifact_sha256, policy_version, status,
                    requested_by_user_id, created_at, completed_at
                )
                VALUES (?, ?, ?, 'validation-v1', 'PASSED', 7,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, validationId, versionId, artifactSha256);

        executeUpdate("""
                INSERT INTO code_risk_assessment (
                    id, version_id, validation_run_id, artifact_sha256,
                    risk_policy_version, scanner_version, status, risk_level,
                    disposition, finding_count, requested_by_user_id,
                    created_at, started_at, completed_at
                )
                VALUES (?, ?, ?, ?, 'risk-v1', 'scanner-v1', 'COMPLETED', 'LOW',
                        'AUTO_APPROVE', 1, NULL,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, lowAssessmentId, versionId, validationId, artifactSha256);
        executeUpdate("""
                INSERT INTO code_risk_finding (
                    id, risk_assessment_id, rule_id, severity, category,
                    file_path, line_start, line_end, description, created_at
                )
                VALUES (?, ?, 'PYTHON_NETWORK', 'LOW', 'NETWORK',
                        'src/train.py', 12, 12,
                        'Network import was classified by policy', CURRENT_TIMESTAMP)
                """, id("risk-finding"), lowAssessmentId);
        executeUpdate("""
                UPDATE code_version
                SET latest_risk_assessment_id = ?,
                    risk_status = 'COMPLETED',
                    risk_level = 'LOW',
                    review_disposition = 'AUTO_APPROVE',
                    risk_policy_version = 'risk-v1'
                WHERE id = ?
                """, lowAssessmentId, versionId);
        executeUpdate("""
                INSERT INTO code_approval_record (
                    id, version_id, artifact_sha256, validation_run_id,
                    policy_version, decision, decision_source,
                    risk_assessment_id, approval_policy_version,
                    reviewer_user_id, created_at
                )
                VALUES (?, ?, ?, ?, 'validation-v1', 'APPROVED', 'AUTO_POLICY',
                        ?, 'approval-v1', NULL, CURRENT_TIMESTAMP)
                """, id("risk-auto-approve"), versionId, artifactSha256,
                validationId, lowAssessmentId);

        executeUpdate("""
                INSERT INTO code_risk_assessment (
                    id, version_id, validation_run_id, artifact_sha256,
                    risk_policy_version, scanner_version, status, risk_level,
                    disposition, finding_count, created_at, started_at, completed_at
                )
                VALUES (?, ?, ?, ?, 'risk-v1', 'scanner-v1', 'COMPLETED', 'HIGH',
                        'BLOCK', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP)
                """, blockAssessmentId, versionId, validationId, artifactSha256);
        executeUpdate("""
                INSERT INTO code_approval_record (
                    id, version_id, artifact_sha256, validation_run_id,
                    policy_version, decision, decision_source,
                    risk_assessment_id, approval_policy_version,
                    reviewer_user_id, created_at
                )
                VALUES (?, ?, ?, ?, 'validation-v1', 'REJECTED', 'AUTO_POLICY',
                        ?, 'approval-v1', NULL, CURRENT_TIMESTAMP)
                """, id("risk-auto-reject"), versionId, artifactSha256,
                validationId, blockAssessmentId);

        executeUpdate("""
                INSERT INTO code_asset_audit_log (
                    id, asset_id, version_id, action, actor_type,
                    actor_user_id, created_at
                )
                VALUES (?, ?, ?, 'AUTO_RISK_DECISION', 'SYSTEM', NULL,
                        CURRENT_TIMESTAMP)
                """, id("risk-system-audit"), assetId, versionId);

        assertEquals(2L, queryLong(
                "SELECT COUNT(*) FROM code_risk_assessment WHERE version_id = ?",
                versionId
        ));
        assertEquals(2L, queryLong("""
                SELECT COUNT(*)
                FROM code_approval_record
                WHERE version_id = ? AND decision_source = 'AUTO_POLICY'
                  AND reviewer_user_id IS NULL
                """, versionId));
        assertEquals(1L, queryLong("""
                SELECT COUNT(*)
                FROM code_asset_audit_log
                WHERE version_id = ? AND actor_type = 'SYSTEM'
                  AND actor_user_id IS NULL
                """, versionId));
    }

    @Test
    void v42PersistsDirectPassConfigurationAndApprovalEvidence()
            throws SQLException {
        String assetId = id("direct-pass-asset");
        String versionId = id("direct-pass-version");
        String validationId = id("direct-pass-validation");
        String assessmentId = id("direct-pass-risk");
        String artifactSha256 = "d".repeat(64);

        insertAsset(assetId);
        insertVersion(versionId, assetId);
        executeUpdate("""
                UPDATE code_version
                SET artifact_sha256 = ?,
                    validation_status = 'PASSED',
                    validation_policy_version = 'code-asset-policy-v1'
                WHERE id = ?
                """, artifactSha256, versionId);
        executeUpdate("""
                INSERT INTO code_validation_run (
                    id, version_id, artifact_sha256, policy_version, status,
                    requested_by_user_id, created_at, completed_at
                )
                VALUES (?, ?, ?, 'code-asset-policy-v1', 'PASSED', 7,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, validationId, versionId, artifactSha256);
        executeUpdate("""
                INSERT INTO code_risk_assessment (
                    id, version_id, validation_run_id, artifact_sha256,
                    risk_policy_version, scanner_version, status, risk_level,
                    disposition, finding_count, requested_by_user_id,
                    created_at, completed_at
                )
                VALUES (?, ?, ?, ?,
                        'training-code-direct-pass-v1',
                        'not-run-direct-pass',
                        'COMPLETED', 'UNKNOWN', 'DIRECT_PASS', 0, 7,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, assessmentId, versionId, validationId, artifactSha256);
        executeUpdate("""
                UPDATE code_version
                SET approval_status = 'APPROVED',
                    latest_risk_assessment_id = ?,
                    risk_status = 'COMPLETED',
                    risk_level = 'UNKNOWN',
                    review_disposition = 'DIRECT_PASS',
                    risk_policy_version = 'training-code-direct-pass-v1'
                WHERE id = ?
                """, assessmentId, versionId);
        executeUpdate("""
                INSERT INTO code_approval_record (
                    id, version_id, artifact_sha256, validation_run_id,
                    policy_version, decision, decision_source,
                    risk_assessment_id, approval_policy_version,
                    reviewer_user_id, reason, created_at
                )
                VALUES (?, ?, ?, ?, 'code-asset-policy-v1',
                        'APPROVED', 'SYSTEM_CONFIG', ?,
                        'training-code-direct-pass-approval-v1',
                        NULL, 'Approved without code review', CURRENT_TIMESTAMP)
                """, id("direct-pass-approval"), versionId, artifactSha256,
                validationId, assessmentId);
        executeUpdate("""
                UPDATE platform_system_config
                SET training_code_review_mode = 'DIRECT_PASS',
                    updated_by_user_id = 7,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = 'GLOBAL'
                """);

        assertEquals(1L, queryLong("""
                SELECT COUNT(*)
                FROM code_approval_record
                WHERE version_id = ?
                  AND decision_source = 'SYSTEM_CONFIG'
                  AND risk_assessment_id = ?
                """, versionId, assessmentId));
        assertEquals(
                List.of("DIRECT_PASS"),
                queryStrings("""
                        SELECT training_code_review_mode
                        FROM platform_system_config
                        WHERE id = 'GLOBAL'
                        """)
        );
        assertCheckViolation(
                "ck_platform_system_config_training_code_review_mode",
                """
                        UPDATE platform_system_config
                        SET training_code_review_mode = 'SHADOW'
                        WHERE id = 'GLOBAL'
                        """
        );
        assertCheckViolation(
                "ck_code_approval_record_reviewer",
                """
                        INSERT INTO code_approval_record (
                            id, version_id, artifact_sha256, validation_run_id,
                            policy_version, decision, decision_source,
                            risk_assessment_id, approval_policy_version,
                            reviewer_user_id, created_at
                        )
                        VALUES (?, ?, ?, ?, 'code-asset-policy-v1',
                                'APPROVED', 'SYSTEM_CONFIG', NULL,
                                'training-code-direct-pass-approval-v1',
                                NULL, CURRENT_TIMESTAMP)
                        """,
                id("bad-direct-pass-approval"),
                versionId,
                artifactSha256,
                validationId
        );
    }

    @Test
    void v30RejectsMismatchedEvidenceInvalidRiskStatesAndSpoofedActors()
            throws SQLException {
        String assetId = id("bad-risk-asset");
        String versionId = id("bad-risk-version");
        String validationId = id("bad-risk-validation");

        insertAsset(assetId);
        insertVersion(versionId, assetId);
        executeUpdate("""
                INSERT INTO code_validation_run (
                    id, version_id, artifact_sha256, policy_version, status,
                    requested_by_user_id, created_at, completed_at
                )
                VALUES (?, ?, repeat('a', 64), 'validation-v1', 'PASSED', 7,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, validationId, versionId);

        assertForeignKeyViolation(
                "fk_code_risk_assessment_validation_evidence",
                """
                        INSERT INTO code_risk_assessment (
                            id, version_id, validation_run_id, artifact_sha256,
                            risk_policy_version, scanner_version, created_at
                        )
                        VALUES (?, ?, ?, repeat('b', 64),
                                'risk-v1', 'scanner-v1', CURRENT_TIMESTAMP)
                        """,
                id("bad-risk-hash"), versionId, validationId
        );
        assertCheckViolation(
                "ck_code_risk_assessment_status",
                """
                        INSERT INTO code_risk_assessment (
                            id, version_id, validation_run_id, artifact_sha256,
                            risk_policy_version, scanner_version, status, created_at
                        )
                        VALUES (?, ?, ?, repeat('a', 64),
                                'risk-v1', 'scanner-v1', 'WAITING', CURRENT_TIMESTAMP)
                        """,
                id("bad-risk-status"), versionId, validationId
        );
        assertCheckViolation(
                "ck_code_risk_assessment_completed_disposition",
                """
                        INSERT INTO code_risk_assessment (
                            id, version_id, validation_run_id, artifact_sha256,
                            risk_policy_version, scanner_version, status,
                            completed_at, created_at
                        )
                        VALUES (?, ?, ?, repeat('a', 64),
                                'risk-v1', 'scanner-v1', 'COMPLETED',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                id("bad-risk-complete"), versionId, validationId
        );
        assertCheckViolation(
                "ck_code_approval_record_reviewer",
                """
                        INSERT INTO code_approval_record (
                            id, version_id, decision, decision_source,
                            reviewer_user_id, created_at
                        )
                        VALUES (?, ?, 'APPROVED', 'AUTO_POLICY', NULL,
                                CURRENT_TIMESTAMP)
                        """,
                id("bad-auto-decision"), versionId
        );
        assertCheckViolation(
                "ck_code_asset_audit_log_actor",
                """
                        INSERT INTO code_asset_audit_log (
                            id, asset_id, action, actor_type,
                            actor_user_id, created_at
                        )
                        VALUES (?, ?, 'SPOOFED_SYSTEM', 'SYSTEM', 7,
                                CURRENT_TIMESTAMP)
                        """,
                id("bad-system-actor"), assetId
        );
        assertCheckViolation(
                "ck_code_asset_audit_log_actor",
                """
                        INSERT INTO code_asset_audit_log (
                            id, asset_id, action, actor_type,
                            actor_user_id, created_at
                        )
                        VALUES (?, ?, 'MISSING_USER', 'USER', NULL,
                                CURRENT_TIMESTAMP)
                        """,
                id("bad-user-actor"), assetId
        );
        String adminAuditId = id("valid-admin-actor");
        executeUpdate(
                """
                        INSERT INTO code_asset_audit_log (
                            id, asset_id, action, actor_type,
                            actor_user_id, created_at
                        )
                        VALUES (?, ?, 'ADMIN_UPDATE', 'ADMIN', 99,
                                CURRENT_TIMESTAMP)
                        """,
                adminAuditId, assetId
        );
        assertEquals(1L, queryLong(
                """
                        SELECT COUNT(*)
                        FROM code_asset_audit_log
                        WHERE id = ? AND actor_type = 'ADMIN'
                          AND actor_user_id = 99
                        """,
                adminAuditId
        ));
    }

    @Test
    void assetRowLockSerializesTheOpenWorkspaceDecision() throws SQLException {
        String assetId = id("asset-lock-race");
        String workspaceId = id("workspace-lock-winner");
        insertAsset(assetId);

        try (Connection winner = connection(); Connection contender = connection()) {
            winner.setAutoCommit(false);
            contender.setAutoCommit(false);

            lockAsset(winner, assetId);
            executeUpdate(
                    winner,
                    INSERT_WORKSPACE_SQL,
                    workspaceId, assetId, "OPEN", 0L, 7
            );
            assertLockUnavailable(contender, ASSET_LOCK_SQL, assetId);
            contender.rollback();
            winner.commit();
        }

        try (Connection retry = connection()) {
            retry.setAutoCommit(false);
            lockAsset(retry, assetId);
            WorkspaceState visibleWinner = openWorkspace(retry, assetId);

            assertNotNull(visibleWinner);
            assertEquals(workspaceId, visibleWinner.id());
            assertEquals("OPEN", visibleWinner.status());
            assertEquals(0L, visibleWinner.revision());
            retry.commit();
        }
    }

    @Test
    void v43AddsDatasetWorkspaceRevisionRawAndSoftDeleteContracts()
            throws SQLException {
        assertEquals(2L, queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'dataset_version'
                  AND column_name IN ('workspace_revision', 'updated_at')
                """));
        assertEquals(3L, queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'dataset_sample_data'
                  AND column_name IN ('deleted', 'deleted_at', 'updated_at')
                """));
        assertEquals(3L, queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'dataset_annotation'
                  AND column_name IN ('deleted', 'deleted_at', 'updated_at')
                """));
        assertEquals(1L, queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'dataset_package'
                  AND column_name = 'storage_kind'
                """));
        assertEquals(1L, queryLong("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'uk_sd_sample_dt_sc_seq_active'
                  AND indexdef ILIKE '%WHERE (deleted = false)%'
                """));

        List<String> workspaceConstraints = queryStrings("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                WHERE t.relname IN (
                    'dataset_version',
                    'dataset_package',
                    'dataset_version_package',
                    'dataset_upload_session'
                )
                """);
        assertTrue(workspaceConstraints.stream()
                .anyMatch(value -> value.contains("ABANDONED")));
        assertTrue(workspaceConstraints.stream()
                .anyMatch(value -> value.contains("RAW")));
        assertTrue(workspaceConstraints.stream()
                .anyMatch(value -> value.contains("OVERLAY")));
        assertTrue(workspaceConstraints.stream()
                .anyMatch(value -> value.contains("WORKSPACE_FILE")));
    }

    @Test
    void softDeletedDatasetVersionLabelRemainsReservedAndNextNumberIsUsable()
            throws SQLException {
        String assetId = id("dataset-label-asset");
        String visibleVersionId = id("dataset-version-visible");
        String deletedVersionId = id("dataset-version-deleted");
        String nextVersionId = id("dataset-version-next");

        executeUpdate("""
                INSERT INTO dataset_asset (
                    id, name, type, owner_user_id,
                    created_at, updated_at, deleted
                )
                VALUES (?, 'Version allocation test', 'NLP', 7,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                """, assetId);
        executeUpdate("""
                INSERT INTO dataset_version (
                    id, asset_id, version, version_no, version_label,
                    status, owner_user_id, created_at, updated_at, deleted
                )
                VALUES (?, ?, '1.0.1', 1, '1.0.1',
                        'READY', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                """, visibleVersionId, assetId);
        executeUpdate("""
                INSERT INTO dataset_version (
                    id, asset_id, version, version_no, version_label,
                    status, owner_user_id, created_at, updated_at,
                    deleted, deleted_at
                )
                VALUES (?, ?, '1.0.2', 2, '1.0.2',
                        'ARCHIVED', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        TRUE, CURRENT_TIMESTAMP)
                """, deletedVersionId, assetId);

        assertEquals(1L, queryLong("""
                SELECT COUNT(*)
                FROM dataset_version
                WHERE asset_id = ? AND deleted = FALSE
                """, assetId));
        assertEquals(2L, queryLong("""
                SELECT MAX(version_no)
                FROM dataset_version
                WHERE asset_id = ?
                """, assetId));

        SQLException duplicateLabel = assertThrows(
                SQLException.class,
                () -> executeUpdate("""
                        INSERT INTO dataset_version (
                            id, asset_id, version, version_no, version_label,
                            status, owner_user_id, created_at, updated_at, deleted
                        )
                        VALUES (?, ?, '1.0.2', 3, '1.0.2',
                                'READY', 7, CURRENT_TIMESTAMP,
                                CURRENT_TIMESTAMP, FALSE)
                        """, nextVersionId, assetId)
        );
        assertEquals(UNIQUE_VIOLATION, duplicateLabel.getSQLState());
        assertTrue(
                lowerCaseMessage(duplicateLabel).contains(
                        "uk_dataset_version_asset_version"
                ),
                duplicateLabel::getMessage
        );

        executeUpdate("""
                INSERT INTO dataset_version (
                    id, asset_id, version, version_no, version_label,
                    status, owner_user_id, created_at, updated_at, deleted
                )
                VALUES (?, ?, '1.0.3', 3, '1.0.3',
                        'READY', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                """, nextVersionId, assetId);

        assertEquals(3L, queryLong("""
                SELECT MAX(version_no)
                FROM dataset_version
                WHERE asset_id = ?
                """, assetId));
        assertEquals(1L, queryLong("""
                SELECT COUNT(*)
                FROM dataset_version
                WHERE asset_id = ?
                  AND version_no = 3
                  AND version_label = '1.0.3'
                  AND deleted = FALSE
                """, assetId));
    }

    @Test
    void workspaceRowLockMakesTheLosingTransitionObserveTheCommittedWinner() throws SQLException {
        String assetId = id("asset-workspace-lock");
        String workspaceId = id("workspace-transition");
        insertAsset(assetId);
        insertWorkspace(workspaceId, assetId, "OPEN", 0L, 7);

        try (Connection winner = connection(); Connection contender = connection()) {
            winner.setAutoCommit(false);
            contender.setAutoCommit(false);

            WorkspaceState initial = lockWorkspace(winner, workspaceId);
            assertEquals("OPEN", initial.status());
            assertEquals(0L, initial.revision());
            executeUpdate(winner, """
                    UPDATE code_workspace
                    SET status = 'PUBLISHED', revision = revision + 1,
                        updated_at = CURRENT_TIMESTAMP, closed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, workspaceId);

            assertLockUnavailable(contender, WORKSPACE_LOCK_SQL, workspaceId);
            contender.rollback();
            winner.commit();
        }

        try (Connection retry = connection()) {
            retry.setAutoCommit(false);
            WorkspaceState committedWinner = lockWorkspace(retry, workspaceId);

            assertEquals("PUBLISHED", committedWinner.status());
            assertEquals(1L, committedWinner.revision());
            retry.commit();
        }
    }

    private static void assertOneRapidAssetInsert(
            String table,
            String constraint,
            String displayName
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<InsertAttempt> first = executor.submit(() -> concurrentAssetInsert(
                table,
                id("rapid-asset-a"),
                displayName,
                ready,
                start
        ));
        Future<InsertAttempt> second = executor.submit(() -> concurrentAssetInsert(
                table,
                id("rapid-asset-b"),
                "  " + displayName.toUpperCase(Locale.ROOT) + "  ",
                ready,
                start
        ));
        List<InsertAttempt> attempts;
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            attempts = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1L, attempts.stream().filter(InsertAttempt::inserted).count());
        InsertAttempt rejected = attempts.stream()
                .filter(attempt -> !attempt.inserted())
                .findFirst()
                .orElseThrow();
        assertEquals(UNIQUE_VIOLATION, rejected.sqlState());
        assertTrue(rejected.message().contains(constraint), rejected.message());
        assertEquals(1L, queryLong(
                "SELECT COUNT(*) FROM " + table
                        + " WHERE owner_user_id = 7"
                        + " AND normalize_asset_name(name) = normalize_asset_name(?)"
                        + " AND deleted = FALSE",
                displayName
        ));
    }

    private static InsertAttempt concurrentAssetInsert(
            String table,
            String assetId,
            String name,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        if (!"model_asset".equals(table)
                && !"dataset_asset".equals(table)
                && !"code_asset".equals(table)) {
            throw new IllegalArgumentException("unsupported asset table");
        }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent insert start signal timed out");
            }
            try {
                String typeColumn = "code_asset".equals(table)
                        ? ""
                        : "type, ";
                String typeValue = "code_asset".equals(table)
                        ? ""
                        : "'CV', ";
                executeUpdate(
                        connection,
                        "INSERT INTO " + table + " ("
                                + "id, name, " + typeColumn + "owner_user_id, "
                                + "created_at, updated_at, deleted"
                                + ") VALUES (?, ?, " + typeValue + "7, "
                                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)",
                        assetId,
                        name
                );
                connection.commit();
                return new InsertAttempt(true, null, "");
            } catch (SQLException exception) {
                connection.rollback();
                return new InsertAttempt(
                        false,
                        exception.getSQLState(),
                        lowerCaseMessage(exception)
                );
            }
        }
    }

    private static InsertAttempt concurrentOpenInsert(
            String workspaceId,
            String assetId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent insert start signal timed out");
            }
            try {
                executeUpdate(
                        connection,
                        INSERT_WORKSPACE_SQL,
                        workspaceId, assetId, "OPEN", 0L, 7
                );
                connection.commit();
                return new InsertAttempt(true, null, "");
            } catch (SQLException exception) {
                connection.rollback();
                return new InsertAttempt(
                        false,
                        exception.getSQLState(),
                        lowerCaseMessage(exception)
                );
            }
        }
    }

    private static void insertAsset(String assetId) throws SQLException {
        executeUpdate("""
                INSERT INTO code_asset (
                    id, name, owner_user_id, created_at, updated_at, deleted
                )
                VALUES (?, ?, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                """, assetId, "Integration asset " + assetId);
    }

    private static void insertVersion(String versionId, String assetId) throws SQLException {
        executeUpdate("""
                INSERT INTO code_version (
                    id, asset_id, version, status, approval_status,
                    validation_status, owner_user_id, created_at, updated_at, deleted
                )
                VALUES (?, ?, ?, 'READY', 'PENDING', 'NOT_RUN', 7,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                """, versionId, assetId, id("label"));
    }

    private static void insertWorkspace(
            String workspaceId,
            String assetId,
            String status,
            long revision,
            int ownerUserId
    ) throws SQLException {
        executeUpdate(
                INSERT_WORKSPACE_SQL,
                workspaceId, assetId, status, revision, ownerUserId
        );
    }

    private static void assertCheckViolation(
            String constraint,
            String sql,
            Object... parameters
    ) {
        assertConstraintViolation(CHECK_VIOLATION, constraint, sql, parameters);
    }

    private static void assertForeignKeyViolation(
            String constraint,
            String sql,
            Object... parameters
    ) {
        assertConstraintViolation(FOREIGN_KEY_VIOLATION, constraint, sql, parameters);
    }

    private static void assertConstraintViolation(
            String sqlState,
            String constraint,
            String sql,
            Object... parameters
    ) {
        SQLException exception = assertThrows(
                SQLException.class,
                () -> executeUpdate(sql, parameters),
                () -> "expected constraint violation: " + constraint
        );
        assertEquals(sqlState, exception.getSQLState(), exception::getMessage);
        assertTrue(
                lowerCaseMessage(exception).contains(constraint.toLowerCase(Locale.ROOT)),
                exception::getMessage
        );
    }

    private static void assertLockUnavailable(
            Connection connection,
            String lockSql,
            String rowId
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '500ms'");
        }
        SQLException exception = assertThrows(SQLException.class, () -> {
            try (PreparedStatement statement = connection.prepareStatement(lockSql)) {
                statement.setString(1, rowId);
                try (ResultSet ignored = statement.executeQuery()) {
                    // The competing transaction owns the row lock, so this never yields a row.
                }
            }
        });
        assertEquals(LOCK_NOT_AVAILABLE, exception.getSQLState(), exception::getMessage);
    }

    private static void lockAsset(Connection connection, String assetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ASSET_LOCK_SQL)) {
            statement.setString(1, assetId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "asset row was not found for locking");
                assertEquals(assetId, result.getString("id"));
            }
        }
    }

    private static WorkspaceState lockWorkspace(
            Connection connection,
            String workspaceId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(WORKSPACE_LOCK_SQL)) {
            statement.setString(1, workspaceId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "workspace row was not found for locking");
                return workspaceState(result);
            }
        }
    }

    private static WorkspaceState openWorkspace(
            Connection connection,
            String assetId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, status, revision
                FROM code_workspace
                WHERE asset_id = ? AND status = 'OPEN' AND deleted = FALSE
                """)) {
            statement.setString(1, assetId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? workspaceState(result) : null;
            }
        }
    }

    private static WorkspaceState workspaceState(ResultSet result) throws SQLException {
        return new WorkspaceState(
                result.getString("id"),
                result.getString("status"),
                result.getLong("revision")
        );
    }

    private static void executeUpdate(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection()) {
            executeUpdate(connection, sql, parameters);
        }
    }

    private static void executeUpdate(
            Connection connection,
            String sql,
            Object... parameters
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private static long queryLong(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "aggregate query returned no row");
                return result.getLong(1);
            }
        }
    }

    private static List<String> queryStrings(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<String> values = new java.util.ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return List.copyOf(values);
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            Object parameter = parameters[index];
            if (parameter instanceof byte[] bytes) {
                statement.setBytes(index + 1, bytes);
            } else {
                statement.setObject(index + 1, parameter);
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String lowerCaseMessage(SQLException exception) {
        String message = exception.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    private record InsertAttempt(boolean inserted, String sqlState, String message) {
    }

    private record WorkspaceState(String id, String status, long revision) {
    }
}
