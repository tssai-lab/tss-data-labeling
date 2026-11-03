package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioServiceRangeTest {

    @Test
    void downloadsOnlyRequestedObjectRange() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioConfig config = new MinioConfig();
        config.setBucket("datasets");
        MinioService service = new MinioService(client, config);
        GetObjectResponse expected = mock(GetObjectResponse.class);
        when(client.getObject(org.mockito.ArgumentMatchers.any(GetObjectArgs.class))).thenReturn(expected);

        InputStream actual = service.downloadRange("objects/data.zip", 12, 34);

        assertSame(expected, actual);
        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(client).getObject(captor.capture());
        GetObjectArgs args = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("datasets", args.bucket());
        org.junit.jupiter.api.Assertions.assertEquals("objects/data.zip", args.object());
        org.junit.jupiter.api.Assertions.assertEquals(12L, args.offset());
        org.junit.jupiter.api.Assertions.assertEquals(34L, args.length());
    }

    @Test
    void rejectsInvalidRangeArguments() {
        MinioClient client = mock(MinioClient.class);
        MinioConfig config = new MinioConfig();
        config.setBucket("datasets");
        MinioService service = new MinioService(client, config);

        assertThrows(IllegalArgumentException.class, () -> service.downloadRange("data.zip", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> service.downloadRange("data.zip", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.downloadRange("data.zip", 0, -1));
    }
}
