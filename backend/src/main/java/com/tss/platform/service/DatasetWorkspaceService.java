package com.tss.platform.service;

import com.tss.platform.dto.DatasetWorkspaceDraftDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class DatasetWorkspaceService {

    private static final String DRAFT = "DRAFT";
    private static final String NOT_FOUND = "dataset version not found or no permission";
    private static final String VERSION_LABEL_CONSTRAINT =
            "uk_dataset_version_asset_version";
    private static final int MAX_VERSION_LABEL_LENGTH = 64;

    static final String ACTIVE_VERSION_EXISTS = "ACTIVE_VERSION_EXISTS";
    static final String DELETED_VERSION_RESERVED = "DELETED_VERSION_RESERVED";

    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final AuthContext authContext;
    private final DatasetVersionLifecycleService lifecycleService;
    private final DatasetWorkspaceMaterializer materializer;
    private final DatasetWorkspaceAuditService auditService;

    @Autowired
    public DatasetWorkspaceService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            AuthContext authContext,
            DatasetVersionLifecycleService lifecycleService,
            DatasetWorkspaceMaterializer materializer,
            DatasetWorkspaceAuditService auditService
    ) {
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.authContext = authContext;
        this.lifecycleService = lifecycleService;
        this.materializer = materializer;
        this.auditService = auditService;
    }

    DatasetWorkspaceService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            AuthContext authContext,
            DatasetVersionLifecycleService lifecycleService,
            DatasetWorkspaceMaterializer materializer
    ) {
        this(
                versionRepo,
                assetRepo,
                authContext,
                lifecycleService,
                materializer,
                null
        );
    }

    @Transactional
    public DatasetWorkspaceDraftDto createDraft(String readyVersionId) {
        return createDraftInternal(readyVersionId, null, false);
    }

    @Transactional
    public DatasetWorkspaceDraftDto createDraft(
            String readyVersionId,
            String requestedVersionLabelValue
    ) {
        return createDraftInternal(
                readyVersionId,
                requestedVersionLabelValue,
                true
        );
    }

    private DatasetWorkspaceDraftDto createDraftInternal(
            String readyVersionId,
            String requestedVersionLabelValue,
            boolean continueExistingDraft
    ) {
        if (readyVersionId == null || readyVersionId.isBlank()) {
            throw new IllegalArgumentException(NOT_FOUND);
        }
        String requestedVersionLabel =
                normalizeRequestedVersionLabel(requestedVersionLabelValue);

        DatasetVersion parentSnapshot = versionRepo
                .findByIdAndDeletedFalse(readyVersionId)
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
        DatasetAsset asset = assetRepo
                .findByIdAndDeletedFalseForUpdate(parentSnapshot.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
        DatasetVersion parent = versionRepo
                .findByIdAndDeletedFalseForUpdate(readyVersionId)
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
        if (!asset.getId().equals(parent.getAssetId())) {
            throw new IllegalArgumentException(NOT_FOUND);
        }
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new IllegalArgumentException(NOT_FOUND);
        }
        lifecycleService.assertReadyVersion(parent);

        Optional<DatasetVersion> activeDraft =
                versionRepo.findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                        asset.getId(),
                        DRAFT
                );
        if (activeDraft.isPresent()) {
            if (continueExistingDraft) {
                return draftDto(
                        activeDraft.get(),
                        asset,
                        "workspace draft already exists"
                );
            }
            throw new IllegalArgumentException(
                    "dataset asset already has an active DRAFT version: "
                            + activeDraft.get().getId()
            );
        }

        VersionAllocationPreview allocation =
                previewVersionAllocation(asset.getId(), requestedVersionLabel);
        if (Boolean.FALSE.equals(allocation.requestedVersionLabelAvailable())) {
            throw new VersionLabelConflictException(allocation);
        }
        int versionNo = allocation.nextVersionNo();
        String versionLabel = requestedVersionLabel == null
                ? allocation.defaultVersionLabel()
                : requestedVersionLabel;
        Instant now = Instant.now();
        DatasetVersion draft = new DatasetVersion();
        draft.setId("dataset-ver-" + UUID.randomUUID().toString().replace("-", ""));
        draft.setAssetId(asset.getId());
        draft.setVersion(versionLabel);
        draft.setVersionNo(versionNo);
        draft.setVersionLabel(versionLabel);
        draft.setFileName(parent.getFileName());
        draft.setStoragePath(parent.getStoragePath());
        draft.setSizeBytes(parent.getSizeBytes());
        draft.setCvTaskType(parent.getCvTaskType());
        draft.setAnnotationFormat(parent.getAnnotationFormat());
        draft.setRemark("Workspace draft based on " + parent.getId());
        draft.setDescription(parent.getDescription());
        draft.setChangeLog("Workspace draft created from " + parent.getId());
        draft.setParentVersionId(parent.getId());
        draft.setStatus(DRAFT);
        draft.setFileFingerprint(parent.getFileFingerprint());
        draft.setPublishedAt(null);
        draft.setCreatedBy(authContext.currentUserId());
        draft.setOwnerUserId(asset.getOwnerUserId());
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        draft.setWorkspaceRevision(0L);
        draft.setWorkspaceHeadVersionId(asset.getCurrentVersionId());
        draft.setDeleted(false);
        draft.setDeletedAt(null);
        DatasetVersion saved;
        try {
            saved = versionRepo.saveAndFlush(draft);
        } catch (DataIntegrityViolationException exception) {
            if (!isVersionLabelConstraintViolation(exception)) {
                throw exception;
            }
            throw new VersionLabelConflictException(
                    new VersionAllocationPreview(
                            allocation.nextVersionNo(),
                            allocation.defaultVersionLabel(),
                            versionLabel,
                            false,
                            ACTIVE_VERSION_EXISTS
                    ),
                    exception
            );
        }
        materializer.materialize(asset, parent, saved);
        if (auditService != null) {
            auditService.recordDraftCreated(asset, saved);
        }

        return draftDto(saved, asset, "workspace draft created");
    }

    @Transactional(readOnly = true)
    public VersionAllocationPreview previewVersionAllocation(
            String assetId,
            String requestedVersionLabelValue
    ) {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("assetId cannot be empty");
        }
        String requestedVersionLabel =
                normalizeRequestedVersionLabel(requestedVersionLabelValue);
        int nextVersionNo = nextVersionNo(assetId);
        String defaultVersionLabel = "v" + nextVersionNo;
        if (requestedVersionLabel == null) {
            return new VersionAllocationPreview(
                    nextVersionNo,
                    defaultVersionLabel,
                    null,
                    null,
                    null
            );
        }
        Optional<DatasetVersion> conflict =
                versionRepo.findByAssetIdAndVersion(
                        assetId,
                        requestedVersionLabel
                );
        if (conflict.isEmpty()) {
            return new VersionAllocationPreview(
                    nextVersionNo,
                    defaultVersionLabel,
                    requestedVersionLabel,
                    true,
                    null
            );
        }
        String unavailableReason =
                Boolean.TRUE.equals(conflict.get().getDeleted())
                        ? DELETED_VERSION_RESERVED
                        : ACTIVE_VERSION_EXISTS;
        return new VersionAllocationPreview(
                nextVersionNo,
                defaultVersionLabel,
                requestedVersionLabel,
                false,
                unavailableReason
        );
    }

    public String normalizeRequestedVersionLabel(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.length() > MAX_VERSION_LABEL_LENGTH) {
            throw new InvalidVersionLabelException();
        }
        return normalized;
    }

    private int nextVersionNo(String assetId) {
        Integer maxVersionNo = versionRepo.findMaxVersionNoByAssetId(assetId);
        int candidate = (maxVersionNo == null ? 0 : maxVersionNo) + 1;
        while (versionRepo.existsByAssetIdAndVersion(assetId, "v" + candidate)) {
            candidate += 1;
        }
        return candidate;
    }

    private boolean isVersionLabelConstraintViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT)
                    .contains(VERSION_LABEL_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private DatasetWorkspaceDraftDto draftDto(
            DatasetVersion draft,
            DatasetAsset asset,
            String message
    ) {
        DatasetWorkspaceDraftDto dto = new DatasetWorkspaceDraftDto();
        dto.setDraftVersionId(draft.getId());
        dto.setParentVersionId(draft.getParentVersionId());
        dto.setDatasetAssetId(asset.getId());
        dto.setVersionNo(draft.getVersionNo());
        dto.setStatus(draft.getStatus());
        dto.setCurrentVersionId(asset.getCurrentVersionId());
        dto.setMessage(message);
        return dto;
    }

    public record VersionAllocationPreview(
            Integer nextVersionNo,
            String defaultVersionLabel,
            String requestedVersionLabel,
            Boolean requestedVersionLabelAvailable,
            String unavailableReason
    ) {
    }

    public static final class InvalidVersionLabelException
            extends IllegalArgumentException {

        InvalidVersionLabelException() {
            super("versionLabel must contain 1 to 64 non-whitespace characters");
        }
    }

    public static final class VersionLabelConflictException
            extends IllegalArgumentException {

        private final VersionAllocationPreview allocation;

        VersionLabelConflictException(VersionAllocationPreview allocation) {
            this(allocation, null);
        }

        VersionLabelConflictException(
                VersionAllocationPreview allocation,
                Throwable cause
        ) {
            super("dataset version label is unavailable", cause);
            this.allocation = allocation;
        }

        public VersionAllocationPreview getAllocation() {
            return allocation;
        }
    }
}
