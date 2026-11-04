package com.tss.platform.api;

import com.tss.platform.controller.v2.CodeReviewAdminAuthorizationInterceptor;
import com.tss.platform.controller.v2.V2AdminCodeWorkspaceController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2CodeWorkspaceDto;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.CodeFileDescriptor;
import com.tss.platform.service.CodeValidationService;
import com.tss.platform.service.CodeWorkspaceContent;
import com.tss.platform.service.CodeWorkspaceOverlayService;
import com.tss.platform.service.CodeWorkspacePublishService;
import com.tss.platform.service.V2CodeAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2AdminCodeWorkspaceControllerTest {

    private V2CodeAssetService assetService;
    private CodeWorkspaceOverlayService overlayService;
    private CodeWorkspacePublishService publishService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        assetService = mock(V2CodeAssetService.class);
        overlayService = mock(CodeWorkspaceOverlayService.class);
        publishService = mock(CodeWorkspacePublishService.class);
        AuthContext authContext = mock(AuthContext.class);
        when(authContext.isAdmin()).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new V2AdminCodeWorkspaceController(
                        assetService,
                        overlayService,
                        mock(CodeValidationService.class),
                        publishService
                ))
                .setControllerAdvice(new V2ExceptionHandler())
                .addInterceptors(new CodeReviewAdminAuthorizationInterceptor(authContext))
                .build();
    }

    @Test
    void fileMutationUsesAdministratorPrecheckAndExistingRevisionContract()
            throws Exception {
        when(assetService.requireAdminWorkspace("workspace-1"))
                .thenReturn(workspace());
        CodeFileDescriptor descriptor = new CodeFileDescriptor(
                "src/train.py", "train.py", "FILE", ".py", "python",
                "text/x-python", 11, true, true, true, null, "a".repeat(64)
        );
        when(overlayService.upsert(
                eq("workspace-1"),
                eq("src/train.py"),
                any(byte[].class),
                eq(3L),
                isNull()
        )).thenReturn(new CodeWorkspaceContent(
                descriptor,
                "print('ok')",
                "UTF-8",
                "a".repeat(64),
                4L,
                false,
                "print('ok')".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));

        mvc.perform(put("/api/v2/admin/code-workspaces/workspace-1/files")
                        .param("path", "src/train.py")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content":"print('ok')",
                                  "expectedWorkspaceRevision":3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceRevision").value(4))
                .andExpect(jsonPath("$.editable").value(true));

        verify(assetService).requireAdminWorkspace("workspace-1");
    }

    @Test
    void publishKeepsVersionInOriginalAssetAndReturnsCreated() throws Exception {
        when(assetService.requireAdminWorkspace("workspace-1"))
                .thenReturn(workspace());
        CodeVersion version = new CodeVersion();
        version.setId("version-2");
        version.setAssetId("asset-1");
        version.setOwnerUserId(7);
        version.setVersion("v2");
        version.setStatus("READY");
        version.setApprovalStatus("PENDING");
        version.setValidationStatus("PASSED");
        version.setCreatedAt(Instant.EPOCH);
        version.setUpdatedAt(Instant.EPOCH);
        when(publishService.publish("workspace-1", 3L, "v2"))
                .thenReturn(version);

        mvc.perform(post("/api/v2/admin/code-workspaces/workspace-1/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedWorkspaceRevision":3,
                                  "version":"v2"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("version-2"))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.storagePath").doesNotExist());

        verify(assetService).requireAdminWorkspace("workspace-1");
        verify(publishService).publish("workspace-1", 3L, "v2");
    }

    private static V2CodeWorkspaceDto workspace() {
        return new V2CodeWorkspaceDto(
                "workspace-1",
                "asset-1",
                "version-1",
                null,
                "OPEN",
                3L,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                false
        );
    }
}
