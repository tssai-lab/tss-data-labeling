package com.tss.platform.service;

import com.tss.platform.dto.CodeUploadResultDto;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeUploadServiceDelegationTest {

    @Test
    void legacyUploadUsesSharedImportOrchestrator() {
        CodeAssetImportService importService = mock(CodeAssetImportService.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip", new byte[]{1}
        );
        CodeUploadResultDto expected = CodeUploadResultDto.builder()
                .codeAssetId("asset-1")
                .codeVersionId("version-1")
                .storagePath("users/1/codes/asset-1/versions/version-1/artifact.zip")
                .build();
        when(importService.importLegacy(
                file, "asset", "v1", "profile-1", "remark"
        )).thenReturn(expected);

        CodeUploadResultDto actual = new CodeUploadService(importService).upload(
                file, "asset", "v1", "profile-1", "remark"
        );

        assertSame(expected, actual);
        verify(importService).importLegacy(
                file, "asset", "v1", "profile-1", "remark"
        );
    }
}
