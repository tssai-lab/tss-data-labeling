package com.tss.platform.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class DatasetPackageCleanupPlanDto {

    private String packageId;
    private String datasetAssetId;
    private String storagePath;
    private boolean canDelete;
    private boolean enqueueRequested;
    private boolean enqueued;
    private String minioDeleteTaskId;
    private List<String> referencedVersionIds = new ArrayList<>();
    private List<DatasetPackageCleanupBlockerDto> blockers = new ArrayList<>();
}
