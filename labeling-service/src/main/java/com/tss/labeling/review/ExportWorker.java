package com.tss.labeling.review;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ExportWorker {
    private final JdbcTemplate db;
    private final ExportService exports;
    private final boolean enabled;

    public ExportWorker(
            JdbcTemplate db,
            ExportService exports,
            @Value("${labeling.exports.worker-enabled:true}") boolean enabled) {
        this.db = db;
        this.exports = exports;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${labeling.exports.poll-delay-ms:1000}")
    public void poll() {
        if (enabled) runOnce();
    }

    public void runOnce() {
        for (UUID id :
                db.query(
                        "SELECT id FROM export_job WHERE status='QUEUED' AND"
                            + " expires_at>CURRENT_TIMESTAMP ORDER BY created_at,id LIMIT 5",
                        (r, n) -> r.getObject(1, UUID.class))) {
            try {
                exports.process(id);
            } catch (Exception ex) {
                LoggerFactory.getLogger(getClass()).error("Export transaction failed: {}", id, ex);
            }
        }
    }
}
