package com.tss.platform.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioInitializerTest {

    @Test
    void retriesBucketCheckBeforeFailingStartup() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioConfig minioConfig = new MinioConfig();
        minioConfig.setBucket("models");
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new RuntimeException("connect refused"))
                .thenReturn(true);
        MinioInitializer initializer = new MinioInitializer(
                minioClient,
                minioConfig,
                3,
                Duration.ZERO,
                duration -> {
                }
        );

        initializer.run(null);

        verify(minioClient, times(2)).bucketExists(any(BucketExistsArgs.class));
    }

    @Test
    void doesNotRetryInvalidBucketName() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioConfig minioConfig = new MinioConfig();
        minioConfig.setBucket("bad_bucket");
        MinioInitializer initializer = new MinioInitializer(
                minioClient,
                minioConfig,
                3,
                Duration.ZERO,
                duration -> {
                }
        );

        assertThrows(IllegalArgumentException.class, () -> initializer.run(null));

        verify(minioClient, times(0)).bucketExists(any(BucketExistsArgs.class));
    }

    @Test
    void doesNotRetryCredentialFailure() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioConfig minioConfig = new MinioConfig();
        minioConfig.setBucket("models");
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(errorResponse("InvalidAccessKeyId"));
        MinioInitializer initializer = new MinioInitializer(
                minioClient,
                minioConfig,
                3,
                Duration.ZERO,
                duration -> {
                }
        );

        assertThrows(ErrorResponseException.class, () -> initializer.run(null));

        verify(minioClient, times(1)).bucketExists(any(BucketExistsArgs.class));
    }

    private ErrorResponseException errorResponse(String code) {
        return new ErrorResponseException(
                new ErrorResponse(code, code, "models", null, "models", "request-1", "host-1"),
                null,
                null
        );
    }
}
