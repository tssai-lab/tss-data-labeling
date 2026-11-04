package com.tss.platform.config;

import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.errors.ErrorResponseException;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MinioInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MinioInitializer.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Set<String> NON_RETRYABLE_ERROR_CODES = Set.of(
            "InvalidBucketName",
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "AccessDenied"
    );

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Sleeper sleeper;

    @Autowired
    public MinioInitializer(
            MinioClient minioClient,
            MinioConfig minioConfig,
            @Value("${minio.init.max-attempts:30}") int maxAttempts,
            @Value("${minio.init.initial-backoff-ms:1000}") long initialBackoffMs) {
        this(
                minioClient,
                minioConfig,
                maxAttempts,
                Duration.ofMillis(initialBackoffMs),
                duration -> Thread.sleep(duration.toMillis()));
    }

    MinioInitializer(
            MinioClient minioClient,
            MinioConfig minioConfig,
            int maxAttempts,
            Duration initialBackoff,
            Sleeper sleeper) {
        this.minioClient = minioClient;
        this.minioConfig = minioConfig;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff.isNegative() ? Duration.ZERO : initialBackoff;
        this.sleeper = sleeper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String bucket = minioConfig.getBucket();
        BucketExistsArgs bucketExistsArgs = BucketExistsArgs.builder().bucket(bucket).build();
        MakeBucketArgs makeBucketArgs = MakeBucketArgs.builder().bucket(bucket).build();
        Duration backoff = initialBackoff;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (!minioClient.bucketExists(bucketExistsArgs)) {
                    minioClient.makeBucket(makeBucketArgs);
                }
                return;
            } catch (Exception exception) {
                if (isNonRetryable(exception)) {
                    throw exception;
                }
                lastFailure = exception;
                if (attempt >= maxAttempts) {
                    break;
                }
                log.warn(
                        "MinIO bucket initialization failed on attempt {}/{}; retrying in {} ms",
                        attempt,
                        maxAttempts,
                        backoff.toMillis(),
                        exception);
                sleeper.sleep(backoff);
                backoff = nextBackoff(backoff);
            }
        }
        throw lastFailure;
    }

    private boolean isNonRetryable(Exception exception) {
        if (!(exception instanceof ErrorResponseException errorResponseException)
                || errorResponseException.errorResponse() == null) {
            return false;
        }
        return NON_RETRYABLE_ERROR_CODES.contains(errorResponseException.errorResponse().code());
    }

    private Duration nextBackoff(Duration current) {
        if (current.isZero()) {
            return Duration.ZERO;
        }
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
