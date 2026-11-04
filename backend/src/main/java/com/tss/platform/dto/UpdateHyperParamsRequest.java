package com.tss.platform.dto;

import lombok.Data;

@Data
public class UpdateHyperParamsRequest {
    private Object hyperParams;
    private Object params;
    private String remark;
}
