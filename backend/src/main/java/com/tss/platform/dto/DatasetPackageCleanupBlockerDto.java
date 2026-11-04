package com.tss.platform.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DatasetPackageCleanupBlockerDto {

    private String code;
    private String message;
    private String referenceType;
    private String referenceId;
}
