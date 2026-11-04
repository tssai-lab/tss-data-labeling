package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.model.CvAnnotationFormat;
import com.tss.platform.model.CvTaskType;
import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;

import java.util.Objects;
import java.util.Optional;

final class DatasetVersionAllocationService {

    private static final String VERSION_STATUS_DRAFT = "DRAFT";
    private static final String VERSION_STATUS_READY = "READY";

    static final class DatasetAllocationAccessException extends IllegalArgumentException {

        private static final String MESSAGE =
                "dataset asset or parent version not found or no permission";

        DatasetAllocationAccessException() {
            super(MESSAGE);
        }
    }

    private final DatasetAssetRepository assetRepo;
    private final DatasetVersionRepository versionRepo;
    private final AuthContext authContext;

    DatasetVersionAllocationService(
            DatasetAssetRepository assetRepo,
            DatasetVersionRepository versionRepo,
            AuthContext authContext
    ) {
        this.assetRepo = assetRepo;
        this.versionRepo = versionRepo;
        this.authContext = authContext;
    }

    Integer previewVersionNo(String assetId) {
        if (assetId == null) {
            return 1;
        }
        Integer maxVersionNo = versionRepo.findMaxVersionNoByAssetId(assetId);
        return (maxVersionNo == null ? 0 : maxVersionNo) + 1;
    }

    DatasetAsset resolveTargetAsset(
            String assetIdValue,
            String taskType,
            String cvTaskType,
            String annotationFormat
    ) {
        String assetId = normalizeText(assetIdValue);
        if (assetId == null) {
            return null;
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(assetId)
                .orElseThrow(DatasetAllocationAccessException::new);
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new DatasetAllocationAccessException();
        }
        validateTargetAssetMetadata(asset, taskType, cvTaskType, annotationFormat);
        return asset;
    }

    String resolveParentVersionId(String parentVersionIdValue, DatasetAsset targetAsset) {
        if (targetAsset == null) {
            if (normalizeText(parentVersionIdValue) != null) {
                throw new IllegalArgumentException("parentVersionId is not allowed when creating a new dataset asset");
            }
            return null;
        }
        String parentVersionId = normalizeText(parentVersionIdValue);
        if (parentVersionId == null) {
            parentVersionId = normalizeText(targetAsset.getCurrentVersionId());
        }
        if (parentVersionId == null) {
            return null;
        }
        String resolvedParentVersionId = parentVersionId;
        DatasetVersion parent = versionRepo.findByIdAndDeletedFalse(resolvedParentVersionId)
                .orElseThrow(DatasetAllocationAccessException::new);
        if (!targetAsset.getId().equals(parent.getAssetId())) {
            throw new DatasetAllocationAccessException();
        }
        if (!VERSION_STATUS_READY.equals(parent.getStatus())) {
            throw new DatasetAllocationAccessException();
        }
        return parentVersionId;
    }

    VersionAllocation allocateVersion(DatasetUploadSession session, String assetId, boolean createAsset) {
        VersionAllocation allocation = allocateVersion(
                assetId,
                createAsset,
                requestedSessionLabel(session),
                normalizeText(session.getParentVersionId())
        );
        if (!createAsset) {
            validateTargetAssetMetadata(
                    allocation.asset(),
                    session.getType(),
                    session.getCvTaskType(),
                    session.getAnnotationFormat()
            );
        }
        return allocation;
    }

    VersionAllocation allocateVersion(
            String assetId,
            boolean createAsset,
            String requestedLabel,
            String parentVersionId
    ) {
        if (createAsset) {
            String label = requestedLabel == null ? "v1" : requestedLabel;
            return new VersionAllocation(new DatasetAsset(), 1, label, parentVersionId);
        }
        Optional<DatasetAsset> locked = assetRepo.findByIdAndDeletedFalseForUpdate(assetId);
        if (locked == null) {
            locked = Optional.empty();
        }
        DatasetAsset asset = locked.orElseThrow(
                DatasetAllocationAccessException::new
        );
        Integer maxVersionNo = versionRepo.findMaxVersionNoByAssetId(assetId);
        int nextVersionNo = (maxVersionNo == null ? 0 : maxVersionNo) + 1;
        String label = requestedLabel == null ? "v" + nextVersionNo : requestedLabel;
        return new VersionAllocation(asset, nextVersionNo, label, parentVersionId);
    }

    void requireUniqueVersionLabel(String assetId, String versionLabel) {
        if (versionRepo.existsByAssetIdAndVersion(assetId, versionLabel)) {
            throw new IllegalArgumentException(
                    "dataset version label already exists for asset: " + versionLabel
            );
        }
    }

    void requireNoActiveDraft(String assetId) {
        versionRepo.findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                assetId,
                VERSION_STATUS_DRAFT
        ).ifPresent(activeDraft -> {
            throw new IllegalArgumentException(activeDraftMessage(activeDraft));
        });
    }

    String defaultVersionLabel(String versionLabel, String version, Integer defaultVersionNo) {
        String label = normalizeText(versionLabel);
        if (label != null) {
            return label;
        }
        label = normalizeText(version);
        if (label != null) {
            return label;
        }
        return defaultVersionNo == null ? null : "v" + defaultVersionNo;
    }

    String displayVersionLabel(String versionLabel, String version, Integer defaultVersionNo) {
        String label = normalizeText(versionLabel);
        if (label != null) {
            return label;
        }
        label = normalizeText(version);
        if (label != null) {
            return label;
        }
        return defaultVersionNo == null ? defaultVersion(version) : "v" + defaultVersionNo;
    }

    boolean isVersionLabelGenerated(String versionLabel, String version) {
        return normalizeText(versionLabel) == null && normalizeText(version) == null;
    }

    private void validateTargetAssetMetadata(
            DatasetAsset asset,
            String taskType,
            String cvTaskType,
            String annotationFormat
    ) {
        String assetTaskType = DatasetTaskType.normalize(asset.getType());
        if (!taskType.equals(assetTaskType)) {
            throw new IllegalArgumentException("dataset asset type mismatch");
        }
        String assetCvTaskType = CvTaskType.normalizeForTask(assetTaskType, asset.getCvTaskType());
        if (!Objects.equals(cvTaskType, assetCvTaskType)) {
            throw new IllegalArgumentException("dataset asset cvTaskType mismatch");
        }
        String assetAnnotationFormat = CvAnnotationFormat.normalizeForTask(assetTaskType, asset.getAnnotationFormat());
        if (!Objects.equals(annotationFormat, assetAnnotationFormat)) {
            throw new IllegalArgumentException("dataset asset annotationFormat mismatch");
        }
    }

    private String requestedSessionLabel(DatasetUploadSession session) {
        if (Boolean.TRUE.equals(session.getVersionLabelGenerated())) {
            return null;
        }
        String label = normalizeText(session.getVersionLabel());
        return label != null ? label : normalizeText(session.getVersion());
    }

    private String defaultVersion(String value) {
        return value == null || value.isBlank() ? "v1" : value.trim();
    }

    private String activeDraftMessage(DatasetVersion activeDraft) {
        String id = activeDraft.getId();
        if (id == null || id.isBlank()) {
            return "dataset asset already has an active DRAFT version";
        }
        return "dataset asset already has an active DRAFT version: " + id;
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record VersionAllocation(
            DatasetAsset asset,
            Integer versionNo,
            String versionLabel,
            String parentVersionId
    ) {
    }
}
