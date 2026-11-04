package com.tss.platform.dto.v2;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class V2DatasetPreviewDescriptor {
    private String datasetVersionId;
    private String mode;
    private List<String> capabilities;
    private Map<String, String> links;
}
