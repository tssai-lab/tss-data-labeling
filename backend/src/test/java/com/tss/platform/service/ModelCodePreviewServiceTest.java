package com.tss.platform.service;

import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCodePreviewServiceTest {

    @Test
    void rejectsMalformedUtf8InsteadOfReturningReplacementCharacters()
            throws Exception {
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        MinioService minioService = mock(MinioService.class);
        ModelVersion version = version();
        when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                .thenReturn(Optional.of(version));
        when(minioService.downloadStream(version.getStoragePath()))
                .thenReturn(new ByteArrayInputStream(zip(
                        "src/train.py",
                        new byte[]{'p', 'r', 'i', 'n', 't', '(', (byte) 0xC3, 0x28, ')'}
                )));
        ModelCodePreviewService service = new ModelCodePreviewService(
                versionRepo,
                mock(ModelAssetRepository.class),
                minioService,
                mock(AuthContext.class)
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.previewCode(version.getId(), "src/train.py")
        );

        assertEquals("代码文件不是有效的 UTF-8 文本", error.getMessage());
    }

    @Test
    void acceptsStrictlyValidUtf8() throws Exception {
        ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        MinioService minioService = mock(MinioService.class);
        ModelVersion version = version();
        when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                .thenReturn(Optional.of(version));
        when(minioService.downloadStream(version.getStoragePath()))
                .thenReturn(new ByteArrayInputStream(zip(
                        "README.md",
                        "模型说明".getBytes(StandardCharsets.UTF_8)
                )));
        ModelCodePreviewService service = new ModelCodePreviewService(
                versionRepo,
                mock(ModelAssetRepository.class),
                minioService,
                mock(AuthContext.class)
        );

        assertEquals(
                "模型说明",
                service.previewCode(version.getId(), "README.md").getContent()
        );
    }

    private static ModelVersion version() {
        ModelVersion version = new ModelVersion();
        version.setId("model-ver-1");
        version.setAssetId("model-asset-1");
        version.setStoragePath("users/7/models/model.zip");
        version.setOwnerUserId(7);
        version.setDeleted(false);
        return version;
    }

    private static byte[] zip(String path, byte[] content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
