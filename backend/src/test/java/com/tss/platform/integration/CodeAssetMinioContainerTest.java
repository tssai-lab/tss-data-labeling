package com.tss.platform.integration;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.service.CodeArtifactStorageService;
import com.tss.platform.service.CodeFilePolicy;
import com.tss.platform.service.CodeZipArchiveService;
import com.tss.platform.service.MinioService;
import com.tss.platform.service.StoredCodeArtifact;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class CodeAssetMinioContainerTest {

    private static final int MINIO_API_PORT = 9000;
    private static final String TEST_ACCESS_KEY = "codeasset-it-access";
    private static final String TEST_SECRET_KEY = "codeasset-it-secret-2026";
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse(
            "minio/minio:RELEASE.2025-09-07T16-13-09Z"
    );

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(MINIO_IMAGE)
            .withEnv("MINIO_ROOT_USER", TEST_ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", TEST_SECRET_KEY)
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(MINIO_API_PORT)
            .waitingFor(Wait.forHttp("/minio/health/ready")
                    .forPort(MINIO_API_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private static MinioService minioService;
    private static CodeArtifactStorageService storageService;
    private static CodeZipArchiveService archiveService;

    static {
        com.tss.platform.testsupport.IsolatedIntegrationContainers.configure(MINIO);
    }

    @BeforeAll
    static void setUpMinio() throws Exception {
        MinioClient minioClient = MinioClient.builder()
                .endpoint(mappedEndpoint())
                .credentials(TEST_ACCESS_KEY, TEST_SECRET_KEY)
                .build();
        String bucket = "code-asset-it-" + compactUuid();
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());

        MinioConfig minioConfig = new MinioConfig();
        minioConfig.setBucket(bucket);
        minioService = new MinioService(minioClient, minioConfig);
        storageService = new CodeArtifactStorageService(minioService, new CodeFilePolicy());
        archiveService = new CodeZipArchiveService();
        minioService.assertConnected();
    }

    @Test
    void uploadsAndReadsActualStoredBytesWithIndependentSha256() throws Exception {
        String objectName = uniqueVersionPrefix("stored-hash") + "artifact.zip";
        Map<String, byte[]> sourceFiles = new LinkedHashMap<>();
        sourceFiles.put("src/train.py", "print('container-backed')\n".getBytes(StandardCharsets.UTF_8));
        sourceFiles.put("README.md", "# MinIO integration\n".getBytes(StandardCharsets.UTF_8));
        byte[] uploadedArchive = archiveService.writeDeterministic(sourceFiles);

        storageService.upload(objectName, uploadedArchive);
        StoredCodeArtifact artifact = storageService.read(objectName);
        byte[] actualStoredBytes;
        try (InputStream input = minioService.downloadStream(objectName)) {
            actualStoredBytes = input.readAllBytes();
        }
        String actualStoredSha256 = sha256(actualStoredBytes);

        assertAll(
                () -> assertEquals(objectName, artifact.objectName()),
                () -> assertArrayEquals(uploadedArchive, actualStoredBytes),
                () -> assertArrayEquals(actualStoredBytes, artifact.bytes()),
                () -> assertEquals(actualStoredBytes.length, artifact.sizeBytes()),
                () -> assertEquals(actualStoredSha256, artifact.artifactSha256()),
                () -> assertEquals(sha256(uploadedArchive), actualStoredSha256)
        );

        LinkedHashMap<String, byte[]> downloadedFiles = archiveService.readEntries(
                new ByteArrayInputStream(artifact.bytes())
        );
        assertAll(
                () -> assertEquals(
                        List.of("README.md", "src/train.py"),
                        new ArrayList<>(downloadedFiles.keySet())
                ),
                () -> assertArrayEquals(sourceFiles.get("README.md"), downloadedFiles.get("README.md")),
                () -> assertArrayEquals(sourceFiles.get("src/train.py"), downloadedFiles.get("src/train.py"))
        );
    }

    @Test
    void deleteRemovesOnlyTheExactObjectAndLeavesItsSiblingReadable() throws Exception {
        String prefix = uniqueVersionPrefix("exact-delete");
        String objectName = prefix + "artifact.zip";
        String siblingObjectName = prefix + "artifact-copy.zip";
        byte[] objectBytes = archiveService.writeDeterministic(Map.of(
                "train.py", "print('delete me')\n".getBytes(StandardCharsets.UTF_8)
        ));
        byte[] siblingBytes = archiveService.writeDeterministic(Map.of(
                "train.py", "print('keep me')\n".getBytes(StandardCharsets.UTF_8)
        ));

        storageService.upload(objectName, objectBytes);
        storageService.upload(siblingObjectName, siblingBytes);
        assertEquals(
                Set.of(objectName, siblingObjectName),
                new HashSet<>(minioService.listObjectNames(prefix))
        );

        storageService.delete(objectName);

        ErrorResponseException missing = assertThrows(
                ErrorResponseException.class,
                () -> minioService.stat(objectName)
        );
        StoredCodeArtifact sibling = storageService.read(siblingObjectName);
        assertAll(
                () -> assertEquals("NoSuchKey", missing.errorResponse().code()),
                () -> assertArrayEquals(siblingBytes, sibling.bytes()),
                () -> assertEquals(sha256(siblingBytes), sibling.artifactSha256()),
                () -> assertEquals(
                        Set.of(siblingObjectName),
                        new HashSet<>(minioService.listObjectNames(prefix))
                )
        );
    }

    private static String mappedEndpoint() {
        String host = MINIO.getHost();
        String uriHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + uriHost + ":" + MINIO.getMappedPort(MINIO_API_PORT);
    }

    private static String uniqueVersionPrefix(String scenario) {
        return "users/7001/codes/" + scenario + "-" + compactUuid()
                + "/versions/version-1/";
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 must be available in the test runtime", exception);
        }
    }
}
