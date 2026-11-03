package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.v2.V2DatasetUploadCompleteRequest;
import com.tss.platform.dto.v2.V2DatasetWorkspaceFileUploadInitRequest;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DatasetWorkspaceFileUploadService {

    private static final DatasetWorkspaceResourcePolicy FIELD_POLICY = new DatasetWorkspaceResourcePolicy("INVALID_UPLOAD_REQUEST");

    public static final String PURPOSE = "WORKSPACE_FILE";
    private static final Set<String> TARGET_KINDS =
            Set.of("DATA", "ANNOTATION");
    private static final Set<String> TARGET_OPERATIONS =
            Set.of("CREATE", "REPLACE");

    private final DatasetWorkspaceCommandService commandService;
    private final DatasetWorkspaceTextFilePolicy textFilePolicy;
    private final DatasetWorkspaceRawStorageService rawStorageService;
    private final V2DatasetWorkspaceResourceService resourceService;
    private final DatasetWorkspaceAuditService auditService;
    private final DatasetUploadSessionRepository sessionRepo;
    private final DatasetUploadChunkRepository chunkRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final MinioDeleteTaskService deleteTaskService;
    private final MinioClient minioClient;
    private final String bucket;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public DatasetWorkspaceFileUploadService(
            DatasetWorkspaceCommandService commandService,
            DatasetWorkspaceTextFilePolicy textFilePolicy,
            DatasetWorkspaceRawStorageService rawStorageService,
            V2DatasetWorkspaceResourceService resourceService,
            DatasetWorkspaceAuditService auditService,
            DatasetUploadSessionRepository sessionRepo,
            DatasetUploadChunkRepository chunkRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            MinioDeleteTaskService deleteTaskService,
            MinioClient minioClient,
            MinioConfig minioConfig,
            PlatformTransactionManager transactionManager
    ) {
        this.commandService = commandService;
        this.textFilePolicy = textFilePolicy;
        this.rawStorageService = rawStorageService;
        this.resourceService = resourceService;
        this.auditService = auditService;
        this.sessionRepo = sessionRepo;
        this.chunkRepo = chunkRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.deleteTaskService = deleteTaskService;
        this.minioClient = minioClient;
        this.bucket = minioConfig.getBucket();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate =
                new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    @Transactional
    public DatasetUploadSession init(
            String workspaceId,
            V2DatasetWorkspaceFileUploadInitRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        request.expectedWorkspaceRevision()
                );
        String targetKind = enumValue(
                request.targetKind(),
                TARGET_KINDS,
                "targetKind"
        );
        String targetOperation = enumValue(
                request.targetOperation(),
                TARGET_OPERATIONS,
                "targetOperation"
        );
        if (request.fileSize() == null || request.fileSize() <= 0) {
            throw invalid("fileSize 必须大于 0");
        }
        String sha256 = requireSha256(request.sha256());
        DatasetSample sample = requireActiveSample(
                workspaceId,
                request.sampleId()
        );

        ResourceDescriptor descriptor = resolveDescriptor(
                workspaceId,
                sample.getId(),
                targetKind,
                targetOperation,
                request
        );
        DatasetWorkspaceTextFilePolicy.Descriptor fileDescriptor =
                textFilePolicy.validateDescriptor(
                        descriptor.fileName(),
                        descriptor.format(),
                        descriptor.contentType()
                );

        DatasetUploadSession session = new DatasetUploadSession();
        session.setId("dataset-upload-" + compactUuid());
        session.setUploadPurpose(PURPOSE);
        session.setFileFingerprint(sha256);
        session.setFileName(fileDescriptor.fileName());
        session.setFileSize(request.fileSize());
        int chunkSize = DatasetUploadService.calculateChunkSize(
                request.fileSize()
        );
        session.setChunkSize(chunkSize);
        session.setTotalChunks(DatasetUploadService.calculateTotalChunks(
                request.fileSize(),
                chunkSize
        ));
        session.setDatasetName(access.asset().getName());
        session.setVersion(access.workspace().getVersion());
        session.setVersionLabel(access.workspace().getVersionLabel());
        session.setVersionNo(access.workspace().getVersionNo());
        session.setVersionLabelGenerated(false);
        session.setType(access.asset().getType());
        session.setCvTaskType(access.workspace().getCvTaskType());
        session.setAnnotationFormat(access.workspace().getAnnotationFormat());
        session.setDescription(access.workspace().getDescription());
        session.setChangeLog(access.workspace().getChangeLog());
        session.setParentVersionId(access.workspace().getParentVersionId());
        session.setStrictManifest(false);
        session.setAssetCreatedByUpload(false);
        session.setAssetId(access.asset().getId());
        session.setVersionId(workspaceId);
        session.setTargetKind(targetKind);
        session.setTargetOperation(targetOperation);
        session.setTargetSampleId(sample.getId());
        session.setTargetResourceId(descriptor.resourceId());
        session.setExpectedSha256(sha256);
        session.setDeclaredFormat(fileDescriptor.format());
        session.setDeclaredContentType(fileDescriptor.contentType());
        session.setTargetDataType(descriptor.dataType());
        session.setTargetSensor(descriptor.sensor());
        session.setTargetChannel(descriptor.channel());
        session.setTargetSeq(descriptor.seq());
        session.setTargetSampleDataId(descriptor.sampleDataId());
        session.setTargetAnnotationType(descriptor.annotationType());
        session.setTargetMetadata(copyMap(descriptor.metadata()));
        session.setOwnerUserId(access.asset().getOwnerUserId());
        session.setStatus("UPLOADING");
        Instant now = Instant.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);

        long nextRevision = commandService.revision(access.workspace()) + 1L;
        session.setWorkspaceBaseRevision(nextRevision);
        sessionRepo.saveAndFlush(session);
        long revision = commandService.incrementRevision(access.workspace());
        auditService.recordUserAction(
                access.asset(),
                access.workspace(),
                "WORKSPACE_FILE_UPLOAD_INIT",
                "UPLOAD_SESSION",
                session.getId(),
                null,
                sample.getId(),
                Map.of(
                        "workspaceRevision", revision,
                        "targetKind", targetKind,
                        "targetOperation", targetOperation
                )
        );
        return session;
    }

    public DatasetUploadSession complete(
            String uploadId,
            V2DatasetUploadCompleteRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        FileCompletionPlan plan = transactionTemplate.execute(status ->
                prepareCompletion(uploadId, request)
        );
        if (plan == null) {
            throw new V2BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATASET_UPLOAD_NOT_COMPLETABLE",
                    "工作区文件上传暂时无法完成"
            );
        }
        if (plan.alreadyCompleted()) {
            return plan.session();
        }

        try {
            CompletedObject completedObject = composeAndValidate(plan);
            FileCompletionResult result = transactionTemplate.execute(status ->
                    persistCompletion(plan, completedObject)
            );
            if (result == null) {
                throw new V2BusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "DATASET_UPLOAD_NOT_COMPLETABLE",
                        "工作区文件上传暂时无法完成"
                );
            }
            if (!result.objectUsed()) {
                cleanupFailedObject(
                        plan.objectName(),
                        plan.session().getId(),
                        plan.ownerUserId()
                );
            }
            return result.session();
        } catch (V2BusinessException exception) {
            cleanupFailedObject(
                    plan.objectName(),
                    plan.session().getId(),
                    plan.ownerUserId()
            );
            throw exception;
        } catch (Exception exception) {
            cleanupFailedObject(
                    plan.objectName(),
                    plan.session().getId(),
                    plan.ownerUserId()
            );
            throw new V2BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATASET_STORAGE_UNAVAILABLE",
                    "工作区文件合并或校验失败"
            );
        }
    }

    private FileCompletionPlan prepareCompletion(
            String uploadId,
            V2DatasetUploadCompleteRequest request
    ) {
        DatasetUploadSession snapshot = requireWorkspaceFileSession(uploadId);
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForOperationSettlement(
                        snapshot.getVersionId(),
                        request.expectedWorkspaceRevision(),
                        snapshot.getId()
                );
        DatasetUploadSession session = sessionRepo.findByIdForUpdate(uploadId)
                .orElseThrow(this::notFound);
        requireWorkspaceFileSession(session, access.workspace().getId());
        requireSessionOwner(session, access);
        if ("COMPLETED".equals(session.getStatus())) {
            return FileCompletionPlan.completed(session);
        }
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "UPLOAD_STATE_CONFLICT",
                    "上传任务当前状态不允许完成",
                    Map.of("status", session.getStatus())
            );
        }

        List<DatasetUploadChunk> chunks = requireCompleteChunks(session);
        String objectName = finalObjectName(session);
        List<ChunkSnapshot> chunkSnapshots = chunks.stream()
                .map(ChunkSnapshot::from)
                .toList();
        return new FileCompletionPlan(
                session,
                access.workspace().getId(),
                request.expectedWorkspaceRevision(),
                objectName,
                session.getFileSize(),
                session.getExpectedSha256(),
                session.getOwnerUserId(),
                chunkSnapshots,
                false
        );
    }

    private CompletedObject composeAndValidate(FileCompletionPlan plan)
            throws Exception {
        List<ComposeSource> sources = plan.chunks().stream()
                .map(chunk -> ComposeSource.builder()
                        .bucket(bucket)
                        .object(chunk.objectName())
                        .build())
                .toList();
        minioClient.composeObject(
                ComposeObjectArgs.builder()
                        .bucket(bucket)
                        .object(plan.objectName())
                        .sources(sources)
                        .build()
        );
        StatObjectResponse stat = minioClient.statObject(
                StatObjectArgs.builder()
                        .bucket(bucket)
                        .object(plan.objectName())
                        .build()
        );
        if (stat.size() != plan.fileSize()) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UPLOAD_SIZE_MISMATCH",
                    "合并后文件大小与初始化声明不一致"
            );
        }
        String actualSha256;
        try (InputStream inputStream = minioClient.getObject(
                io.minio.GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(plan.objectName())
                        .build()
        )) {
            actualSha256 = sha256(inputStream);
        }
        if (!plan.expectedSha256().equalsIgnoreCase(actualSha256)) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UPLOAD_CHECKSUM_MISMATCH",
                    "合并后文件 SHA-256 与初始化声明不一致"
            );
        }
        return new CompletedObject(stat.size(), actualSha256);
    }

    private FileCompletionResult persistCompletion(
            FileCompletionPlan plan,
            CompletedObject completedObject
    ) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForOperationSettlement(
                        plan.workspaceId(),
                        plan.expectedWorkspaceRevision(),
                        plan.session().getId()
                );
        DatasetUploadSession session = sessionRepo
                .findByIdForUpdate(plan.session().getId())
                .orElseThrow(this::notFound);
        requireWorkspaceFileSession(session, access.workspace().getId());
        requireSessionOwner(session, access);
        if ("COMPLETED".equals(session.getStatus())) {
            return new FileCompletionResult(session, false);
        }
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "UPLOAD_STATE_CONFLICT",
                    "上传任务当前状态不允许完成",
                    Map.of("status", session.getStatus())
            );
        }
        List<DatasetUploadChunk> currentChunks = requireCompleteChunks(session);
        if (!sameChunks(plan.chunks(), currentChunks)
                || session.getFileSize() != plan.fileSize()
                || !java.util.Objects.equals(
                        session.getExpectedSha256(),
                        plan.expectedSha256()
                )) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "UPLOAD_CHANGED_DURING_COMPLETION",
                    "上传分片在合并期间发生变化，请重新完成上传"
            );
        }

        DatasetPackage datasetPackage = rawStorageService.attachRawObject(
                access.asset(),
                access.workspace(),
                plan.objectName(),
                session.getFileName(),
                completedObject.sizeBytes(),
                completedObject.sha256()
        );
        V2DatasetWorkspaceResourceService.AttachedResource attached =
                resourceService.attachUploadedFile(
                        access,
                        session,
                        datasetPackage,
                        completedObject.sizeBytes(),
                        completedObject.sha256()
                );
        session.setTargetResourceId(attached.resourceId());
        session.setStoragePath(plan.objectName());
        session.setStatus("COMPLETED");
        session.setUpdatedAt(Instant.now());
        sessionRepo.saveAndFlush(session);
        long revision = commandService.incrementRevision(access.workspace());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workspaceRevision", revision);
        details.put("targetKind", attached.targetKind());
        details.put("targetOperation", session.getTargetOperation());
        details.put("checksum", completedObject.sha256());
        auditService.recordUserAction(
                access.asset(),
                access.workspace(),
                "WORKSPACE_FILE_UPLOAD_COMPLETED",
                attached.targetKind(),
                attached.resourceId(),
                datasetPackage.getId(),
                attached.sampleId(),
                details
        );
        registerChunkCleanup(session, currentChunks);
        return new FileCompletionResult(session, true);
    }

    private boolean sameChunks(
            List<ChunkSnapshot> expected,
            List<DatasetUploadChunk> actual
    ) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            ChunkSnapshot left = expected.get(index);
            DatasetUploadChunk right = actual.get(index);
            if (!java.util.Objects.equals(left.partIndex(), right.getPartIndex())
                    || !java.util.Objects.equals(
                            left.objectName(),
                            right.getObjectName()
                    )
                    || !java.util.Objects.equals(
                            left.sizeBytes(),
                            right.getSizeBytes()
                    )
                    || !java.util.Objects.equals(left.etag(), right.getEtag())) {
                return false;
            }
        }
        return true;
    }

    @Transactional
    public DatasetUploadSession cancel(
            String uploadId,
            V2DatasetUploadCompleteRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetUploadSession snapshot = requireWorkspaceFileSession(uploadId);
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForOperationSettlement(
                        snapshot.getVersionId(),
                        request.expectedWorkspaceRevision(),
                        snapshot.getId()
                );
        DatasetUploadSession session = sessionRepo.findByIdForUpdate(uploadId)
                .orElseThrow(this::notFound);
        requireWorkspaceFileSession(session, access.workspace().getId());
        requireSessionOwner(session, access);
        if ("DISCARDED".equals(session.getStatus())) {
            return session;
        }
        if ("COMPLETED".equals(session.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "UPLOAD_STATE_CONFLICT",
                    "已完成上传不能取消"
            );
        }
        List<DatasetUploadChunk> chunks =
                chunkRepo.findByUploadIdOrderByPartIndexAsc(uploadId);
        session.setStatus("DISCARDED");
        session.setUpdatedAt(Instant.now());
        sessionRepo.saveAndFlush(session);
        long revision = commandService.incrementRevision(access.workspace());
        auditService.recordUserAction(
                access.asset(),
                access.workspace(),
                "WORKSPACE_FILE_UPLOAD_CANCELLED",
                "UPLOAD_SESSION",
                session.getId(),
                null,
                session.getTargetSampleId(),
                Map.of("workspaceRevision", revision)
        );
        registerChunkCleanup(session, chunks);
        return session;
    }

    private ResourceDescriptor resolveDescriptor(
            String workspaceId,
            String sampleId,
            String targetKind,
            String targetOperation,
            V2DatasetWorkspaceFileUploadInitRequest request
    ) {
        if ("DATA".equals(targetKind)) {
            DatasetSampleData existing = null;
            if ("REPLACE".equals(targetOperation)) {
                existing = dataRepo.findByIdAndDatasetVersionIdForUpdate(
                                requireText(request.resourceId(), "resourceId 不能为空", 64),
                                workspaceId
                        )
                        .orElseThrow(() -> resourceNotFound("数据组件不存在"));
                if (!sampleId.equals(existing.getSampleId())
                        || Boolean.TRUE.equals(existing.getDeleted())) {
                    throw resourceNotFound("数据组件不存在");
                }
            } else if (request.resourceId() != null
                    && !request.resourceId().isBlank()) {
                throw invalid("CREATE 上传不能指定 resourceId");
            }
            return new ResourceDescriptor(
                    existing == null ? null : existing.getId(),
                    fallback(request.fileName(), existing == null
                            ? null : existing.getFileName()),
                    fallback(request.format(), existing == null
                            ? null : existing.getFormat()),
                    fallback(request.contentType(), existing == null
                            ? null : existing.getContentType()),
                    dataType(fallback(request.dataType(), existing == null
                            ? null : existing.getDataType())),
                    optionalText(fallback(request.sensor(), existing == null
                            ? null : existing.getSensor()), 64),
                    optionalText(fallback(request.channel(), existing == null
                            ? null : existing.getChannel()), 32),
                    nonNegative(request.seq() == null && existing != null
                            ? existing.getSeq() : request.seq(), "seq"),
                    null,
                    null,
                    request.metadata() == null && existing != null
                            ? existing.getMetadata()
                            : request.metadata()
            );
        }

        DatasetAnnotation existing = null;
        if ("REPLACE".equals(targetOperation)) {
            existing = annotationRepo.findByIdAndDatasetVersionIdForUpdate(
                            requireText(request.resourceId(), "resourceId 不能为空", 64),
                            workspaceId
                    )
                    .orElseThrow(() -> resourceNotFound("标注组件不存在"));
            if (!sampleId.equals(existing.getSampleId())
                    || Boolean.TRUE.equals(existing.getDeleted())) {
                throw resourceNotFound("标注组件不存在");
            }
        } else if (request.resourceId() != null
                && !request.resourceId().isBlank()) {
            throw invalid("CREATE 上传不能指定 resourceId");
        }
        String sampleDataId = optionalText(
                fallback(request.sampleDataId(), existing == null
                        ? null : existing.getSampleDataId()),
                64
        );
        validateAnnotationTarget(workspaceId, sampleId, sampleDataId);
        return new ResourceDescriptor(
                existing == null ? null : existing.getId(),
                fallback(request.fileName(), existing == null
                        ? null : existing.getFileName()),
                fallback(request.format(), existing == null
                        ? null : existing.getFormat()),
                fallback(request.contentType(), existing == null
                        ? null : existing.getContentType()),
                null,
                null,
                null,
                null,
                sampleDataId,
                requireText(
                        fallback(request.annotationType(), existing == null
                                ? null : existing.getAnnotationType()),
                        "annotationType 不能为空",
                        64
                ),
                request.metadata() == null && existing != null
                        ? existing.getMetadata()
                        : request.metadata()
        );
    }

    private DatasetSample requireActiveSample(
            String workspaceId,
            String sampleId
    ) {
        if (sampleId == null || sampleId.isBlank()) {
            throw resourceNotFound("样本不存在");
        }
        DatasetSample sample = sampleRepo.findByIdForUpdate(sampleId)
                .orElseThrow(() -> resourceNotFound("样本不存在"));
        if (!workspaceId.equals(sample.getDatasetVersionId())
                || Boolean.TRUE.equals(sample.getDeleted())) {
            throw resourceNotFound("样本不存在");
        }
        return sample;
    }

    private void validateAnnotationTarget(
            String workspaceId,
            String sampleId,
            String sampleDataId
    ) {
        DatasetWorkspaceResourcePolicy.validateAnnotationTarget(dataRepo, workspaceId, sampleId, sampleDataId);
    }

    private List<DatasetUploadChunk> requireCompleteChunks(
            DatasetUploadSession session
    ) {
        List<DatasetUploadChunk> chunks =
                chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId());
        if (chunks.size() != session.getTotalChunks()) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UPLOAD_INCOMPLETE",
                    "上传分片尚未全部完成"
            );
        }
        long totalBytes = 0;
        for (int index = 0; index < chunks.size(); index++) {
            DatasetUploadChunk chunk = chunks.get(index);
            if (chunk.getPartIndex() == null
                    || chunk.getPartIndex() != index
                    || chunk.getSizeBytes() == null
                    || chunk.getSizeBytes() <= 0) {
                throw new V2BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "UPLOAD_INCOMPLETE",
                        "上传分片编号或大小不完整"
                );
            }
            long expected = index == chunks.size() - 1
                    ? session.getFileSize()
                    - (long) session.getChunkSize() * index
                    : session.getChunkSize();
            if (chunk.getSizeBytes() != expected) {
                throw new V2BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "UPLOAD_CHUNK_SIZE_MISMATCH",
                        "上传分片大小不符合初始化约定"
                );
            }
            totalBytes += chunk.getSizeBytes();
        }
        if (totalBytes != session.getFileSize()) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UPLOAD_SIZE_MISMATCH",
                    "上传分片总大小与初始化声明不一致"
            );
        }
        return chunks;
    }

    private DatasetUploadSession requireWorkspaceFileSession(String uploadId) {
        DatasetUploadSession session = sessionRepo.findById(uploadId)
                .orElseThrow(this::notFound);
        requireWorkspaceFileSession(session, session.getVersionId());
        return session;
    }

    private void requireWorkspaceFileSession(
            DatasetUploadSession session,
            String workspaceId
    ) {
        if (!PURPOSE.equals(session.getUploadPurpose())
                || workspaceId == null
                || !workspaceId.equals(session.getVersionId())) {
            throw notFound();
        }
    }

    private void requireSessionOwner(
            DatasetUploadSession session,
            DatasetWorkspaceCommandService.WorkspaceAccess access
    ) {
        if (!access.asset().getId().equals(session.getAssetId())
                || !java.util.Objects.equals(
                        access.asset().getOwnerUserId(),
                        session.getOwnerUserId()
                )) {
            throw notFound();
        }
    }

    private String finalObjectName(DatasetUploadSession session) {
        return "users/" + session.getOwnerUserId()
                + "/datasets/" + session.getAssetId()
                + "/workspaces/" + session.getVersionId()
                + "/overlays/" + compactUuid()
                + "/" + session.getFileName();
    }

    private void registerChunkCleanup(
            DatasetUploadSession session,
            List<DatasetUploadChunk> chunks
    ) {
        List<String> objectNames = chunks.stream()
                .map(DatasetUploadChunk::getObjectName)
                .toList();
        Runnable cleanup = () -> {
            for (String objectName : objectNames) {
                try {
                    deleteTaskService.enqueueDefaultBucketDeleteImmediately(
                            objectName,
                            MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_CHUNK,
                            session.getId(),
                            session.getOwnerUserId()
                    );
                } catch (Exception ignored) {
                    // Cleanup tasks are retried independently.
                }
            }
            try {
                requiresNewTransactionTemplate.executeWithoutResult(status ->
                        chunkRepo.deleteByUploadId(session.getId())
                );
            } catch (Exception ignored) {
                // Metadata cleanup is recoverable and does not change the result.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cleanup.run();
                        }
                    }
            );
        } else {
            cleanup.run();
        }
    }

    private void cleanupFailedObject(
            String objectName,
            String uploadId,
            Integer ownerUserId
    ) {
        try {
            deleteTaskService.enqueueDefaultBucketDeleteImmediately(
                    objectName,
                    MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_ROLLBACK,
                    uploadId,
                    ownerUserId
            );
        } catch (Exception ignored) {
            // Preserve the original upload failure.
        }
    }

    private static String sha256(InputStream inputStream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String requireSha256(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) {
            throw invalid("sha256 必须是 64 位十六进制字符串");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String enumValue(
            String value,
            Set<String> allowed,
            String field
    ) {
        String normalized = requireText(
                value,
                field + " 不能为空",
                32
        ).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw invalid(field + " 不受支持");
        }
        return normalized;
    }

    private static String dataType(String value) {
        return FIELD_POLICY.dataType(value);
    }

    private static int nonNegative(Integer value, String field) {
        return FIELD_POLICY.nonNegative(value, field);
    }

    private static String requireText(
            String value,
            String message,
            int maxLength
    ) {
        return FIELD_POLICY.requiredText(value, message, maxLength);
    }

    private static String optionalText(String value, int maxLength) {
        return FIELD_POLICY.optionalText(value, maxLength);
    }

    private static String fallback(String value, String current) {
        return DatasetWorkspaceResourcePolicy.fallback(value, current);
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return DatasetWorkspaceResourcePolicy.copyMap(value);
    }

    private static V2BusinessException invalid(String message) {
        return new V2BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_UPLOAD_REQUEST",
                message
        );
    }

    private static V2BusinessException resourceNotFound(String message) {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "WORKSPACE_RESOURCE_NOT_FOUND",
                message
        );
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "DATASET_UPLOAD_NOT_FOUND",
                "数据集上传任务不存在或无权访问"
        );
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record FileCompletionPlan(
            DatasetUploadSession session,
            String workspaceId,
            Long expectedWorkspaceRevision,
            String objectName,
            long fileSize,
            String expectedSha256,
            Integer ownerUserId,
            List<ChunkSnapshot> chunks,
            boolean alreadyCompleted
    ) {
        private static FileCompletionPlan completed(
                DatasetUploadSession session
        ) {
            return new FileCompletionPlan(
                    session,
                    session.getVersionId(),
                    null,
                    null,
                    session.getFileSize() == null ? 0L : session.getFileSize(),
                    session.getExpectedSha256(),
                    session.getOwnerUserId(),
                    List.of(),
                    true
            );
        }
    }

    private record ChunkSnapshot(
            Integer partIndex,
            String objectName,
            Long sizeBytes,
            String etag
    ) {
        private static ChunkSnapshot from(DatasetUploadChunk chunk) {
            return new ChunkSnapshot(
                    chunk.getPartIndex(),
                    chunk.getObjectName(),
                    chunk.getSizeBytes(),
                    chunk.getEtag()
            );
        }
    }

    private record CompletedObject(long sizeBytes, String sha256) {
    }

    private record FileCompletionResult(
            DatasetUploadSession session,
            boolean objectUsed
    ) {
    }

    private record ResourceDescriptor(
            String resourceId,
            String fileName,
            String format,
            String contentType,
            String dataType,
            String sensor,
            String channel,
            Integer seq,
            String sampleDataId,
            String annotationType,
            Map<String, Object> metadata
    ) {
    }
}
