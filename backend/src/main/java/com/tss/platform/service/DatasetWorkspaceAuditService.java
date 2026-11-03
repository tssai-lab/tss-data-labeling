package com.tss.platform.service;

import com.tss.platform.dto.DatasetWorkspaceAuditLogDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetWorkspaceAuditLog;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetWorkspaceAuditLogRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DatasetWorkspaceAuditService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String ACTOR_USER = "USER";
    private static final String ACTOR_SYSTEM = "SYSTEM";
    private static final Set<String> FORBIDDEN_DETAIL_KEYS = Set.of(
            "storagepath",
            "storage_path",
            "bucket",
            "objectname",
            "object_name",
            "destinationobject",
            "destination_object",
            "zipentryoffset",
            "zip_entry_offset",
            "zipdataoffset",
            "zip_data_offset"
    );
    private static final String NOT_FOUND =
            "dataset workspace audit log not found or no permission";

    private final DatasetWorkspaceAuditLogRepository auditRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final AuthContext authContext;

    public DatasetWorkspaceAuditService(
            DatasetWorkspaceAuditLogRepository auditRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            AuthContext authContext
    ) {
        this.auditRepo = auditRepo;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.authContext = authContext;
    }

    @Transactional
    public void recordDraftCreated(DatasetAsset asset, DatasetVersion draft) {
        appendUser(
                asset,
                draft,
                "DRAFT_CREATED",
                "DATASET_VERSION",
                draft.getId(),
                null,
                null,
                null,
                Map.of(
                        "parentVersionId", safe(draft.getParentVersionId()),
                        "versionNo", safe(draft.getVersionNo()),
                        "status", safe(draft.getStatus())
                )
        );
    }

    @Transactional
    public void recordAppendInit(
            DatasetAsset asset,
            DatasetVersion draft,
            DatasetUploadSession session
    ) {
        appendUser(
                asset,
                draft,
                "APPEND_PACKAGE_INIT",
                "UPLOAD_SESSION",
                session.getId(),
                null,
                null,
                null,
                Map.of(
                        "uploadId", safe(session.getId()),
                        "fileName", safe(session.getFileName()),
                        "fileSize", safe(session.getFileSize()),
                        "sampleGrouping", safe(session.getSampleGrouping()),
                        "strictManifest", Boolean.TRUE.equals(session.getStrictManifest()),
                        "totalChunks", safe(session.getTotalChunks())
                )
        );
    }

    @Transactional
    public void recordAppendCompleted(
            DatasetAsset asset,
            DatasetVersion draft,
            DatasetUploadSession session,
            DatasetPackage datasetPackage,
            ImportJob job
    ) {
        appendUser(
                asset,
                draft,
                "APPEND_PACKAGE_COMPLETED",
                "DATASET_PACKAGE",
                datasetPackage.getId(),
                job.getId(),
                datasetPackage.getId(),
                null,
                Map.of(
                        "uploadId", safe(session.getId()),
                        "fileName", safe(datasetPackage.getFileName()),
                        "sizeBytes", safe(datasetPackage.getSizeBytes()),
                        "sampleGrouping", safe(session.getSampleGrouping()),
                        "strictManifest", Boolean.TRUE.equals(session.getStrictManifest()),
                        "packageStatus", safe(datasetPackage.getStatus()),
                        "importStatus", safe(job.getStatus())
                )
        );
    }

    @Transactional
    public void recordImportSucceeded(
            DatasetAsset asset,
            DatasetVersion version,
            ImportJob job,
            boolean appendPackage,
            int totalSamples,
            int totalDataCount,
            int totalAnnotationCount
    ) {
        appendSystem(
                asset,
                version,
                "IMPORT_JOB_SUCCEEDED",
                "IMPORT_JOB",
                job.getId(),
                job.getId(),
                job.getPackageId(),
                null,
                Map.of(
                        "importType", appendPackage ? "APPEND" : "INITIAL",
                        "status", "SUCCESS",
                        "totalSamples", totalSamples,
                        "totalDataCount", totalDataCount,
                        "totalAnnotationCount", totalAnnotationCount
                )
        );
    }

    @Transactional
    public void recordImportFailed(
            DatasetAsset asset,
            DatasetVersion version,
            ImportJob job,
            String packageRole,
            String errorCode
    ) {
        appendSystem(
                asset,
                version,
                "IMPORT_JOB_FAILED",
                "IMPORT_JOB",
                job.getId(),
                job.getId(),
                job.getPackageId(),
                null,
                Map.of(
                        "packageRole", safe(packageRole),
                        "errorCode", safe(errorCode),
                        "status", "FAILED"
                )
        );
    }

    @Transactional
    public void recordImportPartial(
            DatasetAsset asset,
            DatasetVersion version,
            ImportJob job,
            String packageRole,
            int importedSamples,
            int failedSamples
    ) {
        appendSystem(
                asset,
                version,
                "IMPORT_JOB_PARTIAL",
                "IMPORT_JOB",
                job.getId(),
                job.getId(),
                job.getPackageId(),
                null,
                Map.of(
                        "packageRole", safe(packageRole),
                        "status", "PARTIAL",
                        "importedSamples", importedSamples,
                        "failedSamples", failedSamples
                )
        );
    }

    @Transactional
    public void recordSampleDeleted(
            DatasetAsset asset,
            DatasetVersion version,
            DatasetSample sample
    ) {
        appendUser(
                asset,
                version,
                "SAMPLE_DELETED",
                "DATASET_SAMPLE",
                sample.getId(),
                null,
                sample.getCreatedByPackageId(),
                sample.getId(),
                sampleDetails(sample)
        );
    }

    @Transactional
    public void recordSampleRestored(
            DatasetAsset asset,
            DatasetVersion version,
            DatasetSample sample
    ) {
        appendUser(
                asset,
                version,
                "SAMPLE_RESTORED",
                "DATASET_SAMPLE",
                sample.getId(),
                null,
                sample.getCreatedByPackageId(),
                sample.getId(),
                sampleDetails(sample)
        );
    }

    @Transactional
    public void recordVersionPublished(DatasetAsset asset, DatasetVersion draft) {
        appendUser(
                asset,
                draft,
                "VERSION_PUBLISHED",
                "DATASET_VERSION",
                draft.getId(),
                null,
                null,
                null,
                Map.of(
                        "parentVersionId", safe(draft.getParentVersionId()),
                        "versionNo", safe(draft.getVersionNo()),
                        "status", safe(draft.getStatus())
                )
        );
    }

    @Transactional
    public void recordUserAction(
            DatasetAsset asset,
            DatasetVersion version,
            String operation,
            String targetType,
            String targetId,
            String packageId,
            String sampleId,
            Map<String, Object> details
    ) {
        appendUser(
                asset,
                version,
                operation,
                targetType,
                targetId,
                null,
                packageId,
                sampleId,
                details == null ? Map.of() : details
        );
    }

    @Transactional
    public void recordFullRetry(
            DatasetAsset asset,
            DatasetVersion version,
            ImportJob job
    ) {
        appendUser(
                asset,
                version,
                "IMPORT_JOB_RETRIED",
                "IMPORT_JOB",
                job.getId(),
                job.getId(),
                job.getPackageId(),
                null,
                Map.of(
                        "retryMode", "FULL",
                        "status", safe(job.getStatus())
                )
        );
    }

    @Transactional
    public void recordIncrementalRetry(
            DatasetAsset asset,
            DatasetVersion version,
            ImportJob job,
            int failedSamples
    ) {
        appendUser(
                asset,
                version,
                "IMPORT_JOB_RETRIED",
                "IMPORT_JOB",
                job.getId(),
                job.getId(),
                job.getPackageId(),
                null,
                Map.of(
                        "retryMode", "INCREMENTAL",
                        "status", safe(job.getStatus()),
                        "failedSamples", failedSamples
                )
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<DatasetWorkspaceAuditLogDto> listByVersion(
            String datasetVersionId,
            Integer page,
            Integer pageSize
    ) {
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(datasetVersionId)
                .orElseThrow(() -> new DatasetWorkspaceAuditAccessException(NOT_FOUND));
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new DatasetWorkspaceAuditAccessException(NOT_FOUND));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new DatasetWorkspaceAuditAccessException(NOT_FOUND);
        }
        int resolvedPage = resolvePage(page);
        int resolvedPageSize = resolvePageSize(pageSize);
        Page<DatasetWorkspaceAuditLog> result =
                auditRepo.findByDatasetVersionIdOrderByCreatedAtDescIdDesc(
                        version.getId(),
                        PageRequest.of(resolvedPage - 1, resolvedPageSize)
                );
        PageResponse<DatasetWorkspaceAuditLogDto> response = new PageResponse<>();
        response.setData(result.getContent().stream()
                .map(DatasetWorkspaceAuditService::toDto)
                .toList());
        response.setTotal(result.getTotalElements());
        response.setPage(resolvedPage);
        response.setPageSize(resolvedPageSize);
        response.setTotalPages(result.getTotalPages());
        return response;
    }

    private void appendUser(
            DatasetAsset asset,
            DatasetVersion version,
            String operation,
            String targetType,
            String targetId,
            String importJobId,
            String packageId,
            String sampleId,
            Map<String, Object> details
    ) {
        append(
                asset,
                version,
                operation,
                ACTOR_USER,
                authContext.currentUserId(),
                targetType,
                targetId,
                importJobId,
                packageId,
                sampleId,
                details
        );
    }

    private void appendSystem(
            DatasetAsset asset,
            DatasetVersion version,
            String operation,
            String targetType,
            String targetId,
            String importJobId,
            String packageId,
            String sampleId,
            Map<String, Object> details
    ) {
        append(
                asset,
                version,
                operation,
                ACTOR_SYSTEM,
                null,
                targetType,
                targetId,
                importJobId,
                packageId,
                sampleId,
                details
        );
    }

    private void append(
            DatasetAsset asset,
            DatasetVersion version,
            String operation,
            String actorType,
            Integer actorUserId,
            String targetType,
            String targetId,
            String importJobId,
            String packageId,
            String sampleId,
            Map<String, Object> details
    ) {
        DatasetWorkspaceAuditLog log = new DatasetWorkspaceAuditLog();
        log.setId("workspace-audit-" + UUID.randomUUID().toString().replace("-", ""));
        log.setDatasetAssetId(asset.getId());
        log.setDatasetVersionId(version.getId());
        log.setParentVersionId(version.getParentVersionId());
        log.setOperation(operation);
        log.setActorType(actorType);
        log.setActorUserId(actorUserId);
        log.setOwnerUserId(asset.getOwnerUserId());
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setImportJobId(importJobId);
        log.setPackageId(packageId);
        log.setSampleId(sampleId);
        log.setDetails(sanitizeDetails(details));
        log.setCreatedAt(Instant.now());
        auditRepo.save(log);
    }

    private static Map<String, Object> sampleDetails(DatasetSample sample) {
        Map<String, Object> details = new LinkedHashMap<>();
        putIfPresent(details, "externalId", sample.getExternalId());
        putIfPresent(details, "sampleIndex", sample.getSampleIndex());
        putIfPresent(details, "createdByPackageId", sample.getCreatedByPackageId());
        details.put("deleted", Boolean.TRUE.equals(sample.getDeleted()));
        return details;
    }

    private static DatasetWorkspaceAuditLogDto toDto(DatasetWorkspaceAuditLog log) {
        DatasetWorkspaceAuditLogDto dto = new DatasetWorkspaceAuditLogDto();
        dto.setId(log.getId());
        dto.setDatasetAssetId(log.getDatasetAssetId());
        dto.setDatasetVersionId(log.getDatasetVersionId());
        dto.setParentVersionId(log.getParentVersionId());
        dto.setOperation(log.getOperation());
        dto.setActorType(log.getActorType());
        dto.setActorUserId(log.getActorUserId());
        dto.setTargetType(log.getTargetType());
        dto.setTargetId(log.getTargetId());
        dto.setImportJobId(log.getImportJobId());
        dto.setPackageId(log.getPackageId());
        dto.setSampleId(log.getSampleId());
        dto.setDetails(sanitizeDetails(log.getDetails()));
        dto.setCreatedAt(log.getCreatedAt());
        return dto;
    }

    private static Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            if (key == null || isForbiddenDetailKey(key)) {
                continue;
            }
            sanitized.put(key, entry.getValue());
        }
        return sanitized;
    }

    private static boolean isForbiddenDetailKey(String key) {
        return FORBIDDEN_DETAIL_KEYS.contains(
                key.replace("-", "_").toLowerCase(Locale.ROOT)
        );
    }

    private static int resolvePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int resolvePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static Object safe(Object value) {
        return value == null ? "" : value;
    }

    private static void putIfPresent(Map<String, Object> details, String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
    }

    public static class DatasetWorkspaceAuditAccessException extends RuntimeException {
        public DatasetWorkspaceAuditAccessException(String message) {
            super(message);
        }
    }
}
