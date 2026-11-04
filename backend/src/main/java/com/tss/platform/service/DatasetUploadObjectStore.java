package com.tss.platform.service;

import com.tss.platform.entity.DatasetUploadChunk;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/** 对象存储的 SDK 细节。不自带事务、不吞异常、不自动重试；补偿和分片清理由 DatasetUploadService 决定。 */
final class DatasetUploadObjectStore {
    private final MinioClient minioClient;
    private final String bucket;

    DatasetUploadObjectStore(MinioClient minioClient, String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    /** 调用方已确认分片完整并按序排列；这里不得重新排序、去重或补片。 */
    void compose(List<DatasetUploadChunk> chunks, String destinationObject) throws Exception {
        List<ComposeSource> sources = chunks.stream()
                .map(chunk -> ComposeSource.builder()
                        .bucket(bucket)
                        .object(chunk.getObjectName())
                        .build())
                .collect(Collectors.toList());
        minioClient.composeObject(
                ComposeObjectArgs.builder()
                        .bucket(bucket)
                        .object(destinationObject)
                        .sources(sources)
                        .build()
        );
    }

    /** 流的所有权仍属于调用方，成功标记及后续 stat 不在此方法隐式执行。 */
    void put(InputStream input, String objectName, long size, String contentType) throws Exception {
        minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectName)
                .stream(input, size, -1).contentType(contentType).build());
    }

    StatObjectResponse stat(String objectName) throws Exception {
        return minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
    }
}
