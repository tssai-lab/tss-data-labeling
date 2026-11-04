package com.tss.platform.controller.v2;

import java.util.Map;

public record V2ErrorResponse(
        boolean success,
        String errorCode,
        String errorMessage,
        Map<String, Object> details,
        String traceId
) {

    public static V2ErrorResponse failure(
            String errorCode,
            String errorMessage,
            Map<String, Object> details,
            String traceId
    ) {
        return new V2ErrorResponse(false, errorCode, errorMessage, details, traceId);
    }
}
