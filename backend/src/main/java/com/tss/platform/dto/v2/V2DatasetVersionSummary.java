package com.tss.platform.dto.v2;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class V2DatasetVersionSummary {
    private String versionId;
    private String versionLabel;
    private Integer versionNo;
    private String status;
}
