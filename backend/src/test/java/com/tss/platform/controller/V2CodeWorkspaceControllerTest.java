package com.tss.platform.controller;

import com.tss.platform.controller.v2.V2CodeWorkspaceController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2CodeWorkspaceDto;
import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.service.CodeContentTooLargeException;
import com.tss.platform.service.CodeFileDescriptor;
import com.tss.platform.service.CodeFilePolicy;
import com.tss.platform.service.CodeValidationResult;
import com.tss.platform.service.CodeValidationException;
import com.tss.platform.service.CodeWorkspaceContent;
import com.tss.platform.service.CodeWorkspaceDownload;
import com.tss.platform.service.CodeWorkspaceFileMetadata;
import com.tss.platform.service.CodeValidationService;
import com.tss.platform.service.CodeWorkspaceOverlayService;
import com.tss.platform.service.CodeWorkspacePublishService;
import com.tss.platform.service.CodeWorkspaceTreeNode;
import com.tss.platform.service.V2CodeAssetService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2CodeWorkspaceControllerTest {

    @Test
    void treeUsesNestedPrefixQueryAndOmitsInternalContentHash() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());
        when(overlay.tree("workspace-1", "src/train"))
                .thenReturn(List.of(new CodeWorkspaceTreeNode(
                        "src/train/main.py",
                        "main.py",
                        "FILE",
                        ".py",
                        "python",
                        "text/x-python",
                        12,
                        true,
                        true,
                        true,
                        null,
                        "a".repeat(64)
                )));

        MockMvc mvc = mvc(assetService, overlay);
        mvc.perform(get("/api/v2/code-workspaces/workspace-1/tree")
                        .queryParam("prefix", "src/train"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("src/train/main.py"))
                .andExpect(jsonPath("$[0].languageId").value("python"))
                .andExpect(jsonPath("$[0].contentHash").doesNotExist())
                .andExpect(jsonPath("$[0].storagePath").doesNotExist());
        verify(assetService).requireOwnedWorkspace("workspace-1");
        verify(overlay).tree("workspace-1", "src/train");
    }

    @Test
    void contentUsesPathQueryAndClosedWorkspaceIsReadOnly() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        V2CodeWorkspaceDto closed = new V2CodeWorkspaceDto(
                "workspace-1", "asset-1", null, null, "ABANDONED", 3L,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, true
        );
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(closed);
        byte[] bytes = "print('ok')".getBytes(StandardCharsets.UTF_8);
        CodeFileDescriptor descriptor = descriptor("src/train.py", bytes, true);
        when(overlay.content("workspace-1", "src/train.py"))
                .thenReturn(new CodeWorkspaceContent(
                        descriptor, "print('ok')", "UTF-8", descriptor.contentHash(),
                        3L, true, bytes
                ));

        mvc(assetService, overlay).perform(
                        get("/api/v2/code-workspaces/workspace-1/files/content")
                                .queryParam("path", "src/train.py"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src/train.py"))
                .andExpect(jsonPath("$.languageId").value("python"))
                .andExpect(jsonPath("$.contentHash").value(descriptor.contentHash()))
                .andExpect(jsonPath("$.workspaceRevision").value(3))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.rawBytes").doesNotExist())
                .andExpect(jsonPath("$.storagePath").doesNotExist());
        verify(overlay).content("workspace-1", "src/train.py");
    }

    @Test
    void exposesLargeFileHashMetadataForSafeDeleteCas() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());
        CodeFileDescriptor descriptor = new CodeFileDescriptor(
                "large.txt", "large.txt", "FILE", ".txt", "plaintext",
                "text/plain", CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1,
                false, false, true, "FILE_TOO_LARGE", "b".repeat(64)
        );
        when(overlay.metadata("workspace-1", "large.txt")).thenReturn(
                new CodeWorkspaceFileMetadata(
                        descriptor, descriptor.contentHash(), 2L, false
                )
        );

        mvc(assetService, overlay).perform(
                        get("/api/v2/code-workspaces/workspace-1/files/metadata")
                                .queryParam("path", "large.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("large.txt"))
                .andExpect(jsonPath("$.sizeBytes")
                        .value(CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1))
                .andExpect(jsonPath("$.contentHash").value("b".repeat(64)))
                .andExpect(jsonPath("$.workspaceRevision").value(2))
                .andExpect(jsonPath("$.previewable").value(false))
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.deletable").value(true))
                .andExpect(jsonPath("$.storagePath").doesNotExist());
    }

    @Test
    void oversizedPreviewIs413ButDownloadStillUsesRfc5987AndNoSniff() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());
        when(overlay.content("workspace-1", "large.txt"))
                .thenThrow(new CodeContentTooLargeException());
        byte[] bytes = "downloadable".getBytes(StandardCharsets.UTF_8);
        when(overlay.download("workspace-1", "large.txt"))
                .thenReturn(new CodeWorkspaceDownload(
                        descriptor("目录/训练 文件.txt", bytes, false), bytes
                ));
        MockMvc mvc = mvc(assetService, overlay);

        mvc.perform(get("/api/v2/code-workspaces/workspace-1/files/content")
                        .queryParam("path", "large.txt")
                        .header("X-Trace-Id", "trace-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.details.reasonCode").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.traceId").value("trace-large"));

        mvc.perform(get("/api/v2/code-workspaces/workspace-1/files/download")
                        .queryParam("path", "large.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(content().contentType("text/plain"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void fileMutationsUseQueryPathAndReturnNewWorkspaceRevision() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());
        byte[] bytes = "print('new')".getBytes(StandardCharsets.UTF_8);
        CodeFileDescriptor descriptor = descriptor("src/new.py", bytes, true);
        CodeWorkspaceContent changed = new CodeWorkspaceContent(
                descriptor, "print('new')", "UTF-8", descriptor.contentHash(),
                3L, false, bytes
        );
        when(overlay.upsert("workspace-1", "src/new.py", bytes, 2L, null))
                .thenReturn(changed);
        when(overlay.move("workspace-1", "src/new.py", "src/main.py", 3L,
                descriptor.contentHash())).thenReturn(new CodeWorkspaceContent(
                descriptor("src/main.py", bytes, true), "print('new')", "UTF-8",
                descriptor.contentHash(), 4L, false, bytes
        ));
        when(overlay.delete("workspace-1", "src/main.py", 4L,
                descriptor.contentHash())).thenReturn(5L);
        MockMvc mvc = mvc(assetService, overlay);

        mvc.perform(put("/api/v2/code-workspaces/workspace-1/files")
                        .queryParam("path", "src/new.py")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"print('new')\",\"expectedWorkspaceRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceRevision").value(3));
        mvc.perform(post("/api/v2/code-workspaces/workspace-1/files/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourcePath":"src/new.py","targetPath":"src/main.py",
                                 "expectedWorkspaceRevision":3,"expectedContentHash":"%s"}
                                """.formatted(descriptor.contentHash())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceRevision").value(4));
        mvc.perform(delete("/api/v2/code-workspaces/workspace-1/files")
                        .queryParam("path", "src/main.py")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkspaceRevision":4,"expectedContentHash":"%s"}
                                """.formatted(descriptor.contentHash())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value("workspace-1"))
                .andExpect(jsonPath("$.workspaceRevision").value(5));
    }

    @Test
    void failedValidationIs422AndSuccessfulPublishIs201() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        CodeValidationService validation = mock(CodeValidationService.class);
        CodeWorkspacePublishService publish = mock(CodeWorkspacePublishService.class);
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());
        when(validation.validateWorkspace("workspace-1", 2L)).thenReturn(
                new CodeValidationResult(
                        "policy-v1", "0".repeat(64), "FAILED", "INVALID_ENTRY_SCRIPT",
                        "Code artifact validation failed", 1
                )
        );
        CodeVersion version = new CodeVersion();
        version.setId("version-1");
        version.setAssetId("asset-1");
        version.setVersion("v1");
        version.setStatus("READY");
        version.setValidationStatus("PASSED");
        version.setApprovalStatus("PENDING");
        version.setArtifactSha256("a".repeat(64));
        version.setCreatedAt(Instant.EPOCH);
        when(publish.publish("workspace-1", 2L, "v1")).thenReturn(version);
        MockMvc mvc = mvc(assetService, overlay, validation, publish);

        mvc.perform(post("/api/v2/code-workspaces/workspace-1/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceRevision\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.reasonCode").value("INVALID_ENTRY_SCRIPT"));
        mvc.perform(post("/api/v2/code-workspaces/workspace-1/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceRevision\":2,\"version\":\"v1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("version-1"))
                .andExpect(jsonPath("$.storagePath").doesNotExist());
    }

    @Test
    void workspaceValidationStorageFailureIs503() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        CodeValidationService validation = mock(CodeValidationService.class);
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());
        when(validation.validateWorkspace("workspace-1", 2L)).thenReturn(
                new CodeValidationResult(
                        "policy-v1", "0".repeat(64), "FAILED", "STORAGE_READ_FAILED",
                        "Code artifact could not be read", 0
                )
        );

        mvc(assetService, overlay, validation, mock(CodeWorkspacePublishService.class))
                .perform(post("/api/v2/code-workspaces/workspace-1/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceRevision\":2}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details.reasonCode").value("STORAGE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("could not be read")
                )));
    }

    @Test
    void ownershipPrecheckStopsOverlayBeforePathOrRevisionDetails() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        when(assetService.requireOwnedWorkspace("hidden"))
                .thenThrow(new com.tss.platform.service.CodeAssetAccessException());

        mvc(assetService, overlay).perform(
                        get("/api/v2/code-workspaces/hidden/files/content")
                                .queryParam("path", "../secret.py"))
                .andExpect(status().isNotFound());
        verify(overlay, never()).content(any(), any());
    }

    @Test
    void abandonChecksOwnershipBeforeBodyAndReturnsClosedWorkspace() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        when(assetService.requireOwnedWorkspace("hidden"))
                .thenThrow(new com.tss.platform.service.CodeAssetAccessException());
        MockMvc hiddenMvc = mvc(assetService, overlay);

        hiddenMvc.perform(post("/api/v2/code-workspaces/hidden/abandon")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        V2CodeWorkspaceDto closed = new V2CodeWorkspaceDto(
                "workspace-1", "asset-1", null, null, "ABANDONED", 3L,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, true
        );
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());
        when(assetService.abandonWorkspace("workspace-1", 2L)).thenReturn(closed);
        mvc(assetService, overlay).perform(post("/api/v2/code-workspaces/workspace-1/abandon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABANDONED"))
                .andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    void rejectsCrLfDownloadFilenameAndSanitizesPublishFailureAs503() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        CodeWorkspacePublishService publish = mock(CodeWorkspacePublishService.class);
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        when(overlay.download("workspace-1", "evil.txt"))
                .thenReturn(new CodeWorkspaceDownload(
                        descriptor("evil\r\nX-Injected.txt", bytes, true), bytes
                ));
        when(publish.publish("workspace-1", 2L, "v1"))
                .thenThrow(new com.tss.platform.service.CodeWorkspacePublishException());
        MockMvc mvc = mvc(
                assetService, overlay, mock(CodeValidationService.class), publish
        );

        mvc.perform(get("/api/v2/code-workspaces/workspace-1/files/download")
                        .queryParam("path", "evil.txt"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().doesNotExist("X-Injected"))
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("INVALID_DOWNLOAD_FILENAME"));
        mvc.perform(post("/api/v2/code-workspaces/workspace-1/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceRevision\":2,\"version\":\"v1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details.reasonCode").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorMessage")
                        .value("代码制品存储暂时不可用"));
    }

    @Test
    void missingWorkspaceRevisionUsesStable400ReasonCodeOnly() throws Exception {
        V2CodeAssetService assetService = mock(V2CodeAssetService.class);
        CodeWorkspaceOverlayService overlay = mock(CodeWorkspaceOverlayService.class);
        when(assetService.requireOwnedWorkspace("workspace-1")).thenReturn(workspace());

        mvc(assetService, overlay).perform(put(
                        "/api/v2/code-workspaces/workspace-1/files")
                        .queryParam("path", "src/main.py")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"print('x')\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("EXPECTED_WORKSPACE_REVISION_REQUIRED"))
                .andExpect(jsonPath("$.details.reason").doesNotExist());
        verify(overlay, never()).upsert(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    private static MockMvc mvc(
            V2CodeAssetService assetService,
            CodeWorkspaceOverlayService overlay
    ) {
        return mvc(
                assetService,
                overlay,
                mock(CodeValidationService.class),
                mock(CodeWorkspacePublishService.class)
        );
    }

    private static MockMvc mvc(
            V2CodeAssetService assetService,
            CodeWorkspaceOverlayService overlay,
            CodeValidationService validationService,
            CodeWorkspacePublishService publishService
    ) {
        return MockMvcBuilders.standaloneSetup(new V2CodeWorkspaceController(
                        assetService,
                        overlay,
                        validationService,
                        publishService
                ))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();
    }

    private static V2CodeWorkspaceDto workspace() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        return new V2CodeWorkspaceDto(
                "workspace-1",
                "asset-1",
                null,
                null,
                "OPEN",
                2L,
                now,
                now,
                null,
                false
        );
    }

    private static CodeFileDescriptor descriptor(
            String path,
            byte[] bytes,
            boolean editable
    ) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String extension = name.substring(name.lastIndexOf('.'));
        String language = ".py".equals(extension) ? "python" : "plaintext";
        String contentType = ".py".equals(extension) ? "text/x-python" : "text/plain";
        return new CodeFileDescriptor(
                path,
                name,
                "FILE",
                extension,
                language,
                contentType,
                bytes.length,
                editable,
                editable,
                true,
                editable ? null : "FILE_TOO_LARGE",
                new com.tss.platform.service.CodeFilePolicy().sha256(bytes)
        );
    }
}
