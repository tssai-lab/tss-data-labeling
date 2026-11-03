package com.tss.platform.service;

import com.tss.platform.dto.ImportJobStatusDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.repository.ImportJobSampleFailureRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class ImportJobQueryService {

    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_PENDING = "PENDING";
    private static final Set<String> ACTIVE_RETRY_BLOCKING_STATUSES = Set.of("PENDING", "RUNNING");
    private static final String VERSION_DRAFT = "DRAFT";
    private static final String RETRY_MODE_FULL = "FULL";
    private static final String RETRY_MODE_INCREMENTAL = "INCREMENTAL";
    private static final String FAILURE_STATUS_FAILED = "FAILED";
    private static final String FAILURE_STATUS_RETRYING = "RETRYING";
    private static final Set<String> UNRESOLVED_FAILURE_STATUSES =
            Set.of(FAILURE_STATUS_FAILED, FAILURE_STATUS_RETRYING);

    private final ImportJobRepository importJobRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final AuthContext authContext;
    private final ImportJobLauncher importJobLauncher;
    private final DatasetSampleRepository sampleRepo;
    private final ImportJobSampleFailureRepository failureRepo;
    private final DatasetWorkspaceAuditService auditService;
    private DatasetWorkspaceCommandService commandService;

    @Autowired
    public ImportJobQueryService(
            ImportJobRepository importJobRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            AuthContext authContext,
            ImportJobLauncher importJobLauncher,
            DatasetSampleRepository sampleRepo,
            ImportJobSampleFailureRepository failureRepo,
            DatasetWorkspaceAuditService auditService
    ) {
        this.importJobRepo = importJobRepo;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.authContext = authContext;
        this.importJobLauncher = importJobLauncher;
        this.sampleRepo = sampleRepo;
        this.failureRepo = failureRepo;
        this.auditService = auditService;
    }

    @Autowired(required = false)
    void setCommandService(DatasetWorkspaceCommandService commandService) {
        this.commandService = commandService;
    }

    ImportJobQueryService(
            ImportJobRepository importJobRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            AuthContext authContext,
            ImportJobLauncher importJobLauncher,
            DatasetSampleRepository sampleRepo
    ) {
        this(
                importJobRepo,
                versionRepo,
                assetRepo,
                authContext,
                importJobLauncher,
                sampleRepo,
                null,
                null
        );
    }

    @Transactional(readOnly = true)
    public ImportJobStatusDto getStatus(String importJobId) {
        if (importJobId == null || importJobId.isBlank()) {
            throw new IllegalArgumentException("importJobId 不能为空");
        }
        ImportJob job = importJobRepo.findById(importJobId)
                .orElseThrow(() -> new ImportJobAccessException("importJob not found or no permission"));
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(job.getDatasetVersionId())
                .orElseThrow(() -> new ImportJobAccessException("importJob not found or no permission"));
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new ImportJobAccessException("importJob not found or no permission"));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new ImportJobAccessException("importJob not found or no permission");
        }

        return toDto(job);
    }

    @Transactional
    public ImportJobStatusDto retry(String importJobId, String mode) {
        if (importJobId == null || importJobId.isBlank()) {
            throw new IllegalArgumentException("importJobId 不能为空");
        }
        String normalizedMode = mode == null || mode.isBlank()
                ? RETRY_MODE_FULL
                : mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!RETRY_MODE_FULL.equals(normalizedMode)
                && !RETRY_MODE_INCREMENTAL.equals(normalizedMode)) {
            throw new ImportJobRetryRejectedException(
                    "retry mode must be FULL or INCREMENTAL"
            );
        }

        ImportJob snapshot = importJobRepo.findById(importJobId)
                .orElseThrow(() -> new ImportJobAccessException(
                        "importJob not found or no permission"
                ));
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService == null
                        ? null
                        : commandService.lockForLegacyMutation(
                                snapshot.getDatasetVersionId()
                        );
        ImportJob job = importJobRepo.findByIdForUpdate(importJobId)
                .orElseThrow(() -> new ImportJobAccessException("importJob not found or no permission"));
        DatasetVersion version = access == null
                ? versionRepo.findByIdAndDeletedFalse(job.getDatasetVersionId())
                        .orElseThrow(() -> new ImportJobAccessException(
                                "importJob not found or no permission"
                        ))
                : access.workspace();
        DatasetAsset asset = access == null
                ? assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                        .orElseThrow(() -> new ImportJobAccessException(
                                "importJob not found or no permission"
                        ))
                : access.asset();
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new ImportJobAccessException("importJob not found or no permission");
        }
        if (!VERSION_DRAFT.equals(version.getStatus())) {
            throw new ImportJobRetryRejectedException("ImportJob retry requires DRAFT dataset version");
        }
        if (RETRY_MODE_INCREMENTAL.equals(normalizedMode)) {
            return retryIncremental(job, asset, version);
        }
        if (STATUS_PARTIAL.equals(job.getStatus())) {
            throw new ImportJobRetryRejectedException(
                    "PARTIAL ImportJob must use INCREMENTAL retry"
            );
        }
        if (!STATUS_FAILED.equals(job.getStatus())) {
            throw new ImportJobRetryRejectedException("only FAILED ImportJob can be retried");
        }
        if (hasPersistedSamples(job)) {
            throw new ImportJobRetryRejectedException(
                    "ImportJob already has imported samples; upload a new package instead"
            );
        }
        requireNoActiveSiblingJob(job);
        if (failureRepo != null) {
            failureRepo.deleteByImportJobId(job.getId());
        }

        Instant now = Instant.now();
        job.setStatus(STATUS_PENDING);
        job.setProgress(0);
        job.setImportedSamples(0);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setErrorDetailsJson(null);
        job.setExecutorId(null);
        job.setStartedAt(null);
        job.setHeartbeatAt(null);
        job.setFinishedAt(null);
        job.setUpdatedAt(now);
        ImportJob saved = importJobRepo.saveAndFlush(job);
        incrementWorkspaceRevision(version);
        if (auditService != null) {
            auditService.recordFullRetry(asset, version, saved);
        }
        launchAfterCommit(saved.getId());
        return toDto(saved);
    }

    private ImportJobStatusDto retryIncremental(
            ImportJob job,
            DatasetAsset asset,
            DatasetVersion version
    ) {
        if (!STATUS_PARTIAL.equals(job.getStatus())) {
            throw new ImportJobRetryRejectedException(
                    "only PARTIAL ImportJob can use INCREMENTAL retry"
            );
        }
        if (failureRepo == null) {
            throw new ImportJobRetryRejectedException(
                    "ImportJob has no failed samples to retry"
            );
        }
        long failedSamples =
                failureRepo.countByImportJobIdAndStatus(job.getId(), FAILURE_STATUS_FAILED);
        if (failedSamples <= 0) {
            throw new ImportJobRetryRejectedException(
                    "ImportJob has no failed samples to retry"
            );
        }
        requireNoActiveSiblingJob(job);

        Instant now = Instant.now();
        int marked = failureRepo.markStatusByImportJobId(
                job.getId(),
                FAILURE_STATUS_FAILED,
                FAILURE_STATUS_RETRYING,
                now
        );
        if (marked <= 0) {
            throw new ImportJobRetryRejectedException(
                    "ImportJob has no failed samples to retry"
            );
        }
        job.setStatus(STATUS_PENDING);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setErrorDetailsJson(null);
        job.setExecutorId(null);
        job.setStartedAt(null);
        job.setHeartbeatAt(null);
        job.setFinishedAt(null);
        job.setUpdatedAt(now);
        ImportJob saved = importJobRepo.saveAndFlush(job);
        incrementWorkspaceRevision(version);
        if (auditService != null) {
            auditService.recordIncrementalRetry(asset, version, saved, marked);
        }
        launchAfterCommit(saved.getId());
        return toDto(saved);
    }

    private void incrementWorkspaceRevision(DatasetVersion version) {
        if (commandService != null) {
            commandService.incrementRevision(version);
        }
    }

    private boolean hasPersistedSamples(ImportJob job) {
        String packageId = job.getPackageId();
        if (packageId == null || packageId.isBlank()) {
            return sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdIsNull(
                    job.getDatasetVersionId()
            ) > 0;
        }
        return sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdAndDeletedFalse(
                job.getDatasetVersionId(),
                packageId
        ) > 0;
    }

    private void requireNoActiveSiblingJob(ImportJob job) {
        List<ImportJob> jobs = importJobRepo.findByDatasetVersionId(job.getDatasetVersionId());
        if (jobs == null) {
            return;
        }
        boolean hasActiveSibling = jobs.stream()
                .anyMatch(candidate -> !job.getId().equals(candidate.getId())
                        && ACTIVE_RETRY_BLOCKING_STATUSES.contains(candidate.getStatus()));
        if (hasActiveSibling) {
            throw new ImportJobRetryRejectedException(
                    "dataset version already has an active ImportJob; wait for it to finish before retry"
            );
        }
    }

    private void launchAfterCommit(String importJobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            importJobLauncher.launch(importJobId);
                        }
                    }
            );
            return;
        }
        importJobLauncher.launch(importJobId);
    }

    private ImportJobStatusDto toDto(ImportJob job) {
        ImportJobStatusDto dto = new ImportJobStatusDto();
        dto.setImportJobId(job.getId());
        dto.setDatasetVersionId(job.getDatasetVersionId());
        dto.setStatus(job.getStatus());
        dto.setProgress(job.getProgress());
        dto.setTotalSamples(job.getTotalSamples());
        dto.setImportedSamples(job.getImportedSamples());
        if (failureRepo != null) {
            dto.setFailedSamples(Math.toIntExact(
                    failureRepo.countByImportJobIdAndStatusIn(
                            job.getId(),
                            UNRESOLVED_FAILURE_STATUSES
                    )
            ));
        }
        dto.setErrorCode(job.getErrorCode());
        dto.setErrorMessage(job.getErrorMessage());
        dto.setErrorDetailsJson(job.getErrorDetailsJson());
        dto.setCreatedAt(job.getCreatedAt());
        dto.setStartedAt(job.getStartedAt());
        dto.setFinishedAt(job.getFinishedAt());
        return dto;
    }

    public static class ImportJobAccessException extends RuntimeException {
        public ImportJobAccessException(String message) {
            super(message);
        }
    }

    public static class ImportJobRetryRejectedException extends RuntimeException {
        public ImportJobRetryRejectedException(String message) {
            super(message);
        }
    }
}
