package com.tss.platform.service;

import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelArtifactIntegrityServiceTest {

    @Test
    void inspectValidatesZipAndHashesTheExactObjectInOneRead() throws Exception {
        byte[] artifact = zip("weights/model.pt", new byte[]{1, 2, 3, 4});
        String objectName = "users/7/models/model.zip";
        MinioService minio = minio(objectName, artifact);
        ModelArtifactIntegrityService service = new ModelArtifactIntegrityService(minio);

        ModelArtifactIntegrityService.Inspection inspection =
                service.inspect(objectName, (long) artifact.length);

        assertEquals(artifact.length, inspection.sizeBytes());
        assertEquals(sha256(artifact), inspection.sha256());
        assertNull(inspection.artifactSpecId());
        verify(minio, times(1)).downloadStream(objectName);
    }

    @Test
    void inspectRecognizesHfContractWhileHashingTheSameStream() throws Exception {
        byte[] artifact = zip(
                new Entry("model.yaml", "format: hf\n".getBytes()),
                new Entry("config.json", "{}".getBytes()),
                new Entry("model.safetensors", new byte[]{1, 2, 3})
        );
        String objectName = "users/7/models/model.zip";
        MinioService minio = minio(objectName, artifact);
        ModelArtifactIntegrityService service = new ModelArtifactIntegrityService(minio);

        ModelArtifactIntegrityService.Inspection inspection =
                service.inspect(objectName, (long) artifact.length, "CV");

        assertEquals("model.cv.hf-image/v1", inspection.artifactSpecId());
        assertEquals(sha256(artifact), inspection.sha256());
        verify(minio, times(1)).downloadStream(objectName);
    }

    @Test
    void inspectRecognizesNlpBertClassificationContractWhileHashingTheSameStream()
            throws Exception {
        byte[] artifact = zip(
                new Entry("model.yaml", "format: bert-sequence-classification\n".getBytes()),
                new Entry("config.json", "{}".getBytes()),
                new Entry("vocab.txt", "[PAD]\n[UNK]\n".getBytes()),
                new Entry("pytorch_model.bin", new byte[]{1, 2, 3})
        );
        String objectName = "users/7/models/minirbt.zip";
        MinioService minio = minio(objectName, artifact);
        ModelArtifactIntegrityService service = new ModelArtifactIntegrityService(minio);

        ModelArtifactIntegrityService.Inspection inspection =
                service.inspect(objectName, (long) artifact.length, "NLP");

        assertEquals(
                "model.nlp.bert-sequence-classification/v1",
                inspection.artifactSpecId()
        );
        assertEquals(sha256(artifact), inspection.sha256());
        verify(minio, times(1)).downloadStream(objectName);
    }

    @Test
    void inspectRecognizesDirectYoloWeightWithoutASecondObjectRead() throws Exception {
        byte[] artifact = "opaque-yolo-weight".getBytes();
        String objectName = "users/7/models/yolo11n.pt";
        MinioService minio = minio(objectName, artifact);
        ModelArtifactIntegrityService service = new ModelArtifactIntegrityService(minio);

        ModelArtifactIntegrityService.Inspection inspection =
                service.inspect(objectName, (long) artifact.length, "CV");

        assertEquals("model.cv.yolo-weight/v1", inspection.artifactSpecId());
        assertEquals(sha256(artifact), inspection.sha256());
        verify(minio, times(1)).downloadStream(objectName);
    }

    @Test
    void inspectRejectsHistoricalPseudoZipWithExecutableContent() throws Exception {
        byte[] artifact = zip("train.py", "print('unsafe')".getBytes());
        String objectName = "users/7/models/model.zip";
        MinioService minio = minio(objectName, artifact);
        ModelArtifactIntegrityService service = new ModelArtifactIntegrityService(minio);

        ModelArtifactException error = assertThrows(
                ModelArtifactException.class,
                () -> service.inspect(objectName, (long) artifact.length)
        );

        assertFalse(error.isStorageUnavailable());
        verify(minio, times(1)).downloadStream(objectName);
    }

    @Test
    void verifiedDownloadHashesWhileStreamingWithoutASecondObjectRead()
            throws Exception {
        byte[] artifact = new byte[]{10, 20, 30, 40, 50};
        String objectName = "users/7/models/model.pt";
        MinioService minio = minio(objectName, artifact);
        ModelArtifactIntegrityService service = new ModelArtifactIntegrityService(minio);

        byte[] downloaded;
        try (InputStream stream = service.openVerified(
                objectName,
                (long) artifact.length,
                sha256(artifact),
                null,
                null
        )) {
            downloaded = stream.readAllBytes();
        }

        assertArrayEquals(artifact, downloaded);
        verify(minio, times(1)).downloadStream(objectName);
    }

    @Test
    void verifiedDownloadFailsBeforeReturningTheFinalBytesAndInvalidates()
            throws Exception {
        byte[] artifact = new byte[]{10, 20, 30, 40, 50};
        String objectName = "users/7/models/model.pt";
        MinioService minio = minio(objectName, artifact);
        ModelArtifactIntegrityService service = new ModelArtifactIntegrityService(minio);
        AtomicInteger invalidations = new AtomicInteger();

        try (InputStream stream = service.openVerified(
                objectName,
                (long) artifact.length,
                "0".repeat(64),
                null,
                invalidations::incrementAndGet
        )) {
            assertThrows(IOException.class, stream::readAllBytes);
        }

        assertEquals(1, invalidations.get());
        verify(minio, times(1)).downloadStream(objectName);
    }

    @Test
    void historicalDownloadBackfillsComputedShaDuringTheSameStream()
            throws Exception {
        byte[] artifact = new byte[]{9, 8, 7, 6};
        String objectName = "users/7/models/historical.pt";
        MinioService minio = minio(objectName, artifact);
        ModelArtifactIntegrityService service = new ModelArtifactIntegrityService(minio);
        AtomicReference<ModelArtifactIntegrityService.Inspection> verified =
                new AtomicReference<>();

        try (InputStream stream = service.openVerified(
                objectName,
                (long) artifact.length,
                null,
                verified::set,
                null
        )) {
            assertArrayEquals(artifact, stream.readAllBytes());
        }

        assertEquals(sha256(artifact), verified.get().sha256());
        assertEquals(artifact.length, verified.get().sizeBytes());
        verify(minio, times(1)).downloadStream(objectName);
    }

    private static MinioService minio(String objectName, byte[] content)
            throws Exception {
        MinioService minio = mock(MinioService.class);
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn((long) content.length);
        when(minio.stat(objectName)).thenReturn(stat);
        when(minio.downloadStream(objectName))
                .thenReturn(new ByteArrayInputStream(content));
        return minio;
    }

    private static byte[] zip(String path, byte[] content) throws Exception {
        return zip(new Entry(path, content));
    }

    private static byte[] zip(Entry... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.path()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)
        );
    }

    private record Entry(String path, byte[] content) {
    }
}
