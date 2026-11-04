package com.tss.platform.service;

import com.tss.platform.asset.spec.ArtifactSpecEvidence;
import io.minio.StatObjectResponse;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.nio.charset.StandardCharsets;

@Service
public class ModelArtifactIntegrityService {

    private final MinioService minioService;

    public ModelArtifactIntegrityService(MinioService minioService) {
        this.minioService = minioService;
    }

    public Inspection inspect(String objectName, Long expectedSize) {
        return inspect(objectName, expectedSize, null);
    }

    public Inspection inspect(String objectName, Long expectedSize, String taskType) {
        validateMetadata(objectName, expectedSize, null);
        requireMatchingStat(objectName, expectedSize);
        MessageDigest digest = sha256();
        CountingInputStream counting = null;
        String artifactSpecId = null;
        try (InputStream source = minioService.downloadStream(objectName)) {
            counting = new CountingInputStream(source);
            DigestInputStream digestInput = new DigestInputStream(counting, digest);
            if (isZip(objectName)) {
                ModelWeightZipValidator.Inspection archive = validateZipAndDrain(digestInput);
                artifactSpecId = ArtifactSpecEvidence.recognizeModelArchive(
                        taskType,
                        archive.filePaths()
                );
            } else {
                if (expectedSize > ModelWeightZipValidator.MAX_SINGLE_FILE_BYTES) {
                    throw new ModelArtifactException(
                            "model weight file size exceeds the supported limit",
                            false
                    );
                }
                drain(digestInput);
                artifactSpecId = ArtifactSpecEvidence.recognizeSingleModel(taskType, objectName);
            }
        } catch (ModelArtifactException exception) {
            throw exception;
        } catch (Exception exception) {
            throw storageException("model artifact could not be read", exception);
        }
        long actualSize = counting == null ? 0 : counting.count();
        if (actualSize != expectedSize) {
            throw new ModelArtifactException("model artifact length changed while hashing", false);
        }
        return new Inspection(
                actualSize,
                HexFormat.of().formatHex(digest.digest()),
                artifactSpecId
        );
    }

    public InputStream openVerified(
            String objectName,
            Long expectedSize,
            String expectedSha256,
            Consumer<Inspection> successHandler,
            Runnable deterministicFailureHandler
    ) {
        validateMetadata(objectName, expectedSize, expectedSha256);
        requireMatchingStat(objectName, expectedSize);
        try {
            return new IntegrityVerifyingInputStream(
                    new BufferedInputStream(minioService.downloadStream(objectName)),
                    expectedSize,
                    expectedSha256,
                    successHandler,
                    deterministicFailureHandler
            );
        } catch (Exception exception) {
            throw storageException("model artifact could not be opened", exception);
        }
    }

    private void requireMatchingStat(String objectName, long expectedSize) {
        StatObjectResponse stat;
        try {
            stat = minioService.stat(objectName);
        } catch (Exception exception) {
            throw storageException("model artifact could not be inspected", exception);
        }
        if (stat.size() != expectedSize) {
            throw new ModelArtifactException("model artifact size does not match metadata", false);
        }
    }

    private static void validateMetadata(
            String objectName,
            Long expectedSize,
            String expectedSha256
    ) {
        if (objectName == null || objectName.isBlank()) {
            throw new ModelArtifactException("model artifact storagePath is blank", false);
        }
        if (expectedSize == null || expectedSize <= 0) {
            throw new ModelArtifactException("model artifact size metadata is invalid", false);
        }
        if (expectedSha256 != null
                && !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new ModelArtifactException("model artifact SHA-256 metadata is invalid", false);
        }
    }

    private static ModelWeightZipValidator.Inspection validateZipAndDrain(
            DigestInputStream digestInput
    ) throws Exception {
        ZipInputStream zip = new ZipInputStream(digestInput, StandardCharsets.UTF_8);
        try {
            ModelWeightZipValidator.Inspection inspection;
            try {
                inspection = ModelWeightZipValidator.validate(zip);
            } catch (IllegalArgumentException | ZipException exception) {
                throw new ModelArtifactException(
                        "model artifact ZIP structure is invalid",
                        false,
                        exception
                );
            }
            // ZipInputStream can stop at the central directory. Drain the remaining raw
            // bytes so the digest covers the exact immutable object, including trailers.
            drain(digestInput);
            return inspection;
        } finally {
            zip.close();
        }
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) != -1) {
            // Drain only.
        }
    }

    private static boolean isZip(String objectName) {
        return objectName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ModelArtifactException storageException(
            String message,
            Exception exception
    ) {
        String root = rootMessage(exception).toLowerCase(Locale.ROOT);
        boolean missing = root.contains("nosuchkey")
                || root.contains("no such key")
                || root.contains("not found")
                || root.contains("404");
        return new ModelArtifactException(message, !missing, exception);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    public record Inspection(long sizeBytes, String sha256, String artifactSpecId) {
        public Inspection(long sizeBytes, String sha256) {
            this(sizeBytes, sha256, null);
        }
    }

    private static final class CountingInputStream extends FilterInputStream {

        private long count;

        private CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count += 1;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        private long count() {
            return count;
        }
    }

    private static final class IntegrityVerifyingInputStream extends InputStream {

        private static final int UNINITIALIZED = -2;

        private final InputStream source;
        private final long expectedSize;
        private final String expectedSha256;
        private final Consumer<Inspection> successHandler;
        private final Runnable deterministicFailureHandler;
        private final MessageDigest digest = sha256();

        private int pending = UNINITIALIZED;
        private long actualSize;
        private boolean finished;

        private IntegrityVerifyingInputStream(
                InputStream source,
                long expectedSize,
                String expectedSha256,
                Consumer<Inspection> successHandler,
                Runnable deterministicFailureHandler
        ) {
            this.source = Objects.requireNonNull(source);
            this.expectedSize = expectedSize;
            this.expectedSha256 = expectedSha256;
            this.successHandler = successHandler;
            this.deterministicFailureHandler = deterministicFailureHandler;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (finished) {
                return -1;
            }
            if (pending == UNINITIALIZED) {
                pending = source.read();
                if (pending < 0) {
                    verifyComplete();
                    finished = true;
                    return -1;
                }
            }

            buffer[offset] = (byte) pending;
            int count = 1;
            if (length > 1) {
                int read = source.read(buffer, offset + 1, length - 1);
                if (read > 0) {
                    count += read;
                } else if (read < 0) {
                    updateAndVerifyFinal(buffer, offset, count);
                    return count;
                }
            }

            int lookahead = source.read();
            if (lookahead < 0) {
                updateAndVerifyFinal(buffer, offset, count);
                return count;
            }
            pending = lookahead;
            updateDigest(buffer, offset, count);
            if (actualSize > expectedSize) {
                failIntegrity();
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }

        private void updateAndVerifyFinal(byte[] buffer, int offset, int count)
                throws IOException {
            updateDigest(buffer, offset, count);
            verifyComplete();
            pending = UNINITIALIZED;
            finished = true;
        }

        private void updateDigest(byte[] buffer, int offset, int count) {
            digest.update(buffer, offset, count);
            actualSize += count;
        }

        private void verifyComplete() throws IOException {
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (actualSize != expectedSize
                    || (expectedSha256 != null
                    && !expectedSha256.equals(actualSha256))) {
                failIntegrity();
            }
            if (successHandler != null) {
                try {
                    successHandler.accept(new Inspection(actualSize, actualSha256));
                } catch (RuntimeException exception) {
                    throw new IOException(
                            "model artifact verification could not be recorded",
                            exception
                    );
                }
            }
        }

        private void failIntegrity() throws IOException {
            IOException failure = new IOException(
                    "model artifact integrity verification failed"
            );
            if (deterministicFailureHandler != null) {
                try {
                    deterministicFailureHandler.run();
                } catch (RuntimeException handlerFailure) {
                    failure.addSuppressed(handlerFailure);
                }
            }
            finished = true;
            try {
                source.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
