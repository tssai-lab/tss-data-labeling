package com.tss.platform.service;

import com.tss.platform.dto.v2.V2DatasetAnnotationResource;
import com.tss.platform.dto.v2.V2DatasetDataResource;
import com.tss.platform.dto.v2.V2DatasetSampleListItem;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;

/** 实体到响应的纯映射；查询、软删除过滤与事务留在原服务。 */
final class DatasetWorkspaceResourceMapper {
    private DatasetWorkspaceResourceMapper() {}

    static V2DatasetSampleListItem toSampleItem(DatasetSample sample) {
        return new V2DatasetSampleListItem(
                sample.getId(),
                sample.getDatasetVersionId(),
                sample.getExternalId(),
                sample.getSampleIndex(),
                sample.getTags(),
                sample.getMetadata(),
                sample.getCreatedAt(),
                sample.getUpdatedAt(),
                Boolean.TRUE.equals(sample.getDeleted()),
                sample.getDeletedAt()
        );
    }

    static V2DatasetDataResource toData(DatasetSampleData data) {
        return new V2DatasetDataResource(
                data.getId(),
                data.getSampleId(),
                data.getDataType(),
                data.getSensor(),
                data.getChannel(),
                data.getSeq(),
                data.getFormat(),
                data.getFileName(),
                data.getSizeBytes(),
                data.getChecksum(),
                data.getContentType(),
                data.getMetadata(),
                data.getCreatedAt(),
                data.getUpdatedAt(),
                Boolean.TRUE.equals(data.getDeleted()),
                data.getDeletedAt()
        );
    }

    static V2DatasetAnnotationResource toAnnotation(
            DatasetAnnotation annotation
    ) {
        return new V2DatasetAnnotationResource(
                annotation.getId(),
                annotation.getSampleId(),
                annotation.getSampleDataId(),
                annotation.getAnnotationType(),
                annotation.getFormat(),
                annotation.getFileName(),
                annotation.getSizeBytes(),
                annotation.getChecksum(),
                annotation.getContentType(),
                annotation.getMetadata(),
                annotation.getCreatedAt(),
                annotation.getUpdatedAt(),
                Boolean.TRUE.equals(annotation.getDeleted()),
                annotation.getDeletedAt()
        );
    }
}
