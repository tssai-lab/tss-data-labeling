package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadInitRequest;
import com.tss.platform.dto.DatasetUploadProgressDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.model.CvAnnotationFormat;
import com.tss.platform.model.CvTaskType;
import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.repository.UploadChunkProgressSummary;
import com.tss.platform.security.AuthContext;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 数据集上传编排：管理上传会话、分片、版本发布与失败补偿。
 *
 * <p>数据库与对象存储不共享事务。完成上传时先在短事务内占用会话并创建草稿，
 * 再于事务外合并/校验对象，最后在短事务内发布版本。失败后使用对应补偿路径；
 * 不能给整个完成流程简单加上一个长事务，也不能仅删除数据库记录而遗留对象。</p>
 * <p>所有权检查由 getSession 等入口统一执行；并发完成请求由条件更新争抢，
 * 已完成会话返回原结果，不重复创建版本。</p>
 */
@Service
public class DatasetUploadService {

    private static final String STATUS_UPLOADING = "UPLOADING";
    private static final String STATUS_COMPLETING = "COMPLETING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String VERSION_STATUS_DRAFT = "DRAFT";
    private static final String VERSION_STATUS_READY = "READY";
    private static final String IMPORT_STATUS_PENDING = "PENDING";
    private static final String UPLOAD_PURPOSE_INITIAL = "INITIAL_DATASET";
    private static final String UPLOAD_PURPOSE_APPEND = "APPEND_PACKAGE";
    private static final String GROUPING_MANIFEST = "MANIFEST";
    private static final String GROUPING_AUTO_DIRECTORY = "AUTO_DIRECTORY";
    private static final String PACKAGE_ROLE_APPEND = "APPEND";

    private final DatasetUploadObjectStore objectStore;
    private final String bucket;
    private final DatasetUploadSessionRepository sessionRepo;
    private final DatasetUploadChunkRepository chunkRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetPackageRepository packageRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final ImportJobRepository importJobRepo;
    private final AuthContext authContext;
    private final MinioDeleteTaskService minioDeleteTaskService;
    private final TransactionTemplate transactionTemplate;
    private final DatasetWorkspaceAuditService auditService;
    private final DatasetZipValidator datasetZipValidator;
    private ImportJobLauncher importJobLauncher;
    private SingleModalDatasetIndexer singleModalDatasetIndexer;
    private ZipCentralDirectoryReader zipCentralDirectoryReader;

    @Autowired
    public DatasetUploadService(
            MinioClient minioClient,
            MinioConfig minioConfig,
            DatasetUploadSessionRepository sessionRepo,
            DatasetUploadChunkRepository chunkRepo,
            DatasetAssetRepository assetRepo,
            DatasetVersionRepository versionRepo,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            ImportJobRepository importJobRepo,
            AuthContext authContext,
            MinioDeleteTaskService minioDeleteTaskService,
            PlatformTransactionManager transactionManager,
            DatasetWorkspaceAuditService auditService
    ) {
        this.bucket = minioConfig.getBucket();
        this.objectStore = new DatasetUploadObjectStore(minioClient, this.bucket);
        this.sessionRepo = sessionRepo;
        this.chunkRepo = chunkRepo;
        this.assetRepo = assetRepo;
        this.versionRepo = versionRepo;
        this.packageRepo = packageRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.importJobRepo = importJobRepo;
        this.authContext = authContext;
        this.minioDeleteTaskService = minioDeleteTaskService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.auditService = auditService;
        this.datasetZipValidator = new DatasetZipValidator(minioClient, this.bucket);
    }

    public DatasetUploadService(
            MinioClient minioClient,
            MinioConfig minioConfig,
            DatasetUploadSessionRepository sessionRepo,
            DatasetUploadChunkRepository chunkRepo,
            DatasetAssetRepository assetRepo,
            DatasetVersionRepository versionRepo,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            ImportJobRepository importJobRepo,
            AuthContext authContext,
            MinioDeleteTaskService minioDeleteTaskService,
            PlatformTransactionManager transactionManager
    ) {
        this(
                minioClient,
                minioConfig,
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                packageRepo,
                versionPackageRepo,
                importJobRepo,
                authContext,
                minioDeleteTaskService,
                transactionManager,
                null
        );
    }

    @Autowired
    void setImportJobLauncher(ImportJobLauncher importJobLauncher) {
        this.importJobLauncher = importJobLauncher;
    }

    @Autowired(required = false)
    void setSingleModalDatasetIndexer(SingleModalDatasetIndexer singleModalDatasetIndexer) {
        this.singleModalDatasetIndexer = singleModalDatasetIndexer;
    }

    @Autowired(required = false)
    void setZipCentralDirectoryReader(ZipCentralDirectoryReader zipCentralDirectoryReader) {
        this.zipCentralDirectoryReader = zipCentralDirectoryReader;
        this.datasetZipValidator.setZipCentralDirectoryReader(zipCentralDirectoryReader);
    }

    DatasetUploadService(
            MinioClient minioClient,
            MinioConfig minioConfig,
            DatasetUploadSessionRepository sessionRepo,
            DatasetUploadChunkRepository chunkRepo,
            DatasetAssetRepository assetRepo,
            DatasetVersionRepository versionRepo,
            AuthContext authContext,
            MinioDeleteTaskService minioDeleteTaskService
    ) {
        this(
                minioClient,
                minioConfig,
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                null,
                null,
                null,
                authContext,
                minioDeleteTaskService,
                new PlatformTransactionManager() {
                    @Override
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return new SimpleTransactionStatus();
                    }

                    @Override
                    public void commit(TransactionStatus status) {
                    }

                    @Override
                    public void rollback(TransactionStatus status) {
                    }
                },
                null
        );
    }

    @Transactional
    public DatasetUploadProgressDto init(DatasetUploadInitRequest req) {
        validateInit(req);
        Integer operatorUserId = authContext.currentUserId();
        String taskType = DatasetTaskType.normalize(req.getType());
        String sampleGrouping = normalizeSampleGroupingForTask(
                taskType,
                req.getSampleGrouping()
        );
        String manifestPath = normalizeManifestPath(sampleGrouping, req.getManifestPath());
        boolean strictManifest = normalizeStrictManifestForTask(
                taskType,
                sampleGrouping,
                req.getStrictManifest()
        );
        validateGroupingForTask(taskType, sampleGrouping);
        String cvTaskType = CvTaskType.normalizeForTask(taskType, req.getCvTaskType());
        String annotationFormat = CvAnnotationFormat.normalizeForTask(taskType, req.getAnnotationFormat());
        DatasetAsset targetAsset = resolveTargetAsset(req.getAssetId(), taskType, cvTaskType, annotationFormat);
        Integer ownerUserId = targetAsset != null ? targetAsset.getOwnerUserId() : operatorUserId;
        String targetAssetId = targetAsset != null ? targetAsset.getId() : null;
        String datasetName = targetAsset != null
                ? targetAsset.getName()
                : requireUniqueNewAssetName(operatorUserId, req.getDatasetName());
        Integer previewVersionNo = previewVersionNo(targetAssetId);
        boolean versionLabelGenerated = isVersionLabelGenerated(req.getVersionLabel(), req.getVersion());
        String requestedLabel = defaultVersionLabel(req.getVersionLabel(), req.getVersion(), previewVersionNo);
        String parentVersionId = resolveParentVersionId(req.getParentVersionId(), targetAsset);
        validateDatasetFileName(taskType, req.getFileName());
        String fingerprint = normalizeText(req.getFileFingerprint());
        if (fingerprint != null) {
            DatasetUploadSession existing = sessionRepo
                    .findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(
                            fingerprint,
                            List.of(STATUS_UPLOADING, STATUS_FAILED),
                            ownerUserId
                    )
                    .orElse(null);
            if (existing != null && sameUpload(existing, req, taskType, parentVersionId)) {
                existing.setRemark(req.getRemark());
                existing.setUpdatedAt(Instant.now());
                return progress(sessionRepo.save(existing));
            }
        }

        DatasetUploadSession session = new DatasetUploadSession();
        session.setId("dataset-upload-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", ""));
        session.setUploadPurpose(UPLOAD_PURPOSE_INITIAL);
        session.setFileFingerprint(fingerprint);
        session.setFileName(req.getFileName().trim());
        session.setFileSize(req.getFileSize());
        int chunkSize = calculateChunkSize(req.getFileSize());
        session.setChunkSize(chunkSize);
        session.setTotalChunks(calculateTotalChunks(req.getFileSize(), chunkSize));
        session.setDatasetName(datasetName);
        session.setVersion(requestedLabel);
        session.setVersionLabel(requestedLabel);
        session.setVersionNo(previewVersionNo);
        session.setVersionLabelGenerated(versionLabelGenerated);
        session.setType(taskType);
        session.setCvTaskType(cvTaskType);
        session.setAnnotationFormat(annotationFormat);
        session.setRemark(req.getRemark());
        session.setDescription(req.getDescription());
        session.setChangeLog(req.getChangeLog());
        session.setParentVersionId(parentVersionId);
        session.setSampleGrouping(sampleGrouping);
        session.setManifestPath(manifestPath);
        session.setStrictManifest(strictManifest);
        session.setAssetCreatedByUpload(false);
        session.setAssetId(targetAssetId);
        session.setStatus(STATUS_UPLOADING);
        session.setOwnerUserId(ownerUserId);
        Instant now = Instant.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return progress(sessionRepo.save(session));
    }

    @Transactional
    public DatasetUploadProgressDto initAppendPackage(
            String draftVersionId,
            DatasetPackageAppendInitRequest req
    ) {
        if (req == null) {
            throw new IllegalArgumentException("request body cannot be null");
        }
        requireText(req.getFileName(), "fileName cannot be blank");
        if (req.getFileSize() == null || req.getFileSize() <= 0) {
            throw new IllegalArgumentException("fileSize must be greater than 0");
        }
        DatasetVersion draft = requireAppendDraft(draftVersionId);
        DatasetAsset asset = requireAppendAsset(draft);
        String taskType = DatasetTaskType.normalize(asset.getType());
        String sampleGrouping = normalizeSampleGroupingForTask(
                taskType,
                req.getSampleGrouping()
        );
        String manifestPath = normalizeManifestPath(sampleGrouping, req.getManifestPath());
        boolean strictManifest = normalizeStrictManifestForTask(
                taskType,
                sampleGrouping,
                req.getStrictManifest()
        );
        validateGroupingForTask(taskType, sampleGrouping);
        validateAppendPackageFileNameForTask(taskType, req.getFileName());

        DatasetUploadSession session = new DatasetUploadSession();
        session.setId("dataset-upload-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", ""));
        session.setUploadPurpose(UPLOAD_PURPOSE_APPEND);
        session.setFileFingerprint(normalizeText(req.getFileFingerprint()));
        session.setFileName(req.getFileName().trim());
        session.setFileSize(req.getFileSize());
        int chunkSize = calculateChunkSize(req.getFileSize());
        session.setChunkSize(chunkSize);
        session.setTotalChunks(calculateTotalChunks(req.getFileSize(), chunkSize));
        session.setDatasetName(asset.getName());
        session.setVersion(draft.getVersion());
        session.setVersionLabel(draft.getVersionLabel());
        session.setVersionNo(draft.getVersionNo());
        session.setVersionLabelGenerated(false);
        session.setType(taskType);
        session.setCvTaskType(draft.getCvTaskType());
        session.setAnnotationFormat(draft.getAnnotationFormat());
        session.setParentVersionId(draft.getParentVersionId());
        session.setSampleGrouping(sampleGrouping);
        session.setManifestPath(manifestPath);
        session.setStrictManifest(strictManifest);
        session.setAssetCreatedByUpload(false);
        session.setAssetId(asset.getId());
        session.setVersionId(draft.getId());
        session.setStatus(STATUS_UPLOADING);
        session.setOwnerUserId(asset.getOwnerUserId());
        Instant now = Instant.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        DatasetUploadSession saved = sessionRepo.save(session);
        if (auditService != null) {
            auditService.recordAppendInit(asset, draft, saved);
        }
        return progress(saved);
    }

    public DatasetUploadProgressDto saveChunk(String uploadId, Integer partIndex, MultipartFile file) {
        DatasetUploadSession snapshot = getSession(uploadId);
        if (STATUS_COMPLETED.equals(snapshot.getStatus())) {
            return progress(snapshot);
        }
        if (!STATUS_UPLOADING.equals(snapshot.getStatus())
                && !isRetryableFailedInitial(snapshot)) {
            throw new IllegalArgumentException(
                    "上传状态不允许继续上传分片: " + snapshot.getStatus()
            );
        }
        if (partIndex == null || partIndex < 0 || partIndex >= snapshot.getTotalChunks()) {
            throw new IllegalArgumentException("partIndex 超出范围");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("分片文件不能为空");
        }
        long expectedSize = partIndex < snapshot.getTotalChunks() - 1
                ? snapshot.getChunkSize()
                : snapshot.getFileSize()
                        - (long) snapshot.getChunkSize()
                        * (snapshot.getTotalChunks() - 1);
        if (file.getSize() != expectedSize) {
            throw new IllegalArgumentException(
                    "分片大小必须等于预期大小: expected=" + expectedSize
            );
        }

        String objectName = "users/" + snapshot.getOwnerUserId()
                + "/datasets/_uploads/" + uploadId
                + "/part-" + partIndex + "-"
                + UUID.randomUUID().toString().replace("-", "");
        StatObjectResponse stat;
        boolean objectUploaded = false;
        try (InputStream is = file.getInputStream()) {
            objectStore.put(is, objectName, file.getSize(), MediaType.APPLICATION_OCTET_STREAM_VALUE);
            objectUploaded = true;
            stat = objectStore.stat(objectName);
        } catch (Exception e) {
            if (objectUploaded) {
                removeObjectQuietly(objectName);
            }
            throw new IllegalArgumentException("分片上传失败: " + e.getMessage());
        }
        if (stat.size() != expectedSize) {
            removeObjectQuietly(objectName);
            throw new IllegalArgumentException("上传后分片大小与预期大小不一致");
        }

        try {
            ChunkPersistenceResult result = transactionTemplate.execute(status ->
                    persistUploadedChunk(
                            uploadId,
                            partIndex,
                            expectedSize,
                            objectName,
                            stat.etag()
                    )
            );
            if (result == null) {
                throw new IllegalArgumentException("保存分片上传记录失败");
            }
            if (!result.persisted()) {
                removeObjectQuietly(objectName);
            }
            return progress(result.session());
        } catch (RuntimeException exception) {
            removeObjectQuietly(objectName);
            throw new IllegalArgumentException(
                    "分片上传失败: " + rootMessage(exception)
            );
        }
    }

    private ChunkPersistenceResult persistUploadedChunk(
            String uploadId,
            Integer partIndex,
            long sizeBytes,
            String objectName,
            String etag
    ) {
        DatasetUploadSession session = sessionRepo.findByIdForUpdate(uploadId)
                .orElseThrow(() -> new IllegalArgumentException("uploadId 无效"));
        authContext.requireOwnerAccess(
                session.getOwnerUserId(),
                "uploadId invalid or not accessible"
        );
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return new ChunkPersistenceResult(session, false);
        }
        if (isRetryableFailedInitial(session)) {
            session.setStatus(STATUS_UPLOADING);
            clearCompletionFailure(session);
        } else if (!STATUS_UPLOADING.equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "上传状态不允许继续上传分片: " + session.getStatus()
            );
        }

        DatasetUploadChunk chunk = chunkRepo
                .findByUploadIdAndPartIndex(uploadId, partIndex)
                .orElseGet(() -> {
                    DatasetUploadChunk value = new DatasetUploadChunk();
                    value.setId(
                            "dataset-chunk-"
                                    + UUID.randomUUID().toString().replace("-", "")
                    );
                    value.setUploadId(uploadId);
                    value.setPartIndex(partIndex);
                    value.setCreatedAt(Instant.now());
                    return value;
                });
        String previousObjectName = chunk.getObjectName();
        chunk.setObjectName(objectName);
        chunk.setSizeBytes(sizeBytes);
        chunk.setEtag(etag);
        chunkRepo.save(chunk);
        session.setUpdatedAt(Instant.now());
        DatasetUploadSession saved = sessionRepo.save(session);
        if (previousObjectName != null
                && !previousObjectName.isBlank()
                && !previousObjectName.equals(objectName)) {
            minioDeleteTaskService.enqueueDefaultBucketDelete(
                    previousObjectName,
                    MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_CHUNK,
                    uploadId,
                    session.getOwnerUserId()
            );
        }
        return new ChunkPersistenceResult(saved, true);
    }

    @Transactional(readOnly = true)
    public DatasetUploadProgressDto getProgress(String uploadId) {
        return progress(getSession(uploadId));
    }

    /** 根据会话用途分派完成流程；追加数据包有独立接口，不能混入首次上传。 */
    public Map<String, Object> complete(DatasetUploadCompleteRequest req) {
        if (req == null || req.getUploadId() == null || req.getUploadId().isBlank()) {
            throw new IllegalArgumentException("uploadId 不能为空");
        }
        DatasetUploadSession session = getSession(req.getUploadId());
        if (UPLOAD_PURPOSE_APPEND.equals(session.getUploadPurpose())) {
            throw new IllegalArgumentException(
                    "append package upload must use the package complete endpoint"
            );
        }
        if (isMultimodalImportUpload(session)) {
            return completeManifestUpload(session.getId());
        }
        return completeSingleModalUpload(session.getId());
    }

    /** 单模态：占位 → 合并与校验 → 发布；每个阶段失败由对应补偿逻辑保留可恢复状态。 */
    private Map<String, Object> completeSingleModalUpload(String uploadId) {
        DatasetUploadSession initial = getSession(uploadId);
        if (STATUS_COMPLETED.equals(initial.getStatus())) {
            return completedPayload(initial);
        }
        List<DatasetUploadChunk> chunks = requireCompleteChunks(initial);
        SingleModalReservation reservation = transactionTemplate.execute(
                status -> reserveSingleModalVersion(uploadId)
        );
        if (reservation == null) {
            throw new IllegalArgumentException("创建单模态数据集草稿失败");
        }
        if (STATUS_COMPLETED.equals(reservation.session().getStatus())) {
            return completedPayload(reservation.session());
        }

        String destinationObject = reservation.destinationObject();
        DatasetUploadSession completed;
        UploadFailure failure = storageFailure();
        try {
            objectStore.compose(chunks, destinationObject);
            failure = validationFailure();
            DatasetZipValidator.ValidationResult evidence = validateDatasetObjectWithEvidence(
                    reservation.session().getType(),
                    reservation.session().getCvTaskType(),
                    reservation.session().getAnnotationFormat(),
                    reservation.session().getFileName(),
                    destinationObject,
                    reservation.session().getFileSize()
            );
            reservation.version().setFileCount(evidence.fileCount());
            SingleModalDatasetIndexer.PreparedIndex preparedIndex =
                    singleModalDatasetIndexer == null
                            ? null
                            : singleModalDatasetIndexer.prepareIndex(
                                    reservation.asset(),
                                    reservation.version()
                            );
            failure = finalizationFailure();
            completed = transactionTemplate.execute(
                    status -> finalizeSingleModalUpload(
                            reservation,
                            evidence,
                            preparedIndex
                    )
            );
            if (completed == null) {
                throw new IllegalArgumentException("发布单模态数据集版本失败");
            }
        } catch (Exception exception) {
            removeObjectQuietly(destinationObject);
            UploadFailure persistedFailure = failure;
            try {
                transactionTemplate.executeWithoutResult(
                        status -> rollbackSingleModalReservation(
                                uploadId,
                                persistedFailure
                        )
                );
            } catch (RuntimeException ignored) {
                // 保留原始合并、校验或发布错误。
            }
            throw new DatasetUploadCompletionException(
                    failure.reasonCode(),
                    failure.userMessage(),
                    failure.details()
            );
        }

        registerChunkCleanup(uploadId, chunks);
        return completedPayload(completed);
    }

    public Map<String, Object> completeAppendPackage(
            String draftVersionId,
            DatasetUploadCompleteRequest req
    ) {
        if (req == null || req.getUploadId() == null || req.getUploadId().isBlank()) {
            throw new IllegalArgumentException("uploadId cannot be blank");
        }
        DatasetUploadSession initial = getSession(req.getUploadId());
        requireAppendSession(initial, draftVersionId);
        requireAppendDraft(draftVersionId);
        if (STATUS_COMPLETED.equals(initial.getStatus())) {
            launchPendingImport(initial);
            return appendCompletedPayload(initial);
        }

        List<DatasetUploadChunk> chunks = requireCompleteChunks(initial);
        DatasetUploadSession claimed = transactionTemplate.execute(
                status -> claimCompleting(initial.getId())
        );
        if (claimed == null) {
            throw new IllegalArgumentException("failed to claim append upload session");
        }
        requireAppendSession(claimed, draftVersionId);
        String destinationObject = appendPackageDestinationObject(claimed);
        DatasetUploadSession completed;
        try {
            objectStore.compose(chunks, destinationObject);
            StatObjectResponse stat = objectStore.stat(destinationObject);
            if (stat.size() != claimed.getFileSize()) {
                throw new IllegalArgumentException(
                        "composed append package size does not match upload session"
                );
            }
            if (!"MULTIMODAL".equals(DatasetTaskType.normalize(claimed.getType()))) {
                validateDatasetObjectFormat(
                        claimed.getType(),
                        claimed.getAnnotationFormat(),
                        claimed.getFileName(),
                        destinationObject,
                        stat.size()
                );
            }
            completed = transactionTemplate.execute(status ->
                    finalizeAppendPackage(
                            claimed.getId(),
                            draftVersionId,
                            destinationObject,
                            stat.size()
                    )
            );
            if (completed == null) {
                throw new IllegalArgumentException("failed to create append import job");
            }
        } catch (Exception exception) {
            removeObjectQuietly(destinationObject);
            transactionTemplate.executeWithoutResult(status ->
                    resetAppendSession(claimed.getId(), draftVersionId)
            );
            throw new IllegalArgumentException(
                    "failed to compose append package: " + rootMessage(exception)
            );
        }

        registerChunkCleanup(initial.getId(), chunks);
        Map<String, Object> response = appendCompletedPayload(completed);
        launchPendingImport(completed);
        return response;
    }

    private SingleModalReservation reserveSingleModalVersion(String uploadId) {
        DatasetUploadSession session = claimCompleting(uploadId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return new SingleModalReservation(session, null, null, session.getStoragePath());
        }

        boolean createAsset = session.getAssetId() == null || session.getAssetId().isBlank();
        String assetId = createAsset
                ? "dataset-asset-" + UUID.randomUUID().toString().replace("-", "")
                : session.getAssetId();
        VersionAllocation allocation = allocateVersion(session, assetId, createAsset);
        if (!createAsset) {
            requireNoActiveDraft(assetId);
        }
        requireUniqueVersionLabel(assetId, allocation.versionLabel());
        String versionId = "dataset-ver-" + UUID.randomUUID().toString().replace("-", "");
        String destName = "users/" + session.getOwnerUserId()
                + "/datasets/" + assetId + "/" + sanitizeSegment("v" + allocation.versionNo())
                + "/" + sanitizeSegment(session.getFileName());
        Instant now = Instant.now();
        DatasetAsset asset = allocation.asset();
        if (createAsset) {
            asset.setId(assetId);
            asset.setName(requireUniqueNewAssetName(
                    session.getOwnerUserId(),
                    session.getDatasetName()
            ));
            asset.setType(session.getType());
            asset.setCvTaskType(session.getCvTaskType());
            asset.setAnnotationFormat(session.getAnnotationFormat());
            asset.setRemark(session.getRemark());
            asset.setOwnerUserId(session.getOwnerUserId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            asset.setDeleted(false);
            saveNewAsset(asset);
        }

        DatasetVersion version = new DatasetVersion();
        version.setId(versionId);
        version.setAssetId(assetId);
        version.setVersionNo(allocation.versionNo());
        version.setVersionLabel(allocation.versionLabel());
        version.setVersion(allocation.versionLabel());
        version.setFileName(session.getFileName());
        version.setStoragePath(destName);
        version.setSizeBytes(session.getFileSize());
        version.setCvTaskType(session.getCvTaskType());
        version.setAnnotationFormat(session.getAnnotationFormat());
        version.setRemark(session.getRemark());
        version.setDescription(session.getDescription());
        version.setChangeLog(session.getChangeLog());
        version.setParentVersionId(allocation.parentVersionId());
        version.setStatus(VERSION_STATUS_DRAFT);
        version.setFileFingerprint(session.getFileFingerprint());
        version.setOwnerUserId(session.getOwnerUserId());
        version.setCreatedBy(authContext.currentUserId());
        version.setCreatedAt(now);
        version.setDeleted(false);
        try {
            versionRepo.saveAndFlush(version);
        } catch (DataIntegrityViolationException exception) {
            if (isOneActiveDraftViolation(exception)) {
                throw new IllegalArgumentException(
                        "dataset asset already has an active DRAFT version: " + assetId
                );
            }
            throw exception;
        }

        session.setAssetId(assetId);
        session.setVersionId(versionId);
        session.setVersionNo(allocation.versionNo());
        session.setVersionLabel(allocation.versionLabel());
        session.setVersion(allocation.versionLabel());
        session.setParentVersionId(allocation.parentVersionId());
        session.setAssetCreatedByUpload(createAsset);
        session.setUpdatedAt(now);
        sessionRepo.saveAndFlush(session);
        return new SingleModalReservation(session, asset, version, destName);
    }

    private DatasetUploadSession finalizeSingleModalUpload(
            SingleModalReservation reservation,
            DatasetZipValidator.ValidationResult evidence,
            SingleModalDatasetIndexer.PreparedIndex preparedIndex
    ) {
        DatasetUploadSession session = getSession(reservation.session().getId());
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return session;
        }
        if (!STATUS_COMPLETING.equals(session.getStatus())
                || !Objects.equals(session.getVersionId(), reservation.version().getId())) {
            throw new IllegalArgumentException("上传会话不处于可发布状态");
        }
        DatasetVersion version = versionRepo
                .findByIdAndDeletedFalseForUpdate(session.getVersionId())
                .orElseThrow(() -> new IllegalArgumentException("dataset version not found"));
        DatasetAsset asset = assetRepo
                .findByIdAndDeletedFalseForUpdate(session.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("dataset asset not found"));
        if (!VERSION_STATUS_DRAFT.equals(version.getStatus())) {
            throw new IllegalArgumentException("单模态数据集版本必须保持 DRAFT");
        }
        if (!Objects.equals(version.getStoragePath(), reservation.destinationObject())
                || !Objects.equals(version.getSizeBytes(), session.getFileSize())) {
            throw new IllegalArgumentException("数据集版本存储元数据与上传预留不一致");
        }

        Instant now = Instant.now();
        requireCompatibleArtifactSpec(asset.getId(), evidence.artifactSpecId());
        version.setFileCount(evidence.fileCount());
        version.setArtifactSha256(evidence.sha256());
        version.setArtifactSpecId(evidence.artifactSpecId());
        if (singleModalDatasetIndexer != null) {
            long consumableSamples = singleModalDatasetIndexer.persistPrepared(
                    asset,
                    version,
                    preparedIndex
            );
            if (consumableSamples <= 0) {
                throw new IllegalArgumentException("数据集版本没有可消费样本");
            }
        }
        version.setStatus(VERSION_STATUS_READY);
        version.setPublishedAt(now);
        versionRepo.saveAndFlush(version);

        session.setStatus(STATUS_COMPLETED);
        session.setArtifactSpecId(evidence.artifactSpecId());
        clearCompletionFailure(session);
        session.setStoragePath(reservation.destinationObject());
        session.setUpdatedAt(now);
        sessionRepo.saveAndFlush(session);
        asset.setCurrentVersionId(version.getId());
        asset.setUpdatedAt(now);
        assetRepo.saveAndFlush(asset);
        return session;
    }

    private void rollbackSingleModalReservation(
            String uploadId,
            UploadFailure failure
    ) {
        DatasetUploadSession session = sessionRepo.findById(uploadId).orElse(null);
        if (session == null || STATUS_COMPLETED.equals(session.getStatus())) {
            return;
        }
        String versionId = session.getVersionId();
        String assetId = session.getAssetId();
        boolean deleteAsset = Boolean.TRUE.equals(session.getAssetCreatedByUpload());

        applyCompletionFailure(session, failure);
        session.setStoragePath(null);
        session.setVersionId(null);
        session.setVersionNo(null);
        if (deleteAsset) {
            session.setAssetId(null);
        }
        session.setAssetCreatedByUpload(false);
        session.setUpdatedAt(Instant.now());
        sessionRepo.saveAndFlush(session);

        if (versionId != null) {
            versionRepo.findById(versionId).ifPresent(versionRepo::delete);
        }
        if (deleteAsset && assetId != null) {
            assetRepo.findById(assetId).ifPresent(assetRepo::delete);
        }
    }

    private Map<String, Object> completeManifestUpload(String uploadId) {
        DatasetUploadSession initial = getSession(uploadId);
        if (STATUS_COMPLETED.equals(initial.getStatus())) {
            launchPendingImport(initial);
            return completedPayload(initial);
        }
        List<DatasetUploadChunk> chunks = requireCompleteChunks(initial);
        ManifestReservation reservation = transactionTemplate.execute(
                status -> reserveManifestVersion(uploadId)
        );
        if (reservation == null) {
            throw new IllegalArgumentException("创建多模态数据集草稿失败");
        }
        if (STATUS_COMPLETED.equals(reservation.session().getStatus())) {
            return completedPayload(reservation.session());
        }

        String destName = reservation.destinationObject();
        DatasetUploadSession completed;
        UploadFailure failure = storageFailure();
        try {
            objectStore.compose(chunks, destName);
            StatObjectResponse stat = objectStore.stat(destName);
            if (stat.size() != reservation.session().getFileSize()) {
                throw new IllegalArgumentException("合并后文件大小与上传会话不一致");
            }

            failure = finalizationFailure();
            completed = transactionTemplate.execute(
                    status -> finalizeManifestUpload(uploadId, destName, stat.size())
            );
            if (completed == null) {
                throw new IllegalArgumentException("创建导入任务失败");
            }
        } catch (Exception e) {
            removeObjectQuietly(destName);
            UploadFailure persistedFailure = failure;
            try {
                transactionTemplate.executeWithoutResult(
                        status -> rollbackManifestReservation(
                                uploadId,
                                persistedFailure
                        )
                );
            } catch (RuntimeException ignored) {
                // 保留原始合并或落库错误。
            }
            throw new DatasetUploadCompletionException(
                    failure.reasonCode(),
                    failure.userMessage(),
                    failure.details()
            );
        }
        registerChunkCleanup(uploadId, chunks);
        launchPendingImport(completed);
        return completedPayload(completed);
    }

    private void launchPendingImport(DatasetUploadSession session) {
        if (importJobLauncher == null
                || importJobRepo == null
                || session.getImportJobId() == null
                || session.getImportJobId().isBlank()) {
            return;
        }
        String importJobId = session.getImportJobId();
        Runnable launch = () -> importJobRepo.findById(importJobId)
                .filter(job -> IMPORT_STATUS_PENDING.equals(job.getStatus()))
                .ifPresent(job -> importJobLauncher.launch(job.getId()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            launch.run();
                        }
                    }
            );
            return;
        }
        launch.run();
    }

    private ManifestReservation reserveManifestVersion(String uploadId) {
        DatasetUploadSession session = claimCompleting(uploadId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return new ManifestReservation(session, session.getStoragePath());
        }

        boolean createAsset = session.getAssetId() == null || session.getAssetId().isBlank();
        String assetId = createAsset
                ? "dataset-asset-" + UUID.randomUUID().toString().replace("-", "")
                : session.getAssetId();
        VersionAllocation allocation = allocateVersion(session, assetId, createAsset);
        if (!createAsset) {
            requireNoActiveDraft(assetId);
        }
        requireUniqueVersionLabel(assetId, allocation.versionLabel());
        String versionId = "dataset-ver-" + UUID.randomUUID().toString().replace("-", "");
        String destName = manifestDestinationObject(
                session.getOwnerUserId(),
                assetId,
                allocation.versionNo(),
                session.getFileName()
        );
        Instant now = Instant.now();

        DatasetAsset asset = allocation.asset();
        if (createAsset) {
            asset.setId(assetId);
            asset.setName(requireUniqueNewAssetName(
                    session.getOwnerUserId(),
                    session.getDatasetName()
            ));
            asset.setType(session.getType());
            asset.setCvTaskType(session.getCvTaskType());
            asset.setAnnotationFormat(session.getAnnotationFormat());
            asset.setRemark(session.getRemark());
            asset.setOwnerUserId(session.getOwnerUserId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            asset.setDeleted(false);
            saveNewAsset(asset);
        }

        DatasetVersion version = new DatasetVersion();
        version.setId(versionId);
        version.setAssetId(assetId);
        version.setVersionNo(allocation.versionNo());
        version.setVersionLabel(allocation.versionLabel());
        version.setVersion(allocation.versionLabel());
        version.setCvTaskType(session.getCvTaskType());
        version.setAnnotationFormat(session.getAnnotationFormat());
        version.setRemark(session.getRemark());
        version.setDescription(session.getDescription());
        version.setChangeLog(session.getChangeLog());
        version.setParentVersionId(allocation.parentVersionId());
        version.setStatus(VERSION_STATUS_DRAFT);
        version.setFileFingerprint(session.getFileFingerprint());
        version.setOwnerUserId(session.getOwnerUserId());
        version.setCreatedBy(authContext.currentUserId());
        version.setCreatedAt(now);
        version.setDeleted(false);
        try {
            versionRepo.saveAndFlush(version);
        } catch (DataIntegrityViolationException e) {
            if (isOneActiveDraftViolation(e)) {
                throw new IllegalArgumentException(
                        "dataset asset already has an active DRAFT version: " + assetId
                );
            }
            throw e;
        }

        session.setAssetId(assetId);
        session.setVersionId(versionId);
        session.setVersionNo(allocation.versionNo());
        session.setVersionLabel(allocation.versionLabel());
        session.setVersion(allocation.versionLabel());
        session.setParentVersionId(allocation.parentVersionId());
        session.setAssetCreatedByUpload(createAsset);
        session.setUpdatedAt(now);
        sessionRepo.saveAndFlush(session);
        return new ManifestReservation(session, destName);
    }

    private DatasetUploadSession finalizeManifestUpload(String uploadId, String storagePath, long sizeBytes) {
        DatasetUploadSession session = getSession(uploadId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return session;
        }
        if (!STATUS_COMPLETING.equals(session.getStatus()) || session.getVersionId() == null) {
            throw new IllegalArgumentException("上传会话不处于可完成状态");
        }
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(session.getVersionId())
                .orElseThrow(() -> new IllegalArgumentException("dataset version not found"));
        if (!VERSION_STATUS_DRAFT.equals(version.getStatus())) {
            throw new IllegalArgumentException("多模态版本必须保持 DRAFT");
        }

        Instant now = Instant.now();
        version.setFileName(session.getFileName());
        version.setStoragePath(storagePath);
        version.setSizeBytes(sizeBytes);
        versionRepo.saveAndFlush(version);

        DatasetPackage datasetPackage = new DatasetPackage();
        datasetPackage.setId("dataset-pkg-" + UUID.randomUUID().toString().replace("-", ""));
        datasetPackage.setDatasetAssetId(version.getAssetId());
        datasetPackage.setStoragePath(storagePath);
        datasetPackage.setFileName(session.getFileName());
        datasetPackage.setSizeBytes(sizeBytes);
        datasetPackage.setManifestPath(session.getManifestPath());
        datasetPackage.setStatus(VERSION_STATUS_READY);
        datasetPackage.setCreatedAt(now);
        datasetPackage.setDeleted(false);
        datasetPackage = packageRepo.saveAndFlush(datasetPackage);

        DatasetVersionPackage versionPackage = new DatasetVersionPackage();
        versionPackage.setDatasetVersionId(version.getId());
        versionPackage.setPackageId(datasetPackage.getId());
        versionPackage.setPackageRole("PRIMARY");
        versionPackage.setPackageOrder(0);
        versionPackage.setCreatedAt(now);
        versionPackageRepo.saveAndFlush(versionPackage);

        DatasetPackage primaryPackage = datasetPackage;
        ImportJob job = importJobRepo
                .findByDatasetVersionIdAndPackageId(version.getId(), primaryPackage.getId())
                .orElseGet(() -> {
                    ImportJob value = new ImportJob();
                    value.setId("ijob-" + UUID.randomUUID().toString().replace("-", ""));
                    value.setDatasetVersionId(version.getId());
                    value.setPackageId(primaryPackage.getId());
                    value.setStatus(IMPORT_STATUS_PENDING);
                    value.setProgress(0);
                    value.setImportedSamples(0);
                    value.setOwnerUserId(session.getOwnerUserId());
                    value.setCreatedAt(now);
                    value.setUpdatedAt(now);
                    return importJobRepo.saveAndFlush(value);
                });

        session.setStoragePath(storagePath);
        session.setImportJobId(job.getId());
        session.setStatus(STATUS_COMPLETED);
        clearCompletionFailure(session);
        session.setUpdatedAt(now);
        return sessionRepo.saveAndFlush(session);
    }

    private DatasetUploadSession finalizeAppendPackage(
            String uploadId,
            String draftVersionId,
            String storagePath,
            long sizeBytes
    ) {
        DatasetUploadSession session = getSession(uploadId);
        requireAppendSession(session, draftVersionId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return session;
        }
        if (!STATUS_COMPLETING.equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "append upload session is not completing: " + session.getStatus()
            );
        }

        DatasetVersion draft = versionRepo
                .findByIdAndDeletedFalseForUpdate(draftVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset workspace version not found or no permission"
                ));
        DatasetAsset asset = requireAppendAsset(draft);
        if (!VERSION_STATUS_DRAFT.equals(draft.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset version must be DRAFT: " + draft.getId()
            );
        }

        Instant now = Instant.now();
        DatasetPackage datasetPackage = new DatasetPackage();
        datasetPackage.setId("dataset-pkg-" + UUID.randomUUID().toString().replace("-", ""));
        datasetPackage.setDatasetAssetId(asset.getId());
        datasetPackage.setStoragePath(storagePath);
        datasetPackage.setFileName(session.getFileName());
        datasetPackage.setSizeBytes(sizeBytes);
        datasetPackage.setManifestPath(session.getManifestPath());
        datasetPackage.setStatus(IMPORT_STATUS_PENDING);
        datasetPackage.setCreatedAt(now);
        datasetPackage.setDeleted(false);
        datasetPackage = packageRepo.saveAndFlush(datasetPackage);

        Integer maxOrder = versionPackageRepo
                .findMaxPackageOrderByDatasetVersionId(draft.getId());
        DatasetVersionPackage relation = new DatasetVersionPackage();
        relation.setDatasetVersionId(draft.getId());
        relation.setPackageId(datasetPackage.getId());
        relation.setPackageRole(PACKAGE_ROLE_APPEND);
        relation.setPackageOrder((maxOrder == null ? -1 : maxOrder) + 1);
        relation.setCreatedAt(now);
        versionPackageRepo.saveAndFlush(relation);

        ImportJob job = new ImportJob();
        job.setId("ijob-" + UUID.randomUUID().toString().replace("-", ""));
        job.setDatasetVersionId(draft.getId());
        job.setPackageId(datasetPackage.getId());
        job.setStatus(IMPORT_STATUS_PENDING);
        job.setProgress(0);
        job.setImportedSamples(0);
        job.setOwnerUserId(asset.getOwnerUserId());
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job = importJobRepo.saveAndFlush(job);

        session.setStoragePath(storagePath);
        session.setImportJobId(job.getId());
        session.setStatus(STATUS_COMPLETED);
        session.setUpdatedAt(now);
        DatasetUploadSession saved = sessionRepo.saveAndFlush(session);
        if (auditService != null) {
            auditService.recordAppendCompleted(asset, draft, saved, datasetPackage, job);
        }
        return saved;
    }

    private void resetAppendSession(String uploadId, String draftVersionId) {
        DatasetUploadSession session = sessionRepo.findById(uploadId).orElse(null);
        if (session == null || STATUS_COMPLETED.equals(session.getStatus())) {
            return;
        }
        requireAppendSession(session, draftVersionId);
        session.setStatus(STATUS_UPLOADING);
        session.setStoragePath(null);
        session.setImportJobId(null);
        session.setUpdatedAt(Instant.now());
        sessionRepo.saveAndFlush(session);
    }

    private void rollbackManifestReservation(
            String uploadId,
            UploadFailure failure
    ) {
        DatasetUploadSession session = sessionRepo.findById(uploadId).orElse(null);
        if (session == null || STATUS_COMPLETED.equals(session.getStatus())) {
            return;
        }
        String versionId = session.getVersionId();
        String assetId = session.getAssetId();
        boolean deleteAsset = Boolean.TRUE.equals(session.getAssetCreatedByUpload());

        applyCompletionFailure(session, failure);
        session.setStoragePath(null);
        session.setVersionId(null);
        session.setVersionNo(null);
        session.setImportJobId(null);
        if (deleteAsset) {
            session.setAssetId(null);
        }
        session.setAssetCreatedByUpload(false);
        session.setUpdatedAt(Instant.now());
        sessionRepo.saveAndFlush(session);

        if (versionId != null) {
            versionRepo.findById(versionId).ifPresent(versionRepo::delete);
        }
        if (deleteAsset && assetId != null) {
            assetRepo.findById(assetId).ifPresent(assetRepo::delete);
        }
    }

    private List<DatasetUploadChunk> requireCompleteChunks(DatasetUploadSession session) {
        List<DatasetUploadChunk> chunks = chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId());
        if (chunks.size() != session.getTotalChunks()) {
            throw new IllegalArgumentException("分片未上传完成");
        }
        for (int i = 0; i < session.getTotalChunks(); i += 1) {
            if (!Integer.valueOf(i).equals(chunks.get(i).getPartIndex())) {
                throw new IllegalArgumentException("缺少分片: " + i);
            }
        }
        return chunks;
    }

    private boolean isMultimodalImportUpload(DatasetUploadSession session) {
        return "MULTIMODAL".equals(session.getType())
                && isMultimodalGrouping(session.getSampleGrouping());
    }

    public Map<String, Object> uploadCvFolder(
            String assetIdValue,
            String datasetName,
            String versionValue,
            String versionLabelValue,
            String type,
            String cvTaskTypeValue,
            String annotationFormatValue,
            String remark,
            String description,
            String changeLog,
            String parentVersionIdValue,
            List<MultipartFile> files,
            List<String> paths
    ) {
        String taskType = DatasetTaskType.normalize(type);
        String cvTaskType = CvTaskType.normalizeForTask(taskType, cvTaskTypeValue);
        String annotationFormat = CvAnnotationFormat.normalizeForTask(taskType, annotationFormatValue);
        DatasetAsset targetAsset = resolveTargetAsset(assetIdValue, taskType, cvTaskType, annotationFormat);
        if (targetAsset == null) {
            requireText(datasetName, "datasetName 不能为空");
        }
        if (!"CV".equals(taskType)) {
            throw new IllegalArgumentException("图片文件夹上传仅支持 CV 数据集");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("图片文件夹不能为空");
        }
        if (paths == null || paths.size() != files.size()) {
            throw new IllegalArgumentException("paths 必须与 files 一一对应");
        }

        Integer operatorUserId = authContext.currentUserId();
        Path tempZip = null;
        FolderUploadReservation reservation = null;

        try {
            String targetAssetId = targetAsset == null ? null : targetAsset.getId();
            reservation = transactionTemplate.execute(status -> reserveFolderUpload(
                    targetAssetId,
                    datasetName,
                    versionValue,
                    versionLabelValue,
                    taskType,
                    cvTaskType,
                    annotationFormat,
                    remark,
                    description,
                    changeLog,
                    parentVersionIdValue,
                    operatorUserId
            ));
            if (reservation == null) {
                throw new IllegalArgumentException("创建图片文件夹数据集草稿失败");
            }
            tempZip = Files.createTempFile("dataset-folder-", ".zip");
            int imageCount = writeCvFolderZip(tempZip, files, paths, annotationFormat);
            if (imageCount <= 0) {
                throw new IllegalArgumentException("CV 图片文件夹必须包含图片文件");
            }
            long sizeBytes = Files.size(tempZip);
            FolderUploadReservation reserved = reservation;
            try (InputStream is = Files.newInputStream(tempZip)) {
                objectStore.put(is, reserved.destinationObject(), sizeBytes, "application/zip");
            }
            DatasetZipValidator.ValidationResult evidence = validateDatasetObjectWithEvidence(
                    taskType,
                    cvTaskType,
                    annotationFormat,
                    reserved.version().getFileName(),
                    reserved.destinationObject(),
                    sizeBytes
            );
            reserved.version().setSizeBytes(sizeBytes);
            reserved.version().setFileCount(evidence.fileCount());
            SingleModalDatasetIndexer.PreparedIndex preparedIndex =
                    singleModalDatasetIndexer == null
                            ? null
                            : singleModalDatasetIndexer.prepareIndex(
                                    reserved.asset(),
                                    reserved.version()
                            );
            FolderUploadPublication publication = transactionTemplate.execute(
                    status -> finalizeFolderUpload(
                            reserved,
                            sizeBytes,
                            evidence,
                            preparedIndex
                    )
            );
            if (publication == null) {
                throw new IllegalArgumentException("发布图片文件夹数据集版本失败");
            }
            DatasetAsset asset = publication.asset();
            DatasetVersion versionEntity = publication.version();
            Instant publishedAt = publication.publishedAt();

            return completedPayload(
                    null,
                    asset.getId(),
                    versionEntity.getId(),
                    asset.getName(),
                    versionEntity.getVersion(),
                    versionEntity.getVersionNo(),
                    versionEntity.getVersionLabel(),
                    versionEntity.getDescription(),
                    versionEntity.getChangeLog(),
                    versionEntity.getParentVersionId(),
                    asset.getType(),
                    versionEntity.getCvTaskType(),
                    versionEntity.getAnnotationFormat(),
                    versionEntity.getRemark(),
                    versionEntity.getFileName(),
                    versionEntity.getStoragePath(),
                    versionEntity.getSizeBytes(),
                    versionEntity.getArtifactSpecId(),
                    versionEntity.getOwnerUserId(),
                    versionEntity.getCreatedAt(),
                    publishedAt
            );
        } catch (IllegalArgumentException e) {
            rollbackFolderUpload(reservation);
            throw e;
        } catch (Exception e) {
            rollbackFolderUpload(reservation);
            throw new IllegalArgumentException("图片文件夹上传失败: " + e.getMessage());
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (Exception ignored) {
                    // 临时 zip 清理失败不影响上传结果。
                }
            }
        }
    }

    private FolderUploadReservation reserveFolderUpload(
            String targetAssetId,
            String datasetName,
            String versionValue,
            String versionLabelValue,
            String taskType,
            String cvTaskType,
            String annotationFormat,
            String remark,
            String description,
            String changeLog,
            String parentVersionIdValue,
            Integer operatorUserId
    ) {
        boolean createAsset = targetAssetId == null || targetAssetId.isBlank();
        String assetId = createAsset
                ? "dataset-asset-" + UUID.randomUUID().toString().replace("-", "")
                : targetAssetId;
        String requestedLabel = defaultVersionLabel(
                versionLabelValue,
                versionValue,
                createAsset ? 1 : null
        );
        VersionAllocation baseAllocation = allocateVersion(
                assetId,
                createAsset,
                requestedLabel,
                null
        );
        DatasetAsset asset = baseAllocation.asset();
        if (!createAsset) {
            validateTargetAssetMetadata(asset, taskType, cvTaskType, annotationFormat);
        }
        String parentVersionId = resolveParentVersionId(parentVersionIdValue, createAsset ? null : asset);
        VersionAllocation allocation = new VersionAllocation(
                asset,
                baseAllocation.versionNo(),
                baseAllocation.versionLabel(),
                parentVersionId
        );
        if (!createAsset) {
            requireNoActiveDraft(assetId);
        }
        requireUniqueVersionLabel(assetId, allocation.versionLabel());

        Integer ownerUserId = createAsset ? operatorUserId : asset.getOwnerUserId();
        String effectiveDatasetName = createAsset
                ? requireUniqueNewAssetName(ownerUserId, datasetName)
                : asset.getName();
        String versionId = "dataset-ver-" + UUID.randomUUID().toString().replace("-", "");
        String fileName = sanitizeSegment(effectiveDatasetName)
                + "-" + sanitizeSegment("v" + allocation.versionNo())
                + "-folder.zip";
        String destinationObject = "users/" + ownerUserId
                + "/datasets/" + assetId
                + "/" + sanitizeSegment("v" + allocation.versionNo())
                + "/" + fileName;
        Instant now = Instant.now();

        if (createAsset) {
            asset.setId(assetId);
            asset.setName(effectiveDatasetName);
            asset.setType(taskType);
            asset.setCvTaskType(cvTaskType);
            asset.setAnnotationFormat(annotationFormat);
            asset.setRemark(remark);
            asset.setOwnerUserId(ownerUserId);
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            asset.setDeleted(false);
            saveNewAsset(asset);
        }

        DatasetVersion version = new DatasetVersion();
        version.setId(versionId);
        version.setAssetId(assetId);
        version.setVersionNo(allocation.versionNo());
        version.setVersionLabel(allocation.versionLabel());
        version.setVersion(allocation.versionLabel());
        version.setFileName(fileName);
        version.setStoragePath(destinationObject);
        version.setCvTaskType(cvTaskType);
        version.setAnnotationFormat(annotationFormat);
        version.setRemark(remark);
        version.setDescription(description);
        version.setChangeLog(changeLog);
        version.setParentVersionId(allocation.parentVersionId());
        version.setStatus(VERSION_STATUS_DRAFT);
        version.setOwnerUserId(ownerUserId);
        version.setCreatedBy(operatorUserId);
        version.setCreatedAt(now);
        version.setDeleted(false);
        try {
            versionRepo.saveAndFlush(version);
        } catch (DataIntegrityViolationException exception) {
            if (isOneActiveDraftViolation(exception)) {
                throw new IllegalArgumentException(
                        "dataset asset already has an active DRAFT version: " + assetId
                );
            }
            throw exception;
        }
        return new FolderUploadReservation(
                asset,
                version,
                destinationObject,
                createAsset
        );
    }

    private FolderUploadPublication finalizeFolderUpload(
            FolderUploadReservation reservation,
            long sizeBytes,
            DatasetZipValidator.ValidationResult evidence,
            SingleModalDatasetIndexer.PreparedIndex preparedIndex
    ) {
        DatasetVersion version = versionRepo
                .findByIdAndDeletedFalseForUpdate(reservation.version().getId())
                .orElseThrow(() -> new IllegalArgumentException("dataset version not found"));
        DatasetAsset asset = assetRepo
                .findByIdAndDeletedFalseForUpdate(reservation.asset().getId())
                .orElseThrow(() -> new IllegalArgumentException("dataset asset not found"));
        if (!VERSION_STATUS_DRAFT.equals(version.getStatus())) {
            throw new IllegalArgumentException("图片文件夹数据集版本必须保持 DRAFT");
        }
        if (!Objects.equals(version.getStoragePath(), reservation.destinationObject())) {
            throw new IllegalArgumentException("图片文件夹数据集存储元数据与预留不一致");
        }

        Instant now = Instant.now();
        requireCompatibleArtifactSpec(asset.getId(), evidence.artifactSpecId());
        version.setSizeBytes(sizeBytes);
        version.setFileCount(evidence.fileCount());
        version.setArtifactSha256(evidence.sha256());
        version.setArtifactSpecId(evidence.artifactSpecId());
        if (singleModalDatasetIndexer != null) {
            long consumableSamples = singleModalDatasetIndexer.persistPrepared(
                    asset,
                    version,
                    preparedIndex
            );
            if (consumableSamples <= 0) {
                throw new IllegalArgumentException("数据集版本没有可消费样本");
            }
        }
        version.setStatus(VERSION_STATUS_READY);
        version.setPublishedAt(now);
        versionRepo.saveAndFlush(version);
        asset.setCurrentVersionId(version.getId());
        asset.setUpdatedAt(now);
        assetRepo.saveAndFlush(asset);
        return new FolderUploadPublication(asset, version, now);
    }

    private void rollbackFolderUpload(FolderUploadReservation reservation) {
        if (reservation == null) {
            return;
        }
        removeObjectQuietly(reservation.destinationObject());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                versionRepo.findById(reservation.version().getId())
                        .ifPresent(versionRepo::delete);
                if (reservation.assetCreatedByUpload()) {
                    assetRepo.findById(reservation.asset().getId())
                            .ifPresent(assetRepo::delete);
                }
            });
        } catch (RuntimeException ignored) {
            // 保留原始上传、校验或发布错误。
        }
    }

    private void validateInit(DatasetUploadInitRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        requireText(req.getFileName(), "fileName 不能为空");
        if (req.getFileSize() == null || req.getFileSize() <= 0) {
            throw new IllegalArgumentException("fileSize 必须大于 0");
        }
        if (req.getAssetId() == null || req.getAssetId().isBlank()) {
            requireText(req.getDatasetName(), "datasetName 不能为空");
        }
        String taskType = DatasetTaskType.normalize(req.getType());
        String sampleGrouping = normalizeSampleGroupingForTask(
                taskType,
                req.getSampleGrouping()
        );
        normalizeManifestPath(sampleGrouping, req.getManifestPath());
        normalizeStrictManifestForTask(taskType, sampleGrouping, req.getStrictManifest());
        validateGroupingForTask(taskType, sampleGrouping);
        CvTaskType.normalizeForTask(taskType, req.getCvTaskType());
        CvAnnotationFormat.normalizeForTask(taskType, req.getAnnotationFormat());
        validateDatasetFileName(taskType, req.getFileName());
    }

    private DatasetUploadSession getSession(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId 不能为空");
        }
        DatasetUploadSession session = sessionRepo.findById(uploadId)
                .orElseThrow(() -> new IllegalArgumentException("uploadId 无效"));
        authContext.requireOwnerAccess(session.getOwnerUserId(), "uploadId invalid or not accessible");
        return session;
    }

    private DatasetVersion requireAppendDraft(String draftVersionId) {
        if (draftVersionId == null || draftVersionId.isBlank()) {
            throw new IllegalArgumentException(
                    "dataset workspace version not found or no permission"
            );
        }
        DatasetVersion draft = versionRepo.findByIdAndDeletedFalse(draftVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset workspace version not found or no permission"
                ));
        if (!VERSION_STATUS_DRAFT.equals(draft.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset workspace version not found or no permission"
            );
        }
        requireAppendAsset(draft);
        return draft;
    }

    private DatasetAsset requireAppendAsset(DatasetVersion draft) {
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(draft.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset workspace version not found or no permission"
                ));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new IllegalArgumentException(
                    "dataset workspace version not found or no permission"
            );
        }
        return asset;
    }

    private void requireAppendSession(
            DatasetUploadSession session,
            String draftVersionId
    ) {
        if (!UPLOAD_PURPOSE_APPEND.equals(session.getUploadPurpose())
                || !Objects.equals(draftVersionId, session.getVersionId())) {
            throw new IllegalArgumentException(
                    "append upload session does not belong to draft version"
            );
        }
        String taskType = DatasetTaskType.normalize(session.getType());
        if ("MULTIMODAL".equals(taskType) && !isMultimodalGrouping(session.getSampleGrouping())) {
            throw new IllegalArgumentException(
                    "append upload session sampleGrouping must be MANIFEST or AUTO_DIRECTORY"
            );
        }
        if (!"MULTIMODAL".equals(taskType)
                && (session.getSampleGrouping() != null
                || session.getManifestPath() != null
                || Boolean.TRUE.equals(session.getStrictManifest()))) {
            throw new IllegalArgumentException(
                    "single-modal append upload cannot use sampleGrouping, manifestPath, or strictManifest"
            );
        }
        normalizeStrictManifestForTask(taskType, session.getSampleGrouping(), session.getStrictManifest());
    }

    /** 在调用方事务内按旧状态和所有者条件占用会话，阻止两个请求同时完成同一次上传。 */
    private DatasetUploadSession claimCompleting(String uploadId) {
        DatasetUploadSession session = getSession(uploadId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            return session;
        }
        if (STATUS_COMPLETING.equals(session.getStatus())) {
            throw new IllegalArgumentException("数据集文件正在合并中，请稍后查询进度");
        }
        String expectedStatus = session.getStatus();
        if (!STATUS_UPLOADING.equals(expectedStatus)
                && !isRetryableFailedInitial(session)) {
            throw new IllegalArgumentException("上传状态不允许完成: " + session.getStatus());
        }

        Instant now = Instant.now();
        int updated = sessionRepo.updateStatusIfCurrent(
                session.getId(),
                session.getOwnerUserId(),
                expectedStatus,
                STATUS_COMPLETING,
                now
        );
        if (updated == 0) {
            DatasetUploadSession current = getSession(uploadId);
            if (STATUS_COMPLETED.equals(current.getStatus())) {
                return current;
            }
            throw new IllegalArgumentException("数据集文件正在合并中，请稍后查询进度");
        }
        session.setStatus(STATUS_COMPLETING);
        clearCompletionFailure(session);
        session.setUpdatedAt(now);
        return session;
    }

    private boolean isRetryableFailedInitial(DatasetUploadSession session) {
        return STATUS_FAILED.equals(session.getStatus())
                && UPLOAD_PURPOSE_INITIAL.equals(session.getUploadPurpose());
    }

    private void applyCompletionFailure(
            DatasetUploadSession session,
            UploadFailure failure
    ) {
        Instant now = Instant.now();
        session.setStatus(STATUS_FAILED);
        session.setCompletionErrorCode(failure.reasonCode());
        session.setCompletionErrorMessage(failure.userMessage());
        session.setCompletionErrorDetails(failure.details());
        session.setCompletionFailedAt(now);
        session.setUpdatedAt(now);
    }

    private void clearCompletionFailure(DatasetUploadSession session) {
        session.setCompletionErrorCode(null);
        session.setCompletionErrorMessage(null);
        session.setCompletionErrorDetails(null);
        session.setCompletionFailedAt(null);
    }

    private UploadFailure storageFailure() {
        return new UploadFailure(
                "DATASET_UPLOAD_STORAGE_FAILED",
                "数据集文件处理失败，请稍后重试",
                Map.of("stage", "STORAGE")
        );
    }

    private UploadFailure validationFailure() {
        return new UploadFailure(
                "INVALID_DATASET_CONTENT",
                "数据集内容格式无效，请检查文件后重试",
                Map.of("stage", "VALIDATION")
        );
    }

    private UploadFailure finalizationFailure() {
        return new UploadFailure(
                "DATASET_UPLOAD_FINALIZATION_FAILED",
                "数据集版本确认失败，请稍后重试",
                Map.of("stage", "FINALIZATION")
        );
    }

    private DatasetUploadProgressDto progress(DatasetUploadSession session) {
        boolean completed = STATUS_COMPLETED.equals(session.getStatus());
        UploadChunkProgressSummary summary = completed
                ? null
                : chunkRepo.summarizeProgressByUploadId(session.getId());
        List<Integer> uploadedPartIndexes = completed
                ? completedPartIndexes(session.getTotalChunks())
                : chunkRepo.findPartIndexesByUploadIdOrderByPartIndexAsc(session.getId());
        DatasetUploadProgressDto dto = new DatasetUploadProgressDto();
        dto.setUploadId(session.getId());
        dto.setStatus(session.getStatus());
        dto.setUploadStatus(session.getStatus());
        dto.setVersionStatus(progressVersionStatus(session));
        dto.setImportJobId(session.getImportJobId());
        dto.setImportStatus(importStatus(session.getImportJobId()));
        dto.setFileName(session.getFileName());
        dto.setFileSize(session.getFileSize());
        dto.setChunkSize(session.getChunkSize());
        dto.setTotalChunks(session.getTotalChunks());
        dto.setUploadedChunks(completed ? session.getTotalChunks() : uploadedChunks(summary));
        dto.setUploadedBytes(completed
                ? session.getFileSize()
                : uploadedBytes(summary));
        dto.setUploadedPartIndexes(uploadedPartIndexes);
        dto.setAssetId(session.getAssetId());
        dto.setVersionId(session.getVersionId());
        dto.setArtifactSpecId(session.getArtifactSpecId());
        dto.setVersionNo(session.getVersionNo());
        dto.setVersionLabel(displayVersionLabel(session.getVersionLabel(), session.getVersion(), session.getVersionNo()));
        dto.setDescription(session.getDescription());
        dto.setChangeLog(session.getChangeLog());
        dto.setParentVersionId(session.getParentVersionId());
        dto.setCvTaskType(session.getCvTaskType());
        dto.setAnnotationFormat(session.getAnnotationFormat());
        dto.setStrictManifest(Boolean.TRUE.equals(session.getStrictManifest()));
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        return dto;
    }

    private String progressVersionStatus(DatasetUploadSession session) {
        if (session.getVersionId() == null || session.getVersionId().isBlank()) {
            return null;
        }
        return versionRepo.findByIdAndDeletedFalse(session.getVersionId())
                .map(DatasetVersion::getStatus)
                .orElse(null);
    }

    private String importStatus(String importJobId) {
        if (importJobId == null || importJobId.isBlank() || importJobRepo == null) {
            return null;
        }
        return importJobRepo.findById(importJobId)
                .map(ImportJob::getStatus)
                .orElse(null);
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

    private Map<String, Object> completedPayload(DatasetUploadSession session) {
        Map<String, Object> data = new HashMap<>();
        data.put("uploadId", session.getId());
        data.put("id", session.getVersionId());
        data.put("assetId", session.getAssetId());
        data.put("name", session.getDatasetName());
        data.put("version", session.getVersion());
        data.put("versionNo", session.getVersionNo());
        data.put("versionLabel", displayVersionLabel(session.getVersionLabel(), session.getVersion(), session.getVersionNo()));
        data.put("description", session.getDescription());
        data.put("changeLog", session.getChangeLog());
        data.put("parentVersionId", session.getParentVersionId());
        data.put("type", session.getType());
        data.put("cvTaskType", session.getCvTaskType());
        data.put("annotationFormat", session.getAnnotationFormat());
        data.put("remark", session.getRemark());
        data.put("fileName", session.getFileName());
        data.put("sizeBytes", session.getFileSize());
        data.put("status", session.getStatus());
        data.put("uploadStatus", session.getStatus());
        data.put("datasetVersionId", session.getVersionId());
        data.put(
                "versionStatus",
                isMultimodalImportUpload(session)
                        ? VERSION_STATUS_DRAFT
                        : VERSION_STATUS_READY
        );
        data.put("importJobId", session.getImportJobId());
        data.put("strictManifest", Boolean.TRUE.equals(session.getStrictManifest()));
        data.put("artifactSpecId", session.getArtifactSpecId());
        data.put("importStatus", importStatus(session.getImportJobId()));
        data.put("ownerUserId", session.getOwnerUserId());
        data.put("createdAt", session.getCreatedAt());
        data.put("updatedAt", session.getUpdatedAt());
        return data;
    }

    private Map<String, Object> appendCompletedPayload(DatasetUploadSession session) {
        if (session.getImportJobId() == null || session.getImportJobId().isBlank()) {
            throw new IllegalArgumentException("append upload has no import job");
        }
        ImportJob job = importJobRepo.findById(session.getImportJobId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "append import job not found: " + session.getImportJobId()
                ));
        if (job.getPackageId() == null || job.getPackageId().isBlank()) {
            throw new IllegalArgumentException("append import job has no package");
        }
        DatasetVersionPackage relation = versionPackageRepo
                .findByDatasetVersionIdAndPackageId(
                        session.getVersionId(),
                        job.getPackageId()
                )
                .orElseThrow(() -> new IllegalArgumentException(
                        "append package is not linked to draft version"
                ));

        Map<String, Object> data = new HashMap<>();
        data.put("uploadId", session.getId());
        data.put("draftVersionId", session.getVersionId());
        data.put("datasetVersionId", session.getVersionId());
        data.put("packageId", job.getPackageId());
        data.put("packageRole", relation.getPackageRole());
        data.put("packageOrder", relation.getPackageOrder());
        data.put("importJobId", job.getId());
        data.put("strictManifest", Boolean.TRUE.equals(session.getStrictManifest()));
        data.put("uploadStatus", session.getStatus());
        data.put("versionStatus", VERSION_STATUS_DRAFT);
        data.put("importStatus", job.getStatus());
        return data;
    }

    private Map<String, Object> completedPayload(
            String uploadId,
            String assetId,
            String versionId,
            String datasetName,
            String version,
            Integer versionNo,
            String versionLabel,
            String description,
            String changeLog,
            String parentVersionId,
            String type,
            String cvTaskType,
            String annotationFormat,
            String remark,
            String fileName,
            String storagePath,
            Long sizeBytes,
            String artifactSpecId,
            Integer ownerUserId,
            Instant createdAt,
            Instant updatedAt
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("uploadId", uploadId);
        data.put("id", versionId);
        data.put("assetId", assetId);
        data.put("name", datasetName);
        data.put("version", version);
        data.put("versionNo", versionNo);
        data.put("versionLabel", displayVersionLabel(versionLabel, version, versionNo));
        data.put("description", description);
        data.put("changeLog", changeLog);
        data.put("parentVersionId", parentVersionId);
        data.put("type", type);
        data.put("cvTaskType", cvTaskType);
        data.put("annotationFormat", annotationFormat);
        data.put("remark", remark);
        data.put("fileName", fileName);
        data.put("sizeBytes", sizeBytes);
        data.put("artifactSpecId", artifactSpecId);
        data.put("status", STATUS_COMPLETED);
        data.put("ownerUserId", ownerUserId);
        data.put("createdAt", createdAt);
        data.put("updatedAt", updatedAt);
        return data;
    }

    private int writeCvFolderZip(
            Path tempZip,
            List<MultipartFile> files,
            List<String> paths,
            String annotationFormat
    ) throws Exception {
        return DatasetFolderArchive.writeCvFolderZip(tempZip, files, paths, annotationFormat);
    }

    private void removeObjectQuietly(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return;
        }
        try {
            minioDeleteTaskService.enqueueDefaultBucketDeleteImmediately(
                    objectName,
                    MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_ROLLBACK,
                    objectName,
                    null
            );
        } catch (Exception ignored) {
            // 清理失败时保留原始错误。
        }
    }

    private void registerChunkCleanup(String uploadId, List<DatasetUploadChunk> chunks) {
        List<String> objectNames = new ArrayList<>();
        for (DatasetUploadChunk chunk : chunks) {
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
                        MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_CHUNK,
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
            // 临时分片元数据清理失败不影响已完成的数据集记录。
        }
    }

    private boolean sameUpload(
            DatasetUploadSession session,
            DatasetUploadInitRequest req,
            String taskType,
            String resolvedParentVersionId
    ) {
        String cvTaskType = CvTaskType.normalizeForTask(taskType, req.getCvTaskType());
        String annotationFormat = CvAnnotationFormat.normalizeForTask(taskType, req.getAnnotationFormat());
        String requestAssetId = normalizeText(req.getAssetId());
        Integer resumeDefaultVersionNo = requestAssetId == null
                ? Integer.valueOf(1)
                : session.getVersionNo();
        String requestLabel = defaultVersionLabel(req.getVersionLabel(), req.getVersion(),
                resumeDefaultVersionNo);
        boolean requestLabelGenerated = isVersionLabelGenerated(req.getVersionLabel(), req.getVersion());
        if (requestLabel == null && requestLabelGenerated) {
            requestLabel = displayVersionLabel(
                    session.getVersionLabel(),
                    session.getVersion(),
                    session.getVersionNo()
            );
        }
        String requestSampleGrouping = normalizeSampleGroupingForTask(taskType, req.getSampleGrouping());
        String requestManifestPath = normalizeManifestPath(
                requestSampleGrouping,
                req.getManifestPath()
        );
        boolean requestStrictManifest = normalizeStrictManifestForTask(
                taskType,
                requestSampleGrouping,
                req.getStrictManifest()
        );
        return session.getFileName().equals(req.getFileName().trim())
                && session.getFileSize().equals(req.getFileSize())
                && Objects.equals(session.getAssetId(), requestAssetId)
                && (requestAssetId != null || session.getDatasetName().equals(req.getDatasetName().trim()))
                && Objects.equals(displayVersionLabel(session.getVersionLabel(), session.getVersion(), session.getVersionNo()), requestLabel)
                && Boolean.TRUE.equals(session.getVersionLabelGenerated()) == requestLabelGenerated
                && session.getType().equals(taskType)
                && equalsNullable(session.getCvTaskType(), cvTaskType)
                && equalsNullable(session.getAnnotationFormat(), annotationFormat)
                && equalsNullable(session.getDescription(), req.getDescription())
                && equalsNullable(session.getChangeLog(), req.getChangeLog())
                && equalsNullable(session.getParentVersionId(), resolvedParentVersionId)
                && equalsNullable(session.getSampleGrouping(), requestSampleGrouping)
                && equalsNullable(session.getManifestPath(), requestManifestPath)
                && Boolean.TRUE.equals(session.getStrictManifest()) == requestStrictManifest;
    }

    private boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String defaultVersion(String value) {
        return value == null || value.isBlank() ? "v1" : value.trim();
    }

    private String defaultVersionLabel(String versionLabel, String version, Integer defaultVersionNo) {
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

    private String displayVersionLabel(String versionLabel, String version, Integer defaultVersionNo) {
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

    private boolean isVersionLabelGenerated(String versionLabel, String version) {
        return normalizeText(versionLabel) == null && normalizeText(version) == null;
    }

    private Integer previewVersionNo(String assetId) {
        if (assetId == null) {
            return 1;
        }
        Integer maxVersionNo = versionRepo.findMaxVersionNoByAssetId(assetId);
        return (maxVersionNo == null ? 0 : maxVersionNo) + 1;
    }

    private DatasetAsset resolveTargetAsset(
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
                .orElseThrow(() -> new IllegalArgumentException("dataset asset not found: " + assetId));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new IllegalArgumentException("no permission for asset: " + assetId);
        }
        authContext.rejectDemoWrite(asset.getIsDemo());
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
        return asset;
    }

    private String resolveParentVersionId(String parentVersionIdValue, DatasetAsset targetAsset) {
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
                .orElseThrow(() -> new IllegalArgumentException("parent dataset version not found: " + resolvedParentVersionId));
        if (!targetAsset.getId().equals(parent.getAssetId())) {
            throw new IllegalArgumentException("parentVersionId must belong to target asset");
        }
        if (!VERSION_STATUS_READY.equals(parent.getStatus())) {
            throw new IllegalArgumentException("parentVersionId must reference a READY dataset version");
        }
        return parentVersionId;
    }

    private VersionAllocation allocateVersion(DatasetUploadSession session, String assetId, boolean createAsset) {
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

    private String requestedSessionLabel(DatasetUploadSession session) {
        if (Boolean.TRUE.equals(session.getVersionLabelGenerated())) {
            return null;
        }
        String label = normalizeText(session.getVersionLabel());
        return label != null ? label : normalizeText(session.getVersion());
    }

    private VersionAllocation allocateVersion(
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
                () -> new IllegalArgumentException("dataset asset not found: " + assetId)
        );
        Integer maxVersionNo = versionRepo.findMaxVersionNoByAssetId(assetId);
        int nextVersionNo = (maxVersionNo == null ? 0 : maxVersionNo) + 1;
        String label = requestedLabel == null ? "v" + nextVersionNo : requestedLabel;
        return new VersionAllocation(asset, nextVersionNo, label, parentVersionId);
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

    private void requireUniqueVersionLabel(String assetId, String versionLabel) {
        if (versionRepo.existsByAssetIdAndVersion(assetId, versionLabel)) {
            throw new IllegalArgumentException(
                    "dataset version label already exists for asset: " + versionLabel
            );
        }
    }

    private void requireNoActiveDraft(String assetId) {
        versionRepo.findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                assetId,
                VERSION_STATUS_DRAFT
        ).ifPresent(activeDraft -> {
            throw new IllegalArgumentException(activeDraftMessage(activeDraft));
        });
    }

    private String activeDraftMessage(DatasetVersion activeDraft) {
        String id = activeDraft.getId();
        if (id == null || id.isBlank()) {
            return "dataset asset already has an active DRAFT version";
        }
        return "dataset asset already has an active DRAFT version: " + id;
    }

    private String requireUniqueNewAssetName(Integer ownerUserId, String value) {
        String normalized = AssetNamePolicy.normalizeRequired(value);
        if (assetRepo.existsActiveNormalizedName(ownerUserId, normalized, null)) {
            throw new AssetNameConflictException("dataset");
        }
        return normalized;
    }

    private DatasetAsset saveNewAsset(DatasetAsset asset) {
        try {
            return assetRepo.saveAndFlush(asset);
        } catch (DataIntegrityViolationException exception) {
            if (AssetNamePolicy.isNameConstraintViolation(exception)) {
                throw new AssetNameConflictException("dataset");
            }
            throw exception;
        }
    }

    private String normalizeText(String value) {
        return DatasetUploadPolicy.normalizeText(value);
    }

    private void requireText(String value, String message) {
        DatasetUploadPolicy.requireText(value, message);
    }

    private void validateDatasetFileName(String taskType, String fileName) {
        validateDatasetFileNameForTask(taskType, fileName);
    }

    static void validateDatasetFileNameForTask(String taskType, String fileName) {
        DatasetZipValidator.validateDatasetFileNameForTask(taskType, fileName);
    }

    static void validateAppendPackageFileNameForTask(String taskType, String fileName) {
        DatasetZipValidator.validateAppendPackageFileNameForTask(taskType, fileName);
    }

    static String normalizeSampleGrouping(String value) {
        return DatasetUploadPolicy.normalizeSampleGrouping(value);
    }

    static String normalizeSampleGroupingForTask(String taskType, String value) {
        return DatasetUploadPolicy.normalizeSampleGroupingForTask(taskType, value);
    }

    static String normalizeManifestPath(String sampleGrouping, String value) {
        return DatasetUploadPolicy.normalizeManifestPath(sampleGrouping, value);
    }

    static boolean normalizeStrictManifestForTask(
            String taskType,
            String sampleGrouping,
            Boolean value
    ) {
        return DatasetUploadPolicy.normalizeStrictManifestForTask(taskType, sampleGrouping, value);
    }

    static int calculateChunkSize(long fileSize) {
        return DatasetUploadPolicy.calculateChunkSize(fileSize);
    }

    static int calculateTotalChunks(long fileSize, int chunkSize) {
        return DatasetUploadPolicy.calculateTotalChunks(fileSize, chunkSize);
    }

    private static void validateGroupingForTask(String taskType, String sampleGrouping) {
        DatasetUploadPolicy.validateGroupingForTask(taskType, sampleGrouping);
    }

    private static boolean isMultimodalGrouping(String sampleGrouping) {
        return DatasetUploadPolicy.isMultimodalGrouping(sampleGrouping);
    }

    private Long validateDatasetObjectFormat(
            String taskType,
            String annotationFormat,
            String fileName,
            String objectName,
            long objectSize
    ) throws Exception {
        return datasetZipValidator.validateDatasetObjectFormat(
                taskType,
                annotationFormat,
                fileName,
                objectName,
                objectSize
        );
    }

    private void requireCompatibleArtifactSpec(String assetId, String artifactSpecId) {
        versionRepo
                .findTopByAssetIdAndArtifactSpecIdIsNotNullAndDeletedFalseOrderByVersionNoDesc(
                        assetId
                )
                .ifPresent(existing -> {
                    if (!Objects.equals(existing.getArtifactSpecId(), artifactSpecId)) {
                        throw new IllegalArgumentException(
                                "dataset artifact specification does not match existing asset versions"
                        );
                    }
                });
    }

    private DatasetZipValidator.ValidationResult validateDatasetObjectWithEvidence(
            String taskType,
            String cvTaskType,
            String annotationFormat,
            String fileName,
            String objectName,
            long objectSize
    ) throws Exception {
        return datasetZipValidator.validateDatasetObjectWithEvidence(
                taskType,
                cvTaskType,
                annotationFormat,
                fileName,
                objectName,
                objectSize
        );
    }

    static long validateDatasetZipEntries(
            String taskType,
            String annotationFormat,
            InputStream inputStream
    ) throws Exception {
        return DatasetZipValidator.validateDatasetZipEntries(
                taskType,
                annotationFormat,
                inputStream
        );
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? e.getMessage() : current.getMessage();
    }

    private boolean isOneActiveDraftViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "uk_dataset_version_one_active_draft".equalsIgnoreCase(constraint.getConstraintName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT)
                            .contains("uk_dataset_version_one_active_draft")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static String manifestDestinationObject(DatasetUploadSession session) {
        return DatasetUploadPolicy.manifestDestinationObject(session);
    }

    static String appendPackageDestinationObject(DatasetUploadSession session) {
        return DatasetUploadPolicy.appendPackageDestinationObject(session);
    }

    private static String manifestDestinationObject(
            Integer ownerUserId,
            String assetId,
            Integer versionNo,
            String fileName
    ) {
        return DatasetUploadPolicy.manifestDestinationObject(ownerUserId, assetId, versionNo, fileName);
    }

    private static String sanitizeSegment(String value) {
        return DatasetUploadPolicy.sanitizeSegment(value);
    }

    private record VersionAllocation(
            DatasetAsset asset,
            Integer versionNo,
            String versionLabel,
            String parentVersionId
    ) {
    }

    private record SingleModalReservation(
            DatasetUploadSession session,
            DatasetAsset asset,
            DatasetVersion version,
            String destinationObject
    ) {
    }

    private record FolderUploadReservation(
            DatasetAsset asset,
            DatasetVersion version,
            String destinationObject,
            boolean assetCreatedByUpload
    ) {
    }

    private record FolderUploadPublication(
            DatasetAsset asset,
            DatasetVersion version,
            Instant publishedAt
    ) {
    }

    private record ManifestReservation(
            DatasetUploadSession session,
            String destinationObject
    ) {
    }

    private record ChunkPersistenceResult(
            DatasetUploadSession session,
            boolean persisted
    ) {
    }

    private record UploadFailure(
            String reasonCode,
            String userMessage,
            Map<String, Object> details
    ) {
    }
}
