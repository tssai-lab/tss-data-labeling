package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.v2.V2DatasetPreviewDescriptor;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class V2DatasetPreviewDescriptorService {

    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final AuthContext authContext;

    public V2DatasetPreviewDescriptorService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            AuthContext authContext
    ) {
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.authContext = authContext;
    }

    @Transactional(readOnly = true)
    public V2DatasetPreviewDescriptor describe(String versionId) {
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(this::notFound);
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(this::notFound);
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw notFound();
        }
        requirePreviewable(version, asset.getType());

        V2DatasetPreviewDescriptor descriptor = new V2DatasetPreviewDescriptor();
        descriptor.setDatasetVersionId(version.getId());
        switch (asset.getType()) {
            case "CV", "NLP", "LEROBOT" -> archive(descriptor, version.getId());
            case "POINT_CLOUD" -> pointCloud(descriptor, version.getId());
            case "MULTIMODAL" -> sampleGallery(descriptor, version.getId());
            default -> throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UNSUPPORTED_PREVIEW",
                    "该数据集类型暂不支持预览"
            );
        }
        return descriptor;
    }

    private void archive(V2DatasetPreviewDescriptor descriptor, String versionId) {
        descriptor.setMode("ARCHIVE");
        descriptor.setCapabilities(List.of(
                "LIST_FILES",
                "PREVIEW_CONTENT",
                "PREVIEW_IMAGE"
        ));
        Map<String, String> links = new LinkedHashMap<>();
        links.put("items", "/api/dataset/preview/files?id=" + versionId);
        links.put("content", "/api/dataset/preview/content?id=" + versionId);
        links.put("image", "/api/dataset/preview/image?id=" + versionId);
        descriptor.setLinks(Map.copyOf(links));
    }

    private void pointCloud(V2DatasetPreviewDescriptor descriptor, String versionId) {
        descriptor.setMode("POINT_CLOUD");
        descriptor.setCapabilities(List.of(
                "PREVIEW_POINT_CLOUD",
                "STREAM_POINT_CLOUD"
        ));
        Map<String, String> links = new LinkedHashMap<>();
        links.put("items", "/api/dataset/point-cloud/preview?id=" + versionId);
        links.put("file", "/api/dataset/point-cloud/file?id=" + versionId);
        links.put("zipFile", "/api/dataset/point-cloud/zip-file?id=" + versionId);
        descriptor.setLinks(Map.copyOf(links));
    }

    private void sampleGallery(
            V2DatasetPreviewDescriptor descriptor,
            String versionId
    ) {
        descriptor.setMode("SAMPLE_GALLERY");
        descriptor.setCapabilities(List.of(
                "LIST_SAMPLES",
                "VIEW_SAMPLE",
                "PREVIEW_SAMPLE_DATA",
                "DOWNLOAD_SAMPLE_DATA"
        ));
        Map<String, String> links = new LinkedHashMap<>();
        links.put("items", "/api/dataset-versions/" + versionId + "/samples");
        links.put("sample", "/api/dataset-samples/{sampleId}");
        links.put("sampleData", "/api/dataset-samples/{sampleId}/data");
        links.put("preview", "/api/dataset-sample-data/{dataId}/preview");
        links.put("download", "/api/dataset-sample-data/{dataId}/download");
        descriptor.setLinks(Map.copyOf(links));
    }

    private void requirePreviewable(DatasetVersion version, String type) {
        Set<String> statuses = "MULTIMODAL".equals(type)
                ? Set.of("READY")
                : Set.of("READY", "DEPRECATED");
        if (!statuses.contains(version.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATASET_NOT_PREVIEWABLE",
                    "该数据集版本当前不可预览"
            );
        }
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "DATASET_NOT_FOUND",
                "数据集版本不存在或无权访问"
        );
    }
}
