package com.tss.platform.controller;

import com.tss.platform.controller.v2.V2CodeVersionController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2CodeApprovalResult;
import com.tss.platform.dto.v2.V2CodeArtifactUpgradeResult;
import com.tss.platform.dto.v2.V2CodeConsumerManifest;
import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.dto.v2.V2CodeFileNode;
import com.tss.platform.dto.v2.V2CodeValidationResult;
import com.tss.platform.dto.v2.V2CodeVersionDto;
import com.tss.platform.service.CodeApprovalForbiddenException;
import com.tss.platform.service.CodeAssetAccessException;
import com.tss.platform.service.CodeArtifactStorageException;
import com.tss.platform.service.CodeContentTooLargeException;
import com.tss.platform.service.CodeValidationException;
import com.tss.platform.service.CodeWorkspaceConflictException;
import com.tss.platform.service.CodeArtifactUpgradeService;
import com.tss.platform.service.V2CodeVersionQueryService;
import com.tss.platform.service.V2CodeRiskQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2CodeVersionControllerTest {

    private V2CodeVersionQueryService service;
    private CodeArtifactUpgradeService artifactUpgradeService;
    private V2CodeRiskQueryService riskQueryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(V2CodeVersionQueryService.class);
        artifactUpgradeService = mock(CodeArtifactUpgradeService.class);
        riskQueryService = mock(V2CodeRiskQueryService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new V2CodeVersionController(
                        service, artifactUpgradeService, riskQueryService
                ))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();
    }

    @Test
    void getsDedicatedVersionDtoWithoutInternalStorageProperties() throws Exception {
        when(service.get("version-1")).thenReturn(versionDto());

        mvc.perform(get("/api/v2/code-versions/version-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("version-1"))
                .andExpect(jsonPath("$.assetId").value("asset-1"))
                .andExpect(jsonPath("$.artifactSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.storagePath").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.deleted").doesNotExist());
    }

    @Test
    void listsAssetVersionsThroughOwnerScopedVersionService() throws Exception {
        when(service.listForAsset("asset-1")).thenReturn(List.of(versionDto()));

        mvc.perform(get("/api/v2/code-assets/asset-1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("version-1"));
    }

    @Test
    void treeUsesPrefixQueryAndReturnsOnlyPublicDescriptorFields() throws Exception {
        V2CodeFileNode node = new V2CodeFileNode(
                "src/train.py", "train.py", "FILE", ".py", "python",
                "text/x-python", 12, true, false, true, null
        );
        when(service.tree("version-1", "src")).thenReturn(List.of(node));

        mvc.perform(get("/api/v2/code-versions/version-1/tree")
                        .param("prefix", "src"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("src/train.py"))
                .andExpect(jsonPath("$[0].languageId").value("python"))
                .andExpect(jsonPath("$[0].editable").value(false))
                .andExpect(jsonPath("$[0].contentHash").doesNotExist());

        verify(service).tree("version-1", "src");
    }

    @Test
    void contentUsesNestedPathQueryAndIsAlwaysReadOnly() throws Exception {
        V2CodeFileContent result = new V2CodeFileContent(
                "src/train.py", "train.py", "FILE", ".py", "python",
                "text/x-python", 12, true, false, true, null,
                "print('ok')", "UTF-8", "b".repeat(64), null, true
        );
        when(service.content("version-1", "src/train.py")).thenReturn(result);

        mvc.perform(get("/api/v2/code-versions/version-1/files/content")
                        .param("path", "src/train.py"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src/train.py"))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.workspaceRevision").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.contentHash").value("b".repeat(64)));

        verify(service).content("version-1", "src/train.py");
    }

    @Test
    void fullArchiveDownloadUsesRfc5987AndNoSniff() throws Exception {
        byte[] bytes = "zip".getBytes(StandardCharsets.UTF_8);
        when(service.downloadArchive("version-1"))
                .thenReturn(new V2CodeVersionQueryService.Download(
                        "训练 *'\" 代码.zip", "application/zip", bytes
                ));

        mvc.perform(get("/api/v2/code-versions/version-1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(header().string("Content-Disposition", containsString("%20%2A%27%22%20")))
                .andExpect(content().contentType("application/zip"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void singleFileDownloadUsesNestedPathAndNoSniff() throws Exception {
        byte[] bytes = "print('ok')".getBytes(StandardCharsets.UTF_8);
        when(service.downloadFile("version-1", "src/train.py"))
                .thenReturn(new V2CodeVersionQueryService.Download(
                        "train.py", "text/x-python", bytes
                ));

        mvc.perform(get("/api/v2/code-versions/version-1/files/download")
                        .param("path", "src/train.py"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType("text/x-python"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void downloadRejectsCrLfFilenameWithoutCreatingAnInjectedHeader() throws Exception {
        when(service.downloadArchive("version-1"))
                .thenReturn(new V2CodeVersionQueryService.Download(
                        "safe.zip\r\nX-Evil: injected", "application/zip", new byte[]{1}
                ));

        mvc.perform(get("/api/v2/code-versions/version-1/download")
                        .header("X-Trace-Id", "trace-download"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().doesNotExist("X-Evil"))
                .andExpect(header().string("X-Trace-Id", "trace-download"))
                .andExpect(jsonPath("$.errorCode").value("CODE_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("INVALID_DOWNLOAD_FILENAME"));
    }

    @Test
    void consumerManifestNeverContainsStoragePathOrUrl() throws Exception {
        V2CodeConsumerManifest manifest = new V2CodeConsumerManifest(
                "asset-1", "version-1", "training", "python:3.11",
                "src/train.py", "CUSTOM", "CUSTOM_PYTHON", "a".repeat(64),
                "validation-1", "code-asset-policy-v1", "approval-1"
        );
        when(service.consumerManifest("version-1")).thenReturn(manifest);

        mvc.perform(get("/api/v2/code-versions/version-1/consumer-manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value("version-1"))
                .andExpect(jsonPath("$.storagePath").doesNotExist())
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());
    }

    @Test
    void exposesValidationApprovalAndLifecycleCommands() throws Exception {
        V2CodeValidationResult validation = new V2CodeValidationResult(
                "code-asset-policy-v1", "a".repeat(64), "PASSED", null,
                "Code artifact validation passed", 1, true
        );
        V2CodeApprovalResult approval = new V2CodeApprovalResult(
                "approval-1", "version-1", "APPROVED", null,
                "a".repeat(64), "validation-1", "code-asset-policy-v1", Instant.EPOCH
        );
        when(service.validate("version-1")).thenReturn(validation);
        when(service.approve(eq("version-1"), any())).thenReturn(approval);
        when(service.deprecate("version-1")).thenReturn(versionDto("DEPRECATED"));
        when(service.archive("version-1")).thenReturn(versionDto("ARCHIVED"));
        when(artifactUpgradeService.upgrade("version-1")).thenReturn(
                new V2CodeArtifactUpgradeResult(
                        "version-1",
                        "a".repeat(64),
                        123L,
                        "PENDING",
                        true,
                        validation
                )
        );

        mvc.perform(post("/api/v2/code-versions/version-1/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.reused").value(true));
        mvc.perform(post("/api/v2/code-versions/version-1/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"));
        mvc.perform(post("/api/v2/code-versions/version-1/deprecate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPRECATED"));
        mvc.perform(post("/api/v2/code-versions/version-1/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(post("/api/v2/code-versions/version-1/artifact-upgrade"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value("version-1"))
                .andExpect(jsonPath("$.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.upgraded").value(true))
                .andExpect(jsonPath("$.validation.status").value("PASSED"))
                .andExpect(jsonPath("$.storagePath").doesNotExist())
                .andExpect(jsonPath("$.objectName").doesNotExist())
                .andExpect(jsonPath("$.bucket").doesNotExist())
                .andExpect(jsonPath("$.url").doesNotExist());
        verify(artifactUpgradeService).upgrade("version-1");
    }

    @Test
    void mapsOwnerIsolationAndContentLimitToRealHttpStatusesWithTraceIds() throws Exception {
        when(service.get("hidden"))
                .thenThrow(new CodeAssetAccessException());
        when(service.content("version-1", "large.txt"))
                .thenThrow(new CodeContentTooLargeException());

        mvc.perform(get("/api/v2/code-versions/hidden")
                        .header("X-Trace-Id", "trace-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Trace-Id", "trace-not-found"))
                .andExpect(jsonPath("$.errorCode").value("CODE_ASSET_NOT_FOUND"));
        mvc.perform(get("/api/v2/code-versions/version-1/files/content")
                        .param("path", "large.txt")
                        .header("X-Trace-Id", "trace-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("X-Trace-Id", "trace-large"))
                .andExpect(jsonPath("$.details.reasonCode").value("FILE_TOO_LARGE"));
    }

    @Test
    void mapsApprovalValidationAndLifecycleFailuresTo403_422_409() throws Exception {
        when(service.approve(eq("hidden"), any()))
                .thenThrow(new CodeApprovalForbiddenException());
        when(service.approve("hidden-empty", null))
                .thenThrow(new CodeApprovalForbiddenException());
        when(service.validate("version-1"))
                .thenThrow(new CodeValidationException(
                        "ENTRY_SCRIPT_MISSING", "validation failed"
                ));
        when(service.deprecate("version-1"))
                .thenThrow(new CodeWorkspaceConflictException(
                        "VERSION_LIFECYCLE_CONFLICT", "conflict"
                ));
        when(service.approve(eq("approval-conflict"), any()))
                .thenThrow(new CodeWorkspaceConflictException(
                        "APPROVAL_TERMINAL", "conflict"
                ));
        when(service.approve(eq("approval-evidence"), any()))
                .thenThrow(new CodeValidationException(
                        "VALIDATION_EVIDENCE_MISSING", "validation failed"
                ));
        when(artifactUpgradeService.upgrade("upgrade-storage"))
                .thenThrow(new CodeArtifactStorageException());

        mvc.perform(post("/api/v2/code-versions/hidden/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/code-versions/hidden-empty/approval"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/code-versions/version-1/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("ENTRY_SCRIPT_MISSING"));
        mvc.perform(post("/api/v2/code-versions/version-1/deprecate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("VERSION_LIFECYCLE_CONFLICT"));
        mvc.perform(post("/api/v2/code-versions/approval-conflict/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("APPROVAL_TERMINAL"));
        mvc.perform(post("/api/v2/code-versions/approval-evidence/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("VALIDATION_EVIDENCE_MISSING"));
        mvc.perform(post("/api/v2/code-versions/upgrade-storage/artifact-upgrade"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CODE_STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("STORAGE_UNAVAILABLE"));
    }

    private static V2CodeVersionDto versionDto() {
        return versionDto("READY");
    }

    private static V2CodeVersionDto versionDto(String status) {
        return new V2CodeVersionDto(
                "version-1", "asset-1", "v1", "code.zip", 123L, status,
                "a".repeat(64), "PASSED", "code-asset-policy-v1", "PENDING",
                Instant.EPOCH, Instant.EPOCH, null, null
        );
    }
}
