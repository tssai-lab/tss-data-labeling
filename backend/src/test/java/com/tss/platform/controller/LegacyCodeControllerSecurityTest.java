package com.tss.platform.controller;

import com.tss.platform.dto.CodeUploadResultDto;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import com.tss.platform.service.CodeApprovalForbiddenException;
import com.tss.platform.service.CodeArtifactStorageException;
import com.tss.platform.service.CodeUploadService;
import com.tss.platform.service.CodeValidationException;
import com.tss.platform.service.CodeVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyCodeControllerSecurityTest {

    @Test
    void approvalDenialUsesLegacyFailureEnvelopeWithoutInternalDetails() {
        CodeVersionService service = mock(CodeVersionService.class);
        when(service.approve("secret-version"))
                .thenThrow(new CodeApprovalForbiddenException());

        var response = new CodeVersionController(service).approve("secret-version");

        assertFalse(response.isSuccess());
        assertEquals("代码版本审批失败", response.getErrorMessage());
    }

    @Test
    void validationAndAccessFailureUsesSafeLegacyEnvelope() {
        CodeVersionService service = mock(CodeVersionService.class);
        when(service.trainingCheck("version-1", "profile-1")).thenThrow(
                new CodeValidationException(
                        "STORAGE_REFERENCE_INVALID",
                        "users/99/private/artifact.zip token=secret"
                )
        );

        var response = new CodeVersionController(service).trainingCheck(
                "version-1", "profile-1"
        );

        assertFalse(response.isSuccess());
        assertEquals("代码版本校验失败", response.getErrorMessage());
        assertFalse(response.getErrorMessage().contains("users/"));
        assertFalse(response.getErrorMessage().contains("secret"));
    }

    @Test
    void approvedListFailureUsesSafeLegacyEnvelope() {
        CodeVersionService service = mock(CodeVersionService.class);
        when(service.listApprovedForTraining()).thenThrow(
                new IllegalStateException("jdbc:postgresql://secret-host")
        );

        var response = new CodeVersionController(service).listApproved();

        assertFalse(response.isSuccess());
        assertEquals("代码版本列表加载失败", response.getErrorMessage());
        assertFalse(response.getErrorMessage().contains("secret-host"));
    }

    @Test
    void uploadStorageOrZipFailureUsesSafeLegacyEnvelope() {
        CodeUploadService service = mock(CodeUploadService.class);
        AuditHooks auditHooks = mock(AuditHooks.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip", new byte[]{1}
        );
        when(service.upload(file, "asset", "v1", "profile-1", null))
                .thenThrow(new CodeArtifactStorageException());

        CodeUploadController controller = new CodeUploadController(service);
        ReflectionTestUtils.setField(controller, "auditHooks", auditHooks);
        var response = controller.upload(
                file, "asset", "v1", "profile-1", null
        );

        assertFalse(response.isSuccess());
        assertEquals("代码资产导入失败", response.getErrorMessage());
        assertFalse(response.getErrorMessage().contains("storage"));
    }

    @Test
    void successfulUploadAuditsStableAssetIdWithoutStoragePath() {
        CodeUploadService service = mock(CodeUploadService.class);
        AuditHooks auditHooks = mock(AuditHooks.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip", new byte[]{1}
        );
        CodeUploadResultDto result = CodeUploadResultDto.builder()
                .codeAssetId("code-asset-1")
                .codeVersionId("code-version-1")
                .storagePath("users/99/private/artifact.zip")
                .build();
        when(service.upload(file, "asset", "v1", "profile-1", null))
                .thenReturn(result);

        CodeUploadController controller = new CodeUploadController(service);
        ReflectionTestUtils.setField(controller, "auditHooks", auditHooks);
        controller.upload(file, "asset", "v1", "profile-1", null);

        verify(auditHooks).upload(
                AuditObjectType.TRAINING_CODE,
                "code-asset-1",
                "CODE_UPLOAD",
                true,
                null
        );
    }
}
