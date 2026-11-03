package com.tss.platform.service;

import java.util.Map;

public final class DatasetUploadCompletionException extends IllegalArgumentException {

    private final String reasonCode;
    private final Map<String, Object> details;

    public DatasetUploadCompletionException(
            String reasonCode,
            String userMessage,
            Map<String, Object> details
    ) {
        super(userMessage);
        this.reasonCode = reasonCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
