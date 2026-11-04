package com.tss.platform.repository;

import com.tss.platform.entity.MinioDeleteTask;
import com.tss.platform.service.MinioDeleteTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.show_sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class MinioDeleteTaskRepositoryTest {

    @Autowired
    private MinioDeleteTaskRepository repo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createMinioDeleteTaskTable() {
        jdbcTemplate.execute("drop table if exists minio_delete_task");
        jdbcTemplate.execute("""
                create table minio_delete_task (
                    id varchar(64) primary key,
                    bucket varchar(255) not null,
                    object_name varchar(1024) not null,
                    source_type varchar(64) not null,
                    source_id varchar(128),
                    owner_user_id integer,
                    status varchar(32) not null,
                    retry_count integer not null default 0,
                    max_retry_count integer not null default 5,
                    failed_reset_count integer not null default 0,
                    error_message text,
                    created_at timestamp with time zone,
                    updated_at timestamp with time zone,
                    last_retry_at timestamp with time zone,
                    completed_at timestamp with time zone
                )
                """);
    }

    @Test
    void resetFailedForRetryStopsAfterConfiguredResetLimit() {
        MinioDeleteTask retryable = failedTask("minio-del-retryable", 1);
        MinioDeleteTask exhausted = failedTask("minio-del-exhausted", 2);
        repo.save(retryable);
        repo.save(exhausted);
        repo.flush();

        int updated = repo.resetFailedForRetry(
                MinioDeleteTaskService.STATUS_FAILED,
                MinioDeleteTaskService.STATUS_PENDING,
                2,
                Instant.parse("2026-07-03T00:00:00Z")
        );
        repo.flush();

        MinioDeleteTask reloadedRetryable = repo.findById("minio-del-retryable").orElseThrow();
        MinioDeleteTask reloadedExhausted = repo.findById("minio-del-exhausted").orElseThrow();
        assertEquals(1, updated);
        assertEquals(MinioDeleteTaskService.STATUS_PENDING, reloadedRetryable.getStatus());
        assertEquals(0, reloadedRetryable.getRetryCount());
        assertEquals(2, reloadedRetryable.getFailedResetCount());
        assertEquals(MinioDeleteTaskService.STATUS_FAILED, reloadedExhausted.getStatus());
        assertEquals(5, reloadedExhausted.getRetryCount());
        assertEquals(2, reloadedExhausted.getFailedResetCount());
    }

    private MinioDeleteTask failedTask(String id, int failedResetCount) {
        Instant now = Instant.parse("2026-07-02T23:59:00Z");
        MinioDeleteTask task = new MinioDeleteTask();
        task.setId(id);
        task.setBucket("models");
        task.setObjectName(id + ".bin");
        task.setSourceType(MinioDeleteTaskService.SOURCE_FILE_OBJECT);
        task.setStatus(MinioDeleteTaskService.STATUS_FAILED);
        task.setRetryCount(5);
        task.setMaxRetryCount(5);
        task.setFailedResetCount(failedResetCount);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setLastRetryAt(now);
        return task;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = MinioDeleteTask.class)
    @EnableJpaRepositories(basePackageClasses = MinioDeleteTaskRepository.class)
    static class RepositoryTestConfig {
    }
}
