package com.tss.platform.service;

import com.tss.platform.dto.v2.V2DatasetEditability;
import com.tss.platform.dto.v2.V2DatasetPublishBlocker;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class DatasetWorkspaceSourceInspector {

    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;

    public DatasetWorkspaceSourceInspector(
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo
    ) {
        this.versionPackageRepo = versionPackageRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
    }

    @Transactional(readOnly = true)
    public V2DatasetEditability inspect(
            DatasetAsset asset,
            DatasetVersion ready
    ) {
        if (ready == null
                || !"READY".equals(ready.getStatus())
                || asset == null
                || !asset.getId().equals(ready.getAssetId())) {
            return blocked(
                    "READY_VERSION_REQUIRED",
                    "数据集需要一个当前 READY 版本才能创建版本工作区"
            );
        }
        if (!versionPackageRepo
                .findByDatasetVersionIdOrderByPackageOrderAsc(ready.getId())
                .isEmpty()) {
            return editable();
        }
        if (ready.getStoragePath() == null
                || ready.getStoragePath().isBlank()) {
            return ambiguous();
        }
        String sourceName = ready.getFileName() == null
                || ready.getFileName().isBlank()
                ? ready.getStoragePath()
                : ready.getFileName();
        String taskType;
        try {
            taskType = DatasetTaskType.normalize(asset.getType());
        } catch (IllegalArgumentException exception) {
            return blocked(
                    "DATASET_TYPE_INVALID",
                    "数据集任务类型无效，无法创建版本工作区"
            );
        }
        if (!"MULTIMODAL".equals(taskType)
                && sourceName.toLowerCase(Locale.ROOT).endsWith(".zip")
                && ready.getSizeBytes() != null) {
            return editable();
        }
        if ("MULTIMODAL".equals(taskType)) {
            return ambiguous();
        }
        long samples = sampleRepo
                .countByDatasetVersionIdAndDeletedFalse(ready.getId());
        long data = dataRepo
                .countByDatasetVersionIdAndDeletedFalse(ready.getId());
        long annotations = annotationRepo
                .countByDatasetVersionIdAndDeletedFalse(ready.getId());
        boolean emptyMetadata = samples == 0 && data == 0 && annotations == 0;
        boolean uniqueMapping = samples == 1 && data == 1 && annotations == 0;
        return emptyMetadata || uniqueMapping ? editable() : ambiguous();
    }

    private static V2DatasetEditability editable() {
        return new V2DatasetEditability(true, List.of());
    }

    private static V2DatasetEditability ambiguous() {
        return blocked(
                "DATASET_WORKSPACE_SOURCE_AMBIGUOUS",
                "历史数据存储映射无法唯一推导，需要先执行数据迁移"
        );
    }

    private static V2DatasetEditability blocked(
            String code,
            String message
    ) {
        return new V2DatasetEditability(
                false,
                List.of(new V2DatasetPublishBlocker(code, message))
        );
    }
}
