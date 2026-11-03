package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.errors.ErrorResponseException;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.Result;
import io.minio.messages.Item;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class MinioService {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioService(MinioClient minioClient, MinioConfig minioConfig) {
        this.minioClient = minioClient;
        this.bucket = minioConfig.getBucket();
    }

    public void assertConnected() throws Exception {
        // Lightweight connectivity check; will throw if endpoint not reachable/auth invalid.
        minioClient.listBuckets();
    }

    public String uploadFile(String objectName, MultipartFile file) throws Exception {
        Objects.requireNonNull(file, "file");
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file 为空");
        }
        try (InputStream is = file.getInputStream()) {
            uploadStream(
                    objectName,
                    is,
                    file.getSize(),
                    Optional.ofNullable(file.getContentType()).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE)
            );
        }
        return objectName;
    }

    public void uploadStream(String objectName, InputStream inputStream, long size, String contentType) throws Exception {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        Objects.requireNonNull(inputStream, "inputStream");
        if (size < 0) {
            throw new IllegalArgumentException("size 不能为负数");
        }
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .stream(inputStream, size, -1)
                        .contentType(contentType == null || contentType.isBlank()
                                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : contentType)
                        .build()
        );
    }

    public InputStream downloadStream(String objectName) throws Exception {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .build()
        );
    }

    InputStream downloadRange(String objectName, long offset, long length) throws Exception {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName cannot be blank");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to zero");
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be greater than zero");
        }
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .offset(offset)
                        .length(length)
                        .build()
        );
    }

    public StatObjectResponse stat(String objectName) throws Exception {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        return minioClient.statObject(
                StatObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .build()
        );
    }

    public boolean objectExists(String objectName) throws Exception {
        try {
            stat(objectName);
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code)) {
                return false;
            }
            throw e;
        }
    }

    public void copyObject(String sourceObjectName, String targetObjectName) throws Exception {
        if (sourceObjectName == null || sourceObjectName.isBlank()) {
            throw new IllegalArgumentException("sourceObjectName 不能为空");
        }
        if (targetObjectName == null || targetObjectName.isBlank()) {
            throw new IllegalArgumentException("targetObjectName 不能为空");
        }
        minioClient.copyObject(
                CopyObjectArgs.builder()
                        .bucket(bucket)
                        .object(targetObjectName)
                        .source(CopySource.builder().bucket(bucket).object(sourceObjectName).build())
                        .build()
        );
    }

    public void deleteObject(String objectName) throws Exception {
        deleteObject(bucket, objectName);
    }

    public void deleteObject(String targetBucket, String objectName) throws Exception {
        if (targetBucket == null || targetBucket.isBlank()) {
            throw new IllegalArgumentException("bucket 不能为空");
        }
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        minioClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(targetBucket)
                        .object(objectName)
                        .build()
        );
    }

    public List<String> listObjectNames(String prefix) throws Exception {
        List<String> names = new ArrayList<>();
        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(prefix == null ? "" : prefix)
                        .recursive(true)
                        .build()
        );
        for (Result<Item> r : results) {
            Item item = r.get();
            if (item != null && item.objectName() != null) {
                names.add(item.objectName());
            }
        }
        return names;
    }
}
