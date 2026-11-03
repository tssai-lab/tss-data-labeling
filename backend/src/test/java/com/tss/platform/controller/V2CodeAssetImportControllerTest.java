package com.tss.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.controller.v2.V2CodeAssetImportController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2CodeAssetImportMetadata;
import com.tss.platform.module1.service.AuditHooks;
import com.tss.platform.service.CodeAssetImportCommand;
import com.tss.platform.service.CodeAssetImportException;
import com.tss.platform.service.CodeAssetImportResult;
import com.tss.platform.service.CodeAssetImportService;
import com.tss.platform.service.CodeValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2CodeAssetImportControllerTest {

    private CodeAssetImportService service;
    private AuditHooks auditHooks;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = mock(CodeAssetImportService.class);
        auditHooks = mock(AuditHooks.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new V2CodeAssetImportController(service, auditHooks)
        ).setControllerAdvice(new V2ExceptionHandler()).build();
    }

    @Test
    void multipartImportReturnsCreatedSafeDtoWithoutStorageDetails() throws Exception {
        when(service.importAsset(any(), any())).thenReturn(new CodeAssetImportResult(
                "asset-1",
                "version-1",
                "v1",
                "source.zip",
                128,
                "profile-1",
                "READY",
                "PASSED",
                "CODE_ASSET_POLICY_V1",
                "PENDING",
                "a".repeat(64),
                Instant.parse("2026-07-13T10:00:00Z")
        ));
        V2CodeAssetImportMetadata metadata = new V2CodeAssetImportMetadata(
                "Training asset",
                "v1",
                "profile-1",
                "TRAINING",
                "python:3.11",
                "train.py",
                "NLP",
                "remark"
        );
        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "metadata.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(metadata)
        );
        MockMultipartFile filePart = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                new byte[]{1, 2, 3}
        );

        String body = mockMvc.perform(multipart("/api/v2/code-assets/import")
                        .file(metadataPart)
                        .file(filePart))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.assetId").value("asset-1"))
                .andExpect(jsonPath("$.versionId").value("version-1"))
                .andExpect(jsonPath("$.artifactSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.validationStatus").value("PASSED"))
                .andExpect(jsonPath("$.approvalStatus").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (String forbidden : new String[]{
                "storagePath", "objectName", "bucket", "ownerUserId", "deleted", "url"
        }) {
            org.junit.jupiter.api.Assertions.assertFalse(body.contains(forbidden));
        }
        ArgumentCaptor<CodeAssetImportCommand> commandCaptor =
                ArgumentCaptor.forClass(CodeAssetImportCommand.class);
        verify(service).importAsset(any(), commandCaptor.capture());
        assertEquals("Training asset", commandCaptor.getValue().name());
        assertEquals("train.py", commandCaptor.getValue().entryScript());
    }

    @Test
    void strictZipValidationFailureIs422WithTraceAndSafeReasonOnly() throws Exception {
        doThrow(new CodeValidationException(
                "ZIP_PATH_TRAVERSAL",
                "users/9/private.zip token=secret"
        )).when(service).importAsset(any(), any());

        String body = performImport()
                .andExpect(status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.errorCode").value("CODE_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reasonCode").value("ZIP_PATH_TRAVERSAL"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("users/9"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("secret"));
    }

    @Test
    void finalizationFailureIs503WithoutInternalDetails() throws Exception {
        doThrow(new CodeAssetImportException())
                .when(service).importAsset(any(), any());

        performImport()
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.errorCode").value("CODE_STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.reasonCode").value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void missingMultipartPartsAre400WithTraceAndDoNotInvokeImport() throws Exception {
        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "metadata.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(new V2CodeAssetImportMetadata(
                        "Training asset", "v1", "profile-1", "TRAINING",
                        "python:3.11", "train.py", "NLP", "remark"
                ))
        );
        MockMultipartFile filePart = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/api/v2/code-assets/import")
                        .file(filePart)
                        .header("X-Trace-Id", "trace-missing-metadata"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Trace-Id", "trace-missing-metadata"))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.traceId").value("trace-missing-metadata"));

        mockMvc.perform(multipart("/api/v2/code-assets/import")
                        .file(metadataPart)
                        .header("X-Trace-Id", "trace-missing-file"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Trace-Id", "trace-missing-file"))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.traceId").value("trace-missing-file"));

        verify(service, never()).importAsset(any(), any());
    }

    @Test
    void codeAssetBadRequestNeverEchoesUserInputOrLegacyReasonField() throws Exception {
        doThrow(new IllegalArgumentException(
                "unsupported trainingProfile=private-profile-user-input"
        )).when(service).importAsset(any(), any());

        String body = performImport()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.reasonCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.reason").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("private-profile"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("user-input"));
    }

    private org.springframework.test.web.servlet.ResultActions performImport() throws Exception {
        V2CodeAssetImportMetadata metadata = new V2CodeAssetImportMetadata(
                "Training asset",
                "v1",
                "profile-1",
                "TRAINING",
                "python:3.11",
                "train.py",
                "NLP",
                "remark"
        );
        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "metadata.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(metadata)
        );
        MockMultipartFile filePart = new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                new byte[]{1, 2, 3}
        );
        return mockMvc.perform(multipart("/api/v2/code-assets/import")
                .file(metadataPart)
                .file(filePart));
    }
}
