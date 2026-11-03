package com.tss.platform.dto.v2;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class V2UserError {
    private String errorCode;
    private String errorMessage;
    private Map<String, Object> details;
}
