package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.ModelUploadProgressDto;
import com.tss.platform.dto.UploadCompleteRequest;
import com.tss.platform.dto.UploadInitRequest;
import com.tss.platform.dto.v2.V2ModelUploadDto;
import com.tss.platform.dto.v2.V2ModelUploadInitRequest;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelUploadChunk;
import com.tss.platform.entity.ModelUploadSession;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.model.TaskType;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelUploadChunkRepository;
import com.tss.platform.repository.ModelUploadSessionRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.repository.UploadChunkProgressSummary;
import com.tss.platform.security.AuthContext;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ModelUploadService {

    private static final int CHUNK_SIZE = 5 * 1024 * 1024;
    private static final String STATUS_UPLOADING = "UPLOADING";
    private static final String STATUS_COMPLETING = "COMPLETING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private final MinioClient minioClient;
    private final String bucket;
    private final ModelUploadSessionRepository sessionRepo;
    private final ModelUploadChunkRepository chunkRepo;
    private final ModelAssetRepository modelAssetRepo;
    private final ModelVersionRepository modelVersionRepo;
    private final AuthContext authContext;
    private final MinioDeleteTaskService minioDeleteTaskService;
    private final ModelArtifactIntegrityService artifactIntegrityService;
    private final TransactionTemplate transactionTemplate;

    public ModelUploadService(
            MinioClient minioClient,
            MinioConfig minioConfig,
            ModelUploadSessionRepository sessionRepo,
            ModelUploadChunkRepository chunkRepo,
            ModelAssetRepository modelAssetRepo,
            ModelVersionRepository modelVersionRepo,
            AuthContext authContext,
            MinioDeleteTaskService minioDeleteTaskService,
            PlatformTransactionManager transactionManager
    ) {
        this.minioClient = minioClient;
        this.bucket = minioConfig.getBucket();
        this.sessionRepo = sessionRepo;
        this.chunkRepo = chunkRepo;
        this.modelAssetRepo = modelAssetRepo;
        this.modelVersionRepo = modelVersionRepo;
        this.authContext = authContext;
        this.minioDeleteTaskService = minioDeleteTaskService;
        this.artifactIntegrityService = new ModelArtifactIntegrityService(
                new MinioService(minioClient, minioConfig)
        );
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public ModelUploadProgressDto init(UploadInitRequest req) {
        validateInit(req);
        Integer ownerUserId = authContext.currentUserId();
        String fingerprint = normalizeText(req.getFileFingerprint());
        if (fingerprint != null) {
            ModelUploadSession existing = sessionRepo
                    .findFirstByFileFingerprintAndStatusAndOwnerUserIdOrderByUpdatedAtDesc(
                            fingerprint,
                            STATUS_UPLOADING,
                            ownerUserId
                    )
                    .orElse(null);
            if (existing != null && sameUpload(existing, req)) {
                existing.setUpdatedAt(Instant.now());
                return progress(sessionRepo.save(existing));
            }
        }

        ModelUploadSession session = new ModelUploadSession();
        session.setId("model-upload-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", ""));
        session.setFileFingerprint(fingerprint);
        session.setFileName(req.getFileName().trim());
        session.setFileSize(req.getFileSize());
        session.setCommitInfo(normalizeCommitInfo(req.getCommitInfo()));
        session.setHyperParams(copyHyperParams(req.getHyperParams()));
        session.setChunkSize(CHUNK_SIZE);
        session.setTotalChunks((int) Math.ceil(req.getFileSize() / (double) CHUNK_SIZE));
        session.setStatus(STATUS_UPLOADING);
        session.setOwnerUserId(ownerUserId);
        Instant now = Instant.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return progress(sessionRepo.save(session));
    }

    @Transactional
    public V2ModelUploadDto initV2(V2ModelUploadInitRequest req) {
        try {
            validateV2Init(req);
            Integer ownerUserId = authContext.currentUserId();
            ModelBusinessMetadata metadata = resolveV2Metadata(req, ownerUserId);
            String fingerprint = normalizeText(req.getFileFingerprint());
            if (fingerprint != null) {
                ModelUploadSession existing = sessionRepo
                        .findFirstByFileFingerprintAndStatusAndOwnerUserIdOrderByUpdatedAtDesc(
                                fingerprint,
                                STATUS_UPLOADING,
                                ownerUserId
                        )
                        .orElse(null);
                if (existing != null && sameV2Upload(existing, req, metadata)) {
                    existing.setUpdatedAt(Instant.now());
                    return toV2(sessionRepo.save(existing));
                }
            }

            ModelUploadSession session = new ModelUploadSession();
            session.setId("model-upload-" + System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().replace("-", ""));
            session.setFileFingerprint(fingerprint);
            session.setFileName(req.getFileName().trim());
            session.setFileSize(req.getFileSize());
            session.setChunkSize(CHUNK_SIZE);
            session.setTotalChunks((int) Math.ceil(req.getFileSize() / (double) CHUNK_SIZE));
            session.setTargetAssetId(metadata.targetAssetId());
            session.setModelName(metadata.modelName());
            session.setModelVersion(req.getModelVersion().trim());
            session.setTaskType(metadata.taskType());
            session.setRemark(metadata.remark());
            session.setCommitInfo(normalizeCommitInfo(req.getCommitInfo()));
            session.setHyperParams(copyHyperParams(req.getHyperParams()));
            session.setStatus(STATUS_UPLOADING);
            session.setOwnerUserId(ownerUserId);
            Instant now = Instant.now();
            session.setCreatedAt(now);
            session.setUpdatedAt(now);
            return toV2(sessionRepo.save(session));
        } catch (IllegalArgumentException exception) {
            throw mapV2ModelUploadFailure(exception);
        }
    }

    public ModelUploadProgressDto saveChunk(String uploadId, Integer partIndex, MultipartFile file) {
        ModelUploadSession session = getSession(uploadId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return progress(session);
        }
        if (!STATUS_UPLOADING.equals(session.getStatus())) {
            throw new IllegalArgumentException("上传状态不允许上传分片: " + session.getStatus());
        }
        if (partIndex == null || partIndex < 0 || partIndex >= session.getTotalChunks()) {
            throw new IllegalArgumentException("partIndex 超出范围");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("分片文件不能为空");
        }
        if (partIndex < session.getTotalChunks() - 1 && file.getSize() != session.getChunkSize()) {
            throw new IllegalArgumentException("非末尾分片大小必须等于 chunkSize");
        }

        String objectName = "users/" + session.getOwnerUserId() + "/models/_uploads/" + uploadId + "/part-" + partIndex;
        StatObjectResponse stat;
        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(is, file.getSize(), -1)
                            .contentType(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                            .build()
            );
            stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectName).build()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("分片上传失败: " + e.getMessage());
        }
        try {
            ChunkPersistenceResult result = transactionTemplate.execute(status ->
                    persistUploadedChunk(uploadId, partIndex, file.getSize(), objectName, stat.etag())
            );
            if (result != null && !result.persisted()) {
                removeObjectQuietly(objectName);
            }
            return progress(result == null ? getSession(uploadId) : result.session());
        } catch (RuntimeException e) {
            removeObjectQuietly(objectName);
            throw new IllegalArgumentException("分片上传失败: " + rootMessage(e));
        }
    }

    @Transactional(readOnly = true)
    public ModelUploadProgressDto getProgress(String uploadId) {
        return progress(getSession(uploadId));
    }

    @Transactional(readOnly = true)
    public V2ModelUploadDto getProgressV2(String uploadId) {
        try {
            return toV2(getSession(uploadId));
        } catch (IllegalArgumentException exception) {
            throw mapV2ModelUploadFailure(exception);
        }
    }

    public V2ModelUploadDto saveChunkV2(
            String uploadId,
            Integer partIndex,
            MultipartFile file
    ) {
        try {
            saveChunk(uploadId, partIndex, file);
            return toV2(getSession(uploadId));
        } catch (IllegalArgumentException exception) {
            throw mapV2ModelUploadFailure(exception);
        }
    }

    public Map<String, Object> complete(UploadCompleteRequest req) {
        validateCompleteRequest(req);
        CompletionPlan plan = transactionTemplate.execute(status -> prepareCompletionPlan(req));
        if (plan == null) {
            throw new IllegalArgumentException("上传状态无效");
        }
        if (plan.alreadyCompleted()) {
            return completedPayload(plan.session());
        }
        boolean objectReady = false;
        try {
            resolveCommitInfo(req, plan.session());
            ModelArtifactIntegrityService.Inspection inspection =
                    composeAndValidateModelObject(
                            plan.destName(),
                            plan.chunks(),
                            plan.session().getFileSize(),
                            plan.target().taskType()
                    );
            objectReady = true;
            CompletionPersistenceResult persisted = transactionTemplate.execute(status ->
                    persistCompletedModel(req, plan, inspection)
            );
            if (persisted == null) {
                throw new IllegalArgumentException("保存模型上传记录失败");
            }
            registerChunkCleanup(persisted.session().getId(), plan.chunks());
            return completedPayload(
                    persisted.session(),
                    persisted.asset(),
                    persisted.version()
            );
        } catch (RuntimeException e) {
            if (objectReady) {
                removeObjectQuietly(plan.destName());
            }
            resetCompletingSessionQuietly(plan.session().getId());
            if (AssetNamePolicy.isNameConstraintViolation(e)) {
                throw new AssetNameConflictException("model");
            }
            if (isDuplicateVersionError(e)) {
                throw duplicateVersion(plan.target().assetId(), plan.version());
            }
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("保存模型上传记录失败: " + rootMessage(e));
        }
    }

    public V2ModelUploadDto completeV2(String uploadId) {
        try {
            ModelUploadSession session = getSession(uploadId);
            requireText(session.getModelVersion(), "modelVersion 不能为空");
            requireText(session.getModelName(), "modelName 不能为空");
            requireText(session.getTaskType(), "taskType 不能为空");
            requireText(session.getRemark(), "remark 不能为空");
            requireText(session.getCommitInfo(), "commitInfo 不能为空");

            UploadCompleteRequest request = new UploadCompleteRequest();
            request.setUploadId(uploadId);
            request.setAssetId(session.getTargetAssetId());
            request.setModelName(session.getModelName());
            request.setVersion(session.getModelVersion());
            request.setType(session.getTaskType());
            request.setRemark(session.getRemark());
            request.setCommitInfo(session.getCommitInfo());
            request.setHyperParams(session.getHyperParams());
            Map<String, Object> completed = complete(request);

            ModelUploadSession refreshed = getSession(uploadId);
            V2ModelUploadDto dto = toV2(refreshed);
            dto.setStatus(stringValue(completed.get("status")));
            dto.setModelId(stringValue(completed.get("id")));
            dto.setAssetId(stringValue(completed.get("assetId")));
        dto.setArtifactSha256(stringValue(completed.get("artifactSha256")));
            dto.setArtifactSpecId(refreshed.getArtifactSpecId());
            dto.setIsCurrent(Boolean.TRUE.equals(completed.get("isCurrent")));
            dto.setUpdatedAt(refreshed.getUpdatedAt());
            return dto;
        } catch (IllegalArgumentException exception) {
            throw mapV2ModelUploadFailure(exception);
        }
    }

    private ChunkPersistenceResult persistUploadedChunk(
            String uploadId,
            Integer partIndex,
            long sizeBytes,
            String objectName,
            String etag
    ) {
        ModelUploadSession session = getSession(uploadId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return new ChunkPersistenceResult(session, false);
        }
        if (!STATUS_UPLOADING.equals(session.getStatus())) {
            throw new IllegalArgumentException("上传状态不允许上传分片: " + session.getStatus());
        }
        ModelUploadChunk chunk = chunkRepo.findByUploadIdAndPartIndex(uploadId, partIndex)
                .orElseGet(() -> {
                    ModelUploadChunk c = new ModelUploadChunk();
                    c.setId("model-chunk-" + UUID.randomUUID().toString().replace("-", ""));
                    c.setUploadId(uploadId);
                    c.setPartIndex(partIndex);
                    c.setCreatedAt(Instant.now());
                    return c;
                });
        chunk.setObjectName(objectName);
        chunk.setSizeBytes(sizeBytes);
        chunk.setEtag(etag);
        chunkRepo.save(chunk);
        session.setUpdatedAt(Instant.now());
        return new ChunkPersistenceResult(sessionRepo.save(session), true);
    }

    private CompletionPlan prepareCompletionPlan(UploadCompleteRequest req) {
        ModelUploadSession session = claimCompleting(req.getUploadId());
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return CompletionPlan.completed(session);
        }

        CompletionTarget target = resolveCompletionTarget(req, session);
        List<ModelUploadChunk> chunks = chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId());
        validateUploadedChunks(session, chunks);

        String assetId = target.assetId();
        String version = req.getVersion().trim();
        ModelVersion existingDraft = modelVersionRepo
                .findByAssetIdAndVersionAndDeletedFalse(assetId, version)
                .orElse(null);
        if (existingDraft != null && !"DRAFT".equals(existingDraft.getStatus())) {
            throw duplicateVersion(assetId, version);
        }
        String versionId = existingDraft == null
                ? "model-ver-" + UUID.randomUUID().toString().replace("-", "")
                : existingDraft.getId();
        String destName = "users/" + target.ownerUserId()
                + "/models/" + assetId
                + "/" + sanitizeSegment(version)
                + "/" + sanitizeSegment(session.getId())
                + "/" + sanitizeSegment(session.getFileName());
        return new CompletionPlan(
                session,
                target,
                List.copyOf(chunks),
                versionId,
                destName,
                version,
                false
        );
    }

    private void validateUploadedChunks(
            ModelUploadSession session,
            List<ModelUploadChunk> chunks
    ) {
        if (chunks.size() != session.getTotalChunks()) {
            throw new IllegalArgumentException("分片未上传完成");
        }
        for (int i = 0; i < session.getTotalChunks(); i += 1) {
            if (!Integer.valueOf(i).equals(chunks.get(i).getPartIndex())) {
                throw new IllegalArgumentException("缺少分片: " + i);
            }
        }
    }

    private ModelArtifactIntegrityService.Inspection composeAndValidateModelObject(
            String destName,
            List<ModelUploadChunk> chunks,
            Long expectedSize,
            String taskType
    ) {
        try {
            List<ComposeSource> sources = chunks.stream()
                    .map(chunk -> ComposeSource.builder()
                            .bucket(bucket)
                            .object(chunk.getObjectName())
                            .build())
                    .collect(Collectors.toList());
            minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(bucket)
                            .object(destName)
                            .sources(sources)
                            .build()
            );
            return artifactIntegrityService.inspect(destName, expectedSize, taskType);
        } catch (Exception e) {
            removeObjectQuietly(destName);
            throw new IllegalArgumentException("合并文件失败: " + e.getMessage());
        }
    }

    private CompletionPersistenceResult persistCompletedModel(
            UploadCompleteRequest req,
            CompletionPlan plan,
            ModelArtifactIntegrityService.Inspection inspection
    ) {
        CompletionTarget target = plan.target();
        if (!target.createAsset()) {
            target = resolveCompletionTarget(req, plan.session());
        }
        String assetId = target.assetId();
        ModelVersion existingDraft = modelVersionRepo
                .findByAssetIdAndVersionAndDeletedFalse(assetId, plan.version())
                .orElse(null);
        if (existingDraft != null && !"DRAFT".equals(existingDraft.getStatus())) {
            throw duplicateVersion(assetId, plan.version());
        }
        requireCompatibleArtifactSpec(assetId, inspection.artifactSpecId());

        Instant now = Instant.now();
        ModelAsset asset = target.asset();
        if (target.createAsset()) {
            asset.setId(assetId);
            asset.setName(AssetNamePolicy.normalizeRequired(req.getModelName()));
            asset.setType(target.taskType());
            asset.setRemark(req.getRemark().trim());
            asset.setOwnerUserId(target.ownerUserId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            modelAssetRepo.saveAndFlush(asset);
        }

        ModelVersion ver = existingDraft == null ? new ModelVersion() : existingDraft;
        ver.setId(plan.versionId());
        ver.setAssetId(assetId);
        ver.setVersion(plan.version());
        ver.setFileName(plan.session().getFileName());
        ver.setStoragePath(plan.destName());
        ver.setSizeBytes(plan.session().getFileSize());
        ver.setArtifactSha256(inspection.sha256());
        ver.setArtifactSpecId(inspection.artifactSpecId());
        ver.setArtifactAttestedSha256(inspection.sha256());
        ver.setArtifactAttestedAt(now);
        ver.setCommitInfo(resolveCommitInfo(req, plan.session()));
        ver.setHyperParams(resolveHyperParams(req, plan.session()));
        ver.setOwnerUserId(target.ownerUserId());
        if (existingDraft == null) {
            ver.setCreatedAt(now);
            ver.setCreatedBy(authContext.currentUserId());
        }
        ver.setStatus("READY");
        ver.setChangeLog(normalizeText(req.getRemark()));
        ver.setPublishedAt(now);
        modelVersionRepo.saveAndFlush(ver);

        asset.setCurrentVersionId(ver.getId());
        asset.setUpdatedAt(now);
        modelAssetRepo.saveAndFlush(asset);

        ModelUploadSession session = plan.session();
        session.setStatus(STATUS_COMPLETED);
        session.setStoragePath(plan.destName());
        session.setAssetId(assetId);
        session.setVersionId(plan.versionId());
        session.setArtifactSpecId(inspection.artifactSpecId());
        session.setUpdatedAt(now);
        sessionRepo.saveAndFlush(session);
        return new CompletionPersistenceResult(session, asset, ver);
    }

    private void validateCompleteRequest(UploadCompleteRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        requireText(req.getUploadId(), "uploadId 不能为空");
        String commitInfo = normalizeText(req.getCommitInfo());
        if (commitInfo != null && commitInfo.length() > 1024) {
            throw new IllegalArgumentException("commitInfo 长度不能超过 1024");
        }
    }

    private CompletionTarget resolveCompletionTarget(UploadCompleteRequest req, ModelUploadSession session) {
        requireText(req.getVersion(), "version 不能为空");
        String requestedAssetId = normalizeText(req.getAssetId());
        if (requestedAssetId == null) {
            String modelName = AssetNamePolicy.normalizeRequired(req.getModelName());
            if (modelAssetRepo.existsActiveNormalizedName(
                    session.getOwnerUserId(),
                    modelName,
                    null
            )) {
                throw new AssetNameConflictException("model");
            }
            requireText(req.getRemark(), "remark 不能为空");
            String taskType = TaskType.normalize(req.getType());
            String assetId = "model-asset-" + UUID.randomUUID().toString().replace("-", "");
            return new CompletionTarget(
                    new ModelAsset(),
                    assetId,
                    session.getOwnerUserId(),
                    taskType,
                    true
            );
        }

        ModelAsset asset = modelAssetRepo.findByIdAndDeletedFalseForUpdate(requestedAssetId)
                .orElseThrow(() -> new IllegalArgumentException("model asset not found: " + requestedAssetId));
        Integer currentUserId = authContext.currentUserId();
        if (!Objects.equals(currentUserId, session.getOwnerUserId())
                || !Objects.equals(currentUserId, asset.getOwnerUserId())) {
            throw new IllegalArgumentException("no permission for asset: " + requestedAssetId);
        }

        String modelName = normalizeText(req.getModelName());
        if (modelName != null && !modelName.equals(asset.getName())) {
            throw new IllegalArgumentException("modelName does not match existing asset");
        }
        String assetTaskType = TaskType.normalize(asset.getType());
        String requestedType = normalizeText(req.getType());
        if (requestedType != null && !TaskType.normalize(requestedType).equals(assetTaskType)) {
            throw new IllegalArgumentException("model type does not match existing asset");
        }
        return new CompletionTarget(
                asset,
                asset.getId(),
                asset.getOwnerUserId(),
                assetTaskType,
                false
        );
    }

    private void validateInit(UploadInitRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        requireText(req.getFileName(), "fileName 不能为空");
        validateModelFileName(req.getFileName());
        if (req.getFileSize() == null || req.getFileSize() <= 0) {
            throw new IllegalArgumentException("fileSize 必须大于 0");
        }
        requireCommitInfo(req.getCommitInfo());
    }

    private void validateV2Init(V2ModelUploadInitRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        requireText(req.getFileName(), "fileName 不能为空");
        validateModelFileName(req.getFileName());
        if (req.getFileSize() == null || req.getFileSize() <= 0) {
            throw new IllegalArgumentException("fileSize 必须大于 0");
        }
        requireText(req.getModelVersion(), "modelVersion 不能为空");
        requireCommitInfo(req.getCommitInfo());
    }

    private ModelBusinessMetadata resolveV2Metadata(
            V2ModelUploadInitRequest req,
            Integer ownerUserId
    ) {
        String targetAssetId = normalizeText(req.getTargetAssetId());
        if (targetAssetId == null) {
            String modelName = AssetNamePolicy.normalizeRequired(req.getModelName());
            if (modelAssetRepo.existsActiveNormalizedName(ownerUserId, modelName, null)) {
                throw new AssetNameConflictException("model");
            }
            requireText(req.getRemark(), "remark 不能为空");
            return new ModelBusinessMetadata(
                    null,
                    modelName,
                    TaskType.normalize(req.getTaskType()),
                    req.getRemark().trim()
            );
        }

        ModelAsset asset = modelAssetRepo.findByIdAndDeletedFalse(targetAssetId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "model asset not found: " + targetAssetId
                ));
        if (!Objects.equals(ownerUserId, asset.getOwnerUserId())) {
            throw new IllegalArgumentException(
                    "no permission for asset: " + targetAssetId
            );
        }
        String requestedName = normalizeText(req.getModelName());
        if (requestedName != null && !requestedName.equals(asset.getName())) {
            throw new IllegalArgumentException("modelName does not match existing asset");
        }
        String taskType = TaskType.normalize(asset.getType());
        String requestedType = normalizeText(req.getTaskType());
        if (requestedType != null
                && !TaskType.normalize(requestedType).equals(taskType)) {
            throw new IllegalArgumentException("model type does not match existing asset");
        }
        String requestedRemark = normalizeText(req.getRemark());
        if (requestedRemark != null && !requestedRemark.equals(asset.getRemark())) {
            throw new IllegalArgumentException("remark does not match existing asset");
        }
        return new ModelBusinessMetadata(
                asset.getId(),
                asset.getName(),
                taskType,
                asset.getRemark()
        );
    }

    private ModelUploadSession getSession(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId 不能为空");
        }
        ModelUploadSession session = sessionRepo.findById(uploadId)
                .orElseThrow(() -> new IllegalArgumentException("uploadId 无效"));
        authContext.requireOwnerAccess(session.getOwnerUserId(), "uploadId invalid or not accessible");
        return session;
    }

    private ModelUploadSession claimCompleting(String uploadId) {
        ModelUploadSession session = getSession(uploadId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return session;
        }
        if (STATUS_COMPLETING.equals(session.getStatus())) {
            throw new IllegalArgumentException("模型文件正在合并中，请稍后查询进度");
        }
        if (!STATUS_UPLOADING.equals(session.getStatus())) {
            throw new IllegalArgumentException("上传状态不允许完成: " + session.getStatus());
        }

        Instant now = Instant.now();
        int updated = sessionRepo.updateStatusIfCurrent(
                session.getId(),
                session.getOwnerUserId(),
                STATUS_UPLOADING,
                STATUS_COMPLETING,
                now
        );
        if (updated == 0) {
            ModelUploadSession current = getSession(uploadId);
            if (STATUS_COMPLETED.equals(current.getStatus())) {
                return current;
            }
            throw new IllegalArgumentException("模型文件正在合并中，请稍后查询进度");
        }
        session.setStatus(STATUS_COMPLETING);
        session.setUpdatedAt(now);
        return session;
    }

    private ModelUploadProgressDto progress(ModelUploadSession session) {
        boolean completed = STATUS_COMPLETED.equals(session.getStatus());
        UploadChunkProgressSummary summary = completed
                ? null
                : chunkRepo.summarizeProgressByUploadId(session.getId());
        List<Integer> uploadedPartIndexes = completed
                ? completedPartIndexes(session.getTotalChunks())
                : chunkRepo.findPartIndexesByUploadIdOrderByPartIndexAsc(session.getId());
        ModelUploadProgressDto dto = new ModelUploadProgressDto();
        dto.setUploadId(session.getId());
        dto.setStatus(session.getStatus());
        dto.setFileName(session.getFileName());
        dto.setFileSize(session.getFileSize());
        dto.setChunkSize(session.getChunkSize());
        dto.setTotalChunks(session.getTotalChunks());
        dto.setUploadedChunks(completed ? session.getTotalChunks() : uploadedChunks(summary));
        dto.setUploadedBytes(completed
                ? session.getFileSize()
                : uploadedBytes(summary));
        dto.setUploadedPartIndexes(uploadedPartIndexes);
        dto.setStoragePath(session.getStoragePath());
        dto.setAssetId(session.getAssetId());
        dto.setVersionId(session.getVersionId());
        dto.setArtifactSpecId(session.getArtifactSpecId());
        dto.setCommitInfo(session.getCommitInfo());
        dto.setHyperParams(session.getHyperParams());
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        return dto;
    }

    private V2ModelUploadDto toV2(ModelUploadSession session) {
        ModelUploadProgressDto progress = progress(session);
        V2ModelUploadDto dto = new V2ModelUploadDto();
        dto.setUploadId(progress.getUploadId());
        dto.setStatus(progress.getStatus());
        dto.setFileName(progress.getFileName());
        dto.setFileSize(progress.getFileSize());
        dto.setChunkSize(progress.getChunkSize());
        dto.setTotalChunks(progress.getTotalChunks());
        dto.setUploadedChunks(progress.getUploadedChunks());
        dto.setUploadedBytes(progress.getUploadedBytes());
        dto.setUploadedPartIndexes(progress.getUploadedPartIndexes());
        dto.setTargetAssetId(session.getTargetAssetId());
        dto.setModelId(session.getVersionId());
        dto.setAssetId(session.getAssetId());
        dto.setModelName(session.getModelName());
        dto.setModelVersion(session.getModelVersion());
        dto.setTaskType(session.getTaskType());
        dto.setRemark(session.getRemark());
        dto.setCommitInfo(session.getCommitInfo());
        dto.setHyperParams(session.getHyperParams());
        dto.setArtifactSpecId(progress.getArtifactSpecId());
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        return dto;
    }

    private List<Integer> completedPartIndexes(Integer totalChunks) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < totalChunks; i += 1) {
            indexes.add(i);
        }
        return indexes;
    }

    private int uploadedChunks(UploadChunkProgressSummary summary) {
        Long value = summary == null ? null : summary.getUploadedChunks();
        return value == null ? 0 : Math.toIntExact(value);
    }

    private long uploadedBytes(UploadChunkProgressSummary summary) {
        Long value = summary == null ? null : summary.getUploadedBytes();
        return value == null ? 0L : value;
    }

    private Map<String, Object> completedPayload(
            ModelUploadSession session,
            String modelName,
            String version,
            String type,
            String remark
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("uploadId", session.getId());
        data.put("id", session.getVersionId());
        data.put("assetId", session.getAssetId());
        data.put("name", modelName);
        data.put("version", version);
        data.put("type", type);
        data.put("remark", remark);
        data.put("fileName", session.getFileName());
        data.put("sizeBytes", session.getFileSize());
        data.put("status", session.getStatus());
        data.put("ownerUserId", session.getOwnerUserId());
        data.put("createdAt", session.getCreatedAt());
        data.put("updatedAt", session.getUpdatedAt());
        return data;
    }

    private Map<String, Object> completedPayload(ModelUploadSession session) {
        ModelVersion version = session.getVersionId() == null
                ? null
                : modelVersionRepo.findById(session.getVersionId()).orElse(null);
        ModelAsset asset = version == null || version.getAssetId() == null
                ? null
                : modelAssetRepo.findById(version.getAssetId()).orElse(null);

        return completedPayload(session, asset, version);
    }

    private Map<String, Object> completedPayload(
            ModelUploadSession session,
            ModelAsset asset,
            ModelVersion version
    ) {
        Map<String, Object> data = completedPayload(
                session,
                asset != null ? asset.getName() : null,
                version != null ? version.getVersion() : null,
                asset != null ? asset.getType() : null,
                asset != null ? asset.getRemark() : null
        );
        data.put("artifactSha256", version == null ? null : version.getArtifactSha256());
        data.put("artifactSpecId", version == null
                ? session.getArtifactSpecId()
                : version.getArtifactSpecId());
        data.put("commitInfo", version == null ? session.getCommitInfo() : version.getCommitInfo());
        data.put("hyperParams", version == null ? session.getHyperParams() : version.getHyperParams());
        data.put("isCurrent", version != null
                && asset != null
                && version.getId().equals(asset.getCurrentVersionId()));
        return data;
    }

    private void registerChunkCleanup(String uploadId, List<ModelUploadChunk> chunks) {
        List<String> objectNames = new ArrayList<>();
        for (ModelUploadChunk chunk : chunks) {
            objectNames.add(chunk.getObjectName());
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupChunks(uploadId, objectNames);
                }
            });
            return;
        }
        cleanupChunks(uploadId, objectNames);
    }

    private void cleanupChunks(String uploadId, List<String> objectNames) {
        for (String objectName : objectNames) {
            try {
                minioDeleteTaskService.enqueueDefaultBucketDeleteImmediately(
                        objectName,
                        MinioDeleteTaskService.SOURCE_MODEL_UPLOAD_CHUNK,
                        uploadId,
                        null
                );
            } catch (Exception ignored) {
                // 临时分片删除任务入队失败不阻断完成结果。
            }
        }
        try {
            chunkRepo.deleteByUploadId(uploadId);
        } catch (Exception ignored) {
            // 临时分片元数据清理失败不影响已完成的上传记录。
        }
    }

    private void resetCompletingSessionQuietly(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ModelUploadSession session = sessionRepo.findById(uploadId).orElse(null);
                if (session == null || !STATUS_COMPLETING.equals(session.getStatus())) {
                    return;
                }
                Instant now = Instant.now();
                int updated = sessionRepo.updateStatusIfCurrent(
                        session.getId(),
                        session.getOwnerUserId(),
                        STATUS_COMPLETING,
                        STATUS_UPLOADING,
                        now
                );
                if (updated > 0) {
                    session.setStatus(STATUS_UPLOADING);
                    session.setUpdatedAt(now);
                }
            });
        } catch (Exception ignored) {
            // 回退失败不覆盖原始上传错误。
        }
    }

    private boolean sameUpload(ModelUploadSession session, UploadInitRequest req) {
        return session.getFileName().equals(req.getFileName().trim())
                && session.getFileSize().equals(req.getFileSize())
                && Objects.equals(session.getCommitInfo(), normalizeText(req.getCommitInfo()))
                && Objects.equals(session.getHyperParams(), copyHyperParams(req.getHyperParams()));
    }

    private boolean sameV2Upload(
            ModelUploadSession session,
            V2ModelUploadInitRequest req,
            ModelBusinessMetadata metadata
    ) {
        return session.getFileName().equals(req.getFileName().trim())
                && session.getFileSize().equals(req.getFileSize())
                && Objects.equals(session.getTargetAssetId(), metadata.targetAssetId())
                && Objects.equals(session.getModelName(), metadata.modelName())
                && Objects.equals(
                        session.getModelVersion(),
                        req.getModelVersion().trim()
                )
                && Objects.equals(session.getTaskType(), metadata.taskType())
                && Objects.equals(session.getRemark(), metadata.remark())
                && Objects.equals(session.getCommitInfo(), normalizeText(req.getCommitInfo()))
                && Objects.equals(session.getHyperParams(), copyHyperParams(req.getHyperParams()));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private V2BusinessException mapV2ModelUploadFailure(
            IllegalArgumentException exception
    ) {
        if (exception instanceof AssetNameConflictException) {
            return new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "MODEL_NAME_CONFLICT",
                    "同一用户下已存在同名模型资产"
            );
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("already exists")) {
            return new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "MODEL_VERSION_CONFLICT",
                    "该模型版本已存在"
            );
        }
        if (message.contains("not found") || message.contains("no permission")) {
            return new V2BusinessException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_ASSET_NOT_FOUND",
                    "模型资产不存在或无权访问"
            );
        }
        if (message.contains("uploadId")
                || message.contains("not accessible")) {
            return new V2BusinessException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_UPLOAD_NOT_FOUND",
                    "模型上传任务不存在或无权访问"
            );
        }
        return new V2BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MODEL_UPLOAD_FAILED",
                "模型上传无法完成，请检查文件后重试",
                reasonDetails(message)
        );
    }

    private Map<String, Object> reasonDetails(String message) {
        return message == null || message.isBlank()
                ? Map.of()
                : Map.of("reason", message);
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeCommitInfo(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException("commitInfo 不能为空");
        }
        if (normalized.length() > 1024) {
            throw new IllegalArgumentException("commitInfo 长度不能超过 1024");
        }
        return normalized;
    }

    private void requireCommitInfo(String value) {
        normalizeCommitInfo(value);
    }

    private Map<String, Object> copyHyperParams(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }

    private String resolveCommitInfo(
            UploadCompleteRequest request,
            ModelUploadSession session
    ) {
        String requested = normalizeText(request.getCommitInfo());
        String initialized = normalizeText(session.getCommitInfo());
        if (requested != null && initialized != null && !requested.equals(initialized)) {
            throw new IllegalArgumentException("commitInfo 与上传初始化信息不一致");
        }
        return normalizeCommitInfo(requested == null ? initialized : requested);
    }

    private Map<String, Object> resolveHyperParams(
            UploadCompleteRequest request,
            ModelUploadSession session
    ) {
        Map<String, Object> initialized = copyHyperParams(session.getHyperParams());
        if (request.getHyperParams() != null
                && !initialized.equals(request.getHyperParams())) {
            throw new IllegalArgumentException("hyperParams 与上传初始化信息不一致");
        }
        return request.getHyperParams() == null
                ? initialized
                : copyHyperParams(request.getHyperParams());
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void validateModelFileName(String fileName) {
        String lower = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".zip")
                || lower.endsWith(".safetensors")
                || lower.endsWith(".pt")
                || lower.endsWith(".pth")
                || lower.endsWith(".ckpt")
                || lower.endsWith(".onnx"))) {
            throw new IllegalArgumentException("模型文件仅支持 zip 压缩包");
        }
    }

    private void removeObjectQuietly(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return;
        }
        try {
            minioDeleteTaskService.enqueueDefaultBucketDeleteImmediately(
                    objectName,
                    MinioDeleteTaskService.SOURCE_MODEL_UPLOAD_ROLLBACK,
                    objectName,
                    null
            );
        } catch (Exception ignored) {
            // 保留原始错误。
        }
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? e.getMessage() : current.getMessage();
    }

    private boolean isDuplicateVersionError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("uk_model_version_asset_version")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private IllegalArgumentException duplicateVersion(String assetId, String version) {
        return new IllegalArgumentException(
                "model version already exists for asset: " + assetId + ", version: " + version
        );
    }

    private String sanitizeSegment(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "unnamed";
        }
        return normalized
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .toLowerCase(Locale.ROOT);
    }

    private void requireCompatibleArtifactSpec(String assetId, String artifactSpecId) {
        modelVersionRepo
                .findFirstByAssetIdAndArtifactSpecIdIsNotNullAndDeletedFalseOrderByCreatedAtDesc(
                        assetId
                )
                .ifPresent(existing -> {
                    if (!Objects.equals(existing.getArtifactSpecId(), artifactSpecId)) {
                        throw new IllegalArgumentException(
                                "model artifact specification does not match existing asset versions"
                        );
                    }
                });
    }

    private record CompletionTarget(
            ModelAsset asset,
            String assetId,
            Integer ownerUserId,
            String taskType,
            boolean createAsset
    ) {
    }

    private record ChunkPersistenceResult(
            ModelUploadSession session,
            boolean persisted
    ) {
    }

    private record CompletionPlan(
            ModelUploadSession session,
            CompletionTarget target,
            List<ModelUploadChunk> chunks,
            String versionId,
            String destName,
            String version,
            boolean alreadyCompleted
    ) {
        private static CompletionPlan completed(ModelUploadSession session) {
            return new CompletionPlan(
                    session,
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    true
            );
        }
    }

    private record CompletionPersistenceResult(
            ModelUploadSession session,
            ModelAsset asset,
            ModelVersion version
    ) {
    }

    private record ModelBusinessMetadata(
            String targetAssetId,
            String modelName,
            String taskType,
            String remark
    ) {
    }
}
