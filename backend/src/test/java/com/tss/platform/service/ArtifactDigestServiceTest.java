package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactDigestServiceTest {

    @Test
    void calculatesSha256AndVerifiesSize() throws Exception {
        byte[] bytes = "real-artifact".getBytes(StandardCharsets.UTF_8);
        MinioService minioService = mock(MinioService.class);
        when(minioService.downloadStream("models/base.pt")).thenReturn(new ByteArrayInputStream(bytes));

        ArtifactDigestService.DigestResult result = new ArtifactDigestService(minioService)
                .digest("models/base.pt", (long) bytes.length);

        assertEquals("f5f1b8ddb00fd98168b544eda9643bd47054c5c639e869cda78cd3f9bbd60370", result.sha256());
        assertEquals(bytes.length, result.sizeBytes());
    }

    @Test
    void rejectsSizeMismatch() throws Exception {
        MinioService minioService = mock(MinioService.class);
        when(minioService.downloadStream("datasets/data.zip"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactDigestService(minioService).digest("datasets/data.zip", 4L)
        );

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("size mismatch"));
    }
}
