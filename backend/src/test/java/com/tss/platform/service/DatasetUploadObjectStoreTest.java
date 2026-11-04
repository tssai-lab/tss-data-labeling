package com.tss.platform.service;

import com.tss.platform.entity.DatasetUploadChunk;
import io.minio.ComposeObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SDK 适配层只映射参数，不能暗中改变分片顺序、生命周期或重试策略。 */
class DatasetUploadObjectStoreTest {
    private final MinioClient client = mock(MinioClient.class);
    private final DatasetUploadObjectStore store = new DatasetUploadObjectStore(client, "test-datasets");

    @Test void composePreservesProvidedOrderAndDestination() throws Exception {
        var first = new DatasetUploadChunk();
        first.setObjectName("parts/b");
        var second = new DatasetUploadChunk();
        second.setObjectName("parts/a");
        store.compose(List.of(first, second), "datasets/final.zip");

        var args = ArgumentCaptor.forClass(ComposeObjectArgs.class);
        verify(client).composeObject(args.capture());
        assertEquals("test-datasets", args.getValue().bucket());
        assertEquals("datasets/final.zip", args.getValue().object());
        assertEquals(List.of("parts/b", "parts/a"), args.getValue().sources().stream().map(source -> source.object()).toList());
        assertTrue(args.getValue().sources().stream().allMatch(source -> "test-datasets".equals(source.bucket())));
        verifyNoMoreInteractions(client);
    }

    @Test void putPreservesBytesSizeTypeAndCallerStreamOwnership() throws Exception {
        var closed = new AtomicBoolean();
        try (var input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        }) {
            store.put(input, "parts/chunk-0", 3L, "application/octet-stream");
            var args = ArgumentCaptor.forClass(PutObjectArgs.class);
            verify(client).putObject(args.capture());
            assertEquals("test-datasets", args.getValue().bucket());
            assertEquals("parts/chunk-0", args.getValue().object());
            assertEquals(3L, args.getValue().objectSize());
            assertEquals("application/octet-stream", args.getValue().contentType());
            assertArrayEquals(new byte[]{1, 2, 3}, args.getValue().stream().readAllBytes());
            assertFalse(closed.get(), "调用方的 try-with-resources 负责关闭流");
            verifyNoMoreInteractions(client);
        }
        assertTrue(closed.get());
    }

    @Test void statReturnsOriginalEvidenceWithoutChangingObject() throws Exception {
        var evidence = mock(StatObjectResponse.class);
        when(client.statObject(any())).thenReturn(evidence);
        assertSame(evidence, store.stat("datasets/final.zip"));
        var args = ArgumentCaptor.forClass(StatObjectArgs.class);
        verify(client).statObject(args.capture());
        assertEquals("test-datasets", args.getValue().bucket());
        assertEquals("datasets/final.zip", args.getValue().object());
        verifyNoMoreInteractions(client);
    }

    @Test void failuresPropagateWithoutRetryOrCompensation() throws Exception {
        var failure = new IOException("injected storage failure");
        when(client.putObject(any())).thenThrow(failure);
        when(client.statObject(any())).thenThrow(failure);
        when(client.composeObject(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IOException.class, () -> store.put(new ByteArrayInputStream(new byte[]{1}), "part", 1L, "application/zip")));
        assertSame(failure, assertThrows(IOException.class, () -> store.stat("part")));
        var chunk = new DatasetUploadChunk();
        chunk.setObjectName("part");
        assertSame(failure, assertThrows(IOException.class, () -> store.compose(List.of(chunk), "final.zip")));
        verify(client, times(1)).putObject(any());
        verify(client, times(1)).statObject(any());
        verify(client, times(1)).composeObject(any());
        verifyNoMoreInteractions(client);
    }
}
