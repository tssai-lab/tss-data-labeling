package com.tss.platform.service;

import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

final class DatasetUploadChunkService {

    private static final Logger log =
            LoggerFactory.getLogger(DatasetUploadChunkService.class);
    private static final String STATUS_UPLOADING = "UPLOADING";

    private final MinioClient minioClient;
    private final String bucket;
    private final DatasetUploadChunkRepository chunkRepo;
    private final DatasetUploadSessionRepository sessionRepo;
    private final MinioDeleteTaskService minioDeleteTaskService;

    DatasetUploadChunkService(
            MinioClient minioClient,
            String bucket,
            DatasetUploadChunkRepository chunkRepo,
            DatasetUploadSessionRepository sessionRepo,
            MinioDeleteTaskService minioDeleteTaskService
    ) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.chunkRepo = chunkRepo;
        this.sessionRepo = sessionRepo;
        this.minioDeleteTaskService = minioDeleteTaskService;
    }

    DatasetUploadSession saveChunk(DatasetUploadSession session, Integer partIndex, MultipartFile file) {
        long expectedSize = validateChunk(session, partIndex, file);

        String objectName = chunkObjectName(session, partIndex);
        boolean objectUploaded = false;
        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(is, file.getSize(), -1)
                            .contentType(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                            .build()
            );
            objectUploaded = true;
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectName).build()
            );
            if (stat.size() != expectedSize) {
                throw new IllegalArgumentException("上传后分片大小与预期大小不一致");
            }
            DatasetUploadChunk chunk = chunkRepo.findByUploadIdAndPartIndex(session.getId(), partIndex)
                    .orElseGet(() -> {
                        DatasetUploadChunk c = new DatasetUploadChunk();
                        c.setId("dataset-chunk-" + UUID.randomUUID().toString().replace("-", ""));
                        c.setUploadId(session.getId());
                        c.setPartIndex(partIndex);
                        c.setCreatedAt(Instant.now());
                        return c;
                    });
            String previousObjectName = chunk.getObjectName();
            chunk.setObjectName(objectName);
            chunk.setSizeBytes(stat.size());
            chunk.setEtag(stat.etag());
            chunkRepo.save(chunk);
            session.setUpdatedAt(Instant.now());
            DatasetUploadSession saved = sessionRepo.save(session);
            registerObjectCleanup(previousObjectName, objectName);
            return saved;
        } catch (Exception e) {
            if (objectUploaded) {
                removeObjectQuietly(
                        objectName,
                        MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_ROLLBACK
                );
            }
            throw new IllegalArgumentException("分片上传失败: " + rootMessage(e));
        }
    }

    private long validateChunk(DatasetUploadSession session, Integer partIndex, MultipartFile file) {
        if (!STATUS_UPLOADING.equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "upload session must be UPLOADING, current status=" + session.getStatus()
            );
        }
        if (partIndex == null || partIndex < 0 || partIndex >= session.getTotalChunks()) {
            throw new IllegalArgumentException("partIndex 超出范围");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("分片文件不能为空");
        }
        long expectedSize = partIndex < session.getTotalChunks() - 1
                ? session.getChunkSize()
                : session.getFileSize()
                        - (long) session.getChunkSize() * (session.getTotalChunks() - 1);
        if (file.getSize() != expectedSize) {
            throw new IllegalArgumentException(
                    "分片大小必须等于预期大小: expected=" + expectedSize
            );
        }
        return expectedSize;
    }

    private String chunkObjectName(DatasetUploadSession session, Integer partIndex) {
        return "users/" + session.getOwnerUserId()
                + "/datasets/_uploads/" + session.getId()
                + "/part-" + partIndex + "-"
                + UUID.randomUUID().toString().replace("-", "");
    }

    private void registerObjectCleanup(String previousObjectName, String newObjectName) {
        if (previousObjectName != null
                && !previousObjectName.isBlank()
                && !previousObjectName.equals(newObjectName)) {
            minioDeleteTaskService.enqueueDefaultBucketDelete(
                    previousObjectName,
                    MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_CHUNK,
                    previousObjectName,
                    null
            );
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    removeObjectQuietly(
                            newObjectName,
                            MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_ROLLBACK
                    );
                }
            }
        });
    }

    private void removeObjectQuietly(String objectName, String source) {
        if (objectName == null || objectName.isBlank()) {
            return;
        }
        try {
            minioDeleteTaskService.enqueueDefaultBucketDeleteImmediately(
                    objectName,
                    source,
                    objectName,
                    null
            );
        } catch (Exception exception) {
            log.warn(
                    "Dataset upload chunk rollback delete enqueue failed: uploadId={}, exceptionType={}",
                    uploadIdFromChunkObjectName(objectName),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private String uploadIdFromChunkObjectName(String objectName) {
        String marker = "/datasets/_uploads/";
        int markerIndex = objectName.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int start = markerIndex + marker.length();
        int end = objectName.indexOf('/', start);
        return end < 0 ? objectName.substring(start) : objectName.substring(start, end);
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? e.getMessage() : current.getMessage();
    }
}
