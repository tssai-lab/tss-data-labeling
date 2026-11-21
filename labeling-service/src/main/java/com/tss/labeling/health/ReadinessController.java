package com.tss.labeling.health;

import com.tss.labeling.ls.LsSourceAdapter;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Deployment readiness checks the database and the configured shipped source. */
@RestController
public class ReadinessController {
    private final JdbcTemplate db;
    private final LsSourceAdapter source;
    public ReadinessController(JdbcTemplate db, LsSourceAdapter source) {
        this.db = db; this.source = source;
    }

    @GetMapping("/api/labeling/v1/health/ready")
    public ResponseEntity<Map<String,String>> ready() {
        try {
            db.queryForObject("SELECT 1", Integer.class);
            source.inspect("<View><Text name=\"text\" value=\"$text\"/><Choices name=\"label\" toName=\"text\"><Choice value=\"ready\"/></Choices></View>");
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
        }
    }
}
