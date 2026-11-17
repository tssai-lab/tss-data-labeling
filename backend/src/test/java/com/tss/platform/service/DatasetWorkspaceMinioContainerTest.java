package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class DatasetWorkspaceMinioContainerTest {

    private static final int MINIO_API_PORT = 9000;
    private static final String ACCESS_KEY = "dataset-workspace-it";
    private static final String SECRET_KEY = "dataset-workspace-it-secret-2026";
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse(
            "minio/minio:RELEASE.2025-09-07T16-13-09Z"
    );

    @Container
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(MINIO_IMAGE)
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand(
                            "server",
                            "/data",
                            "--console-address",
                            ":9001"
                    )
                    .withExposedPorts(MINIO_API_PORT)
                    .waitingFor(Wait.forHttp("/minio/health/ready")
                            .forPort(MINIO_API_PORT)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(2)));

    private static MinioService minioService;

    static {
        com.tss.platform.testsupport.IsolatedIntegrationContainers.configure(MINIO);
    }

    @BeforeAll
    static void setUpMinio() throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(
                        "http://"
                                + MINIO.getHost()
                                + ":"
                                + MINIO.getMappedPort(MINIO_API_PORT)
                )
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
        String bucket = "dataset-workspace-it-" + compactUuid();
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        MinioConfig config = new MinioConfig();
        config.setBucket(bucket);
        minioService = new MinioService(client, config);
        minioService.assertConnected();
    }

    @Test
    void draftRawObjectSupportsFullDownloadAndRangePreview() throws Exception {
        byte[] content =
                "0123456789abcdefghijklmnopqrstuvwxyz"
                        .getBytes(StandardCharsets.UTF_8);
        String objectName = "dataset-workspace/raw/" + compactUuid() + ".mp4";
        minioService.uploadStream(
                objectName,
                new ByteArrayInputStream(content),
                content.length,
                "video/mp4"
        );
        Fixture fixture = new Fixture(objectName, "RAW");
        fixture.data.setDataType("VIDEO");
        fixture.data.setFileName("clip.mp4");
        fixture.data.setContentType("video/mp4");
        fixture.data.setSizeBytes((long) content.length);

        SampleFileService.SampleFileStream download =
                fixture.service.openWorkspaceDataDownload(
                        fixture.workspace.getId(),
                        fixture.sample.getId(),
                        fixture.data.getId()
                );
        SampleFileService.SampleFileStream range =
                fixture.service.openWorkspaceDataPreview(
                        fixture.workspace.getId(),
                        fixture.sample.getId(),
                        fixture.data.getId(),
                        "bytes=10-19"
                );

        assertArrayEquals(content, download.inputStream().readAllBytes());
        assertArrayEquals(
                java.util.Arrays.copyOfRange(content, 10, 20),
                range.inputStream().readAllBytes()
        );
        assertTrue(range.partial());
        assertEquals(10L, range.rangeStart());
        assertEquals(19L, range.rangeEnd());
        assertEquals((long) content.length, range.totalSize());
    }

    @Test
    void inheritedZipStoredEntryRemainsReadableByExactOffset() throws Exception {
        byte[] prefix = "header".getBytes(StandardCharsets.UTF_8);
        byte[] entry = "zip-entry-content".getBytes(StandardCharsets.UTF_8);
        byte[] object = new byte[prefix.length + entry.length];
        System.arraycopy(prefix, 0, object, 0, prefix.length);
        System.arraycopy(entry, 0, object, prefix.length, entry.length);
        String objectName = "dataset-workspace/zip/" + compactUuid() + ".zip";
        minioService.uploadStream(
                objectName,
                new ByteArrayInputStream(object),
                object.length,
                "application/zip"
        );
        Fixture fixture = new Fixture(objectName, "ZIP");
        fixture.data.setDataType("TEXT");
        fixture.data.setFileName("labels.txt");
        fixture.data.setContentType("text/plain");
        fixture.data.setSizeBytes((long) entry.length);
        fixture.data.setZipDataOffset((long) prefix.length);
        fixture.data.setCompressedSize((long) entry.length);
        fixture.data.setUncompressedSize((long) entry.length);
        fixture.data.setCompressionMethod("STORED");

        SampleFileService.SampleFileStream download =
                fixture.service.openWorkspaceDataDownload(
                        fixture.workspace.getId(),
                        fixture.sample.getId(),
                        fixture.data.getId()
                );

        assertArrayEquals(entry, download.inputStream().readAllBytes());
    }

    private static final class Fixture {
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final DatasetSampleRepository sampleRepo =
                mock(DatasetSampleRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final DatasetPackageRepository packageRepo =
                mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetAsset asset = new DatasetAsset();
        private final DatasetVersion workspace = new DatasetVersion();
        private final DatasetSample sample = new DatasetSample();
        private final DatasetSampleData data = new DatasetSampleData();
        private final DatasetPackage datasetPackage = new DatasetPackage();
        private final SampleFileService service;

        private Fixture(String objectName, String storageKind) {
            asset.setId("asset-" + compactUuid());
            asset.setOwnerUserId(7);
            asset.setDeleted(false);
            workspace.setId("workspace-" + compactUuid());
            workspace.setAssetId(asset.getId());
            workspace.setStatus("DRAFT");
            workspace.setDeleted(false);
            sample.setId("sample-" + compactUuid());
            sample.setDatasetVersionId(workspace.getId());
            sample.setDeleted(false);
            data.setId("data-" + compactUuid());
            data.setSampleId(sample.getId());
            data.setDatasetVersionId(workspace.getId());
            data.setDeleted(false);
            datasetPackage.setId("package-" + compactUuid());
            datasetPackage.setDatasetAssetId(asset.getId());
            datasetPackage.setStoragePath(objectName);
            datasetPackage.setStorageKind(storageKind);
            datasetPackage.setStatus("READY");
            datasetPackage.setDeleted(false);
            data.setPackageId(datasetPackage.getId());

            when(dataRepo.findByIdAndDatasetVersionId(
                    data.getId(),
                    workspace.getId()
            )).thenReturn(Optional.of(data));
            when(sampleRepo.findByIdAndDatasetVersionId(
                    sample.getId(),
                    workspace.getId()
            )).thenReturn(Optional.of(sample));
            when(sampleRepo.findByIdAndDeletedFalse(sample.getId()))
                    .thenReturn(Optional.of(sample));
            when(versionRepo.findByIdAndDeletedFalse(workspace.getId()))
                    .thenReturn(Optional.of(workspace));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(authContext.canAccessOwner(asset.getOwnerUserId()))
                    .thenReturn(true);
            when(packageRepo.findByIdAndDeletedFalse(datasetPackage.getId()))
                    .thenReturn(Optional.of(datasetPackage));
            when(versionPackageRepo.existsByDatasetVersionIdAndPackageId(
                    workspace.getId(),
                    datasetPackage.getId()
            )).thenReturn(true);

            service = new SampleFileService(
                    dataRepo,
                    sampleRepo,
                    annotationRepo,
                    versionRepo,
                    assetRepo,
                    packageRepo,
                    versionPackageRepo,
                    minioService,
                    authContext
            );
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
