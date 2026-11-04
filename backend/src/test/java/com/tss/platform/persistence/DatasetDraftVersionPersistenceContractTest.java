package com.tss.platform.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetDraftVersionPersistenceContractTest {

    @Test
    void publishedV18RemainsOriginalAndV24CarriesActiveDraftColumnMigration() throws Exception {
        String v18 = migrationSql("db/migration/V18__dataset_version_one_active_draft.sql");
        assertTrue(v18.contains("ON dataset_version (asset_id)"));
        assertTrue(v18.contains("WHERE status = 'DRAFT' AND deleted = false"));
        assertFalse(v18.contains("active_draft_asset_id"));

        String v24 = migrationSql("db/migration/V24__dataset_version_active_draft_asset_id.sql");
        assertTrue(v24.contains("ADD COLUMN IF NOT EXISTS active_draft_asset_id"));
        assertTrue(v24.contains("ck_dataset_version_active_draft_asset_id"));
        assertTrue(v24.contains("DROP INDEX IF EXISTS uk_dataset_version_one_active_draft"));
        assertTrue(v24.contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_dataset_version_one_active_draft"));
        assertTrue(v24.contains("ON dataset_version (active_draft_asset_id)"));
    }

    @Test
    void h2PostgresqlModeV24RejectsDuplicateActiveDrafts() throws Exception {
        String sql = migrationSql("db/migration/V24__dataset_version_active_draft_asset_id.sql");

        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:dataset_draft_contract;MODE=PostgreSQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        );
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE dataset_version (
                        id VARCHAR(64) PRIMARY KEY,
                        asset_id VARCHAR(64) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        deleted BOOLEAN NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO dataset_version
                        (id, asset_id, status, deleted)
                    VALUES
                        ('draft-1', 'asset-1', 'DRAFT', false),
                        ('ready-1', 'asset-1', 'READY', false),
                        ('deleted-draft-1', 'asset-1', 'DRAFT', true)
                    """);
            executeStatements(statement, sql);

            try (var resultSet = statement.executeQuery("""
                    SELECT active_draft_asset_id
                    FROM dataset_version
                    WHERE id = 'draft-1'
                    """)) {
                assertTrue(resultSet.next());
                assertEquals("asset-1", resultSet.getString(1));
            }
            try (var resultSet = statement.executeQuery("""
                    SELECT active_draft_asset_id
                    FROM dataset_version
                    WHERE id = 'ready-1'
                    """)) {
                assertTrue(resultSet.next());
                assertNull(resultSet.getString(1));
            }

            statement.executeUpdate("""
                    INSERT INTO dataset_version
                        (id, asset_id, status, deleted, active_draft_asset_id)
                    VALUES ('ready-2', 'asset-1', 'READY', false, NULL)
                    """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO dataset_version
                        (id, asset_id, status, deleted, active_draft_asset_id)
                    VALUES ('draft-2', 'asset-1', 'DRAFT', false, 'asset-1')
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO dataset_version
                        (id, asset_id, status, deleted, active_draft_asset_id)
                    VALUES ('bad-draft', 'asset-2', 'DRAFT', false, NULL)
                    """));
        }
    }

    private static String migrationSql(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executeStatements(Statement statement, String sql) throws SQLException {
        for (String command : sql.split(";")) {
            if (!command.isBlank()) {
                statement.execute(command);
            }
        }
    }
}
