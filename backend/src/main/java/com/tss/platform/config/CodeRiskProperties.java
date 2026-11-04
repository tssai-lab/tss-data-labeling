package com.tss.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "code-assets.risk")
public class CodeRiskProperties {

    public enum Mode {
        MANUAL_ONLY,
        SHADOW,
        ENFORCE
    }

    private Mode mode = Mode.MANUAL_ONLY;
    private int batchSize = 20;
    private long staleAfterSeconds = 600;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.MANUAL_ONLY : mode;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    public long getStaleAfterSeconds() {
        return staleAfterSeconds;
    }

    public void setStaleAfterSeconds(long staleAfterSeconds) {
        this.staleAfterSeconds = Math.max(60, staleAfterSeconds);
    }
}
