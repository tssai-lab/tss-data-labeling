package com.tss.labeling.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class ImportWorker {
    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);
    private final JdbcTemplate db;
    private final ImportService service;
    private final ImportSettings settings;

    public ImportWorker(JdbcTemplate db, ImportService service, ImportSettings settings) {
        this.db = db; this.service = service; this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${labeling.imports.poll-delay-ms:1000}")
    public void poll() {
        if (settings.workerEnabled()) runOnce();
    }

    /** One bounded queue sweep; persisted PROCESSING rows survive process restarts. */
    public void runOnce() {
        var batches = db.query("""
                SELECT b.id FROM import_batch b JOIN project p ON p.id = b.project_id
                JOIN actor a ON a.id = p.owner_actor_id
                WHERE b.status = 'PROCESSING' AND p.status IN ('DRAFT', 'OPEN') AND a.status = 'ENABLED'
                ORDER BY b.created_at, b.id LIMIT 10
                """, (row, number) -> row.getObject(1, UUID.class));
        for (UUID batch : batches) {
            try { service.processBatch(batch); }
            catch (Exception exception) { log.error("导入事务失败，可恢复 batchId={}", batch, exception); }
        }
    }
}
