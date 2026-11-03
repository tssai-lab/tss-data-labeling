package com.tss.platform.api;

import com.tss.platform.controller.v2.V2AdminCodeReviewController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.controller.v2.CodeReviewAdminAuthorizationInterceptor;
import com.tss.platform.dto.v2.V2AdminCodeReviewTask;
import com.tss.platform.dto.v2.V2AdminCodeReviewTaskDetail;
import com.tss.platform.dto.v2.V2AdminCodeReviewTaskPage;
import com.tss.platform.dto.v2.V2AdminCodeRiskAssessment;
import com.tss.platform.dto.v2.V2AdminCodeRiskFinding;
import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.dto.v2.V2CodeFileNode;
import com.tss.platform.service.CodeApprovalForbiddenException;
import com.tss.platform.service.CodeAssetAccessException;
import com.tss.platform.service.CodeContentTooLargeException;
import com.tss.platform.service.V2AdminCodeReviewService;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2AdminCodeReviewControllerTest {

    private V2AdminCodeReviewService service;
    private AuthContext authContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(V2AdminCodeReviewService.class);
        authContext = mock(AuthContext.class);
        when(authContext.isAdmin()).thenReturn(true);
        mvc = MockMvcBuilders
                .standaloneSetup(new V2AdminCodeReviewController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .addInterceptors(new CodeReviewAdminAuthorizationInterceptor(authContext))
                .build();
    }

    @Test
    void exposesPagedQueueWithPendingDefaultAndNoStorageDetails() throws Exception {
        V2AdminCodeReviewTask item = new V2AdminCodeReviewTask(
                "version-1", "asset-1", "asset", 7, "v1", "READY", "PENDING",
                "a".repeat(64), "PASSED", "validation-v1", "risk-1", "COMPLETED",
                "HIGH", "MANUAL_REVIEW", "risk-v1", 2, Instant.EPOCH
        );
        when(service.list(
                eq("PENDING"), eq("HIGH"), eq(7), eq("asset"),
                any(), any(), eq("VERSION"), eq("ASC"), eq(0), eq(20)
        )).thenReturn(new V2AdminCodeReviewTaskPage(
                List.of(item), 0, 20, 1, 1
        ));

        mvc.perform(get("/api/v2/admin/code-review-tasks")
                        .param("riskLevel", "HIGH")
                        .param("ownerUserId", "7")
                        .param("keyword", "asset")
                        .param("sortBy", "VERSION")
                        .param("sortDirection", "ASC")
                        .param("submittedFrom", "1970-01-01T00:00:00Z")
                        .param("submittedTo", "1970-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].versionId").value("version-1"))
                .andExpect(jsonPath("$.items[0].riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.items[0].storagePath").doesNotExist())
                .andExpect(jsonPath("$.items[0].downloadUrl").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(service).list(
                eq("PENDING"), eq("HIGH"), eq(7), eq("asset"),
                eq(Instant.EPOCH), eq(Instant.parse("1970-01-02T00:00:00Z")),
                eq("VERSION"), eq("ASC"), eq(0), eq(20)
        );
    }

    @Test
    void exposesDetailTreePreviewFindingsAndRescan() throws Exception {
        V2AdminCodeRiskAssessment assessment = assessment();
        when(service.detail("version-1")).thenReturn(new V2AdminCodeReviewTaskDetail(
                "version-1", "asset-1", "asset", 7, "v1", "READY", "PENDING",
                "training", "python:3.11", "src/train.py", "CUSTOM", "CUSTOM_PYTHON",
                "code.zip", 123L, "a".repeat(64), "PASSED", "validation-v1",
                Instant.EPOCH, assessment
        ));
        when(service.tree("version-1", "src")).thenReturn(List.of(
                new V2CodeFileNode(
                        "src/train.py", "train.py", "FILE", ".py", "python",
                        "text/x-python", 12, true, false, true, null
                )
        ));
        when(service.content("version-1", "src/train.py")).thenReturn(
                new V2CodeFileContent(
                        "src/train.py", "train.py", "FILE", ".py", "python",
                        "text/x-python", 12, true, false, true, null,
                        "print('ok')", "UTF-8", "b".repeat(64), null, true
                )
        );
        when(service.findings("version-1")).thenReturn(List.of(
                new V2AdminCodeRiskFinding(
                        "finding-1", "risk-1", "PY_PROCESS", "HIGH", "PROCESS",
                        "src/train.py", 2, 2, "Process invocation detected"
                )
        ));
        when(service.rescan("version-1")).thenReturn(assessment);

        mvc.perform(get("/api/v2/admin/code-review-tasks/version-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskAssessment.id").value("risk-1"))
                .andExpect(jsonPath("$.storagePath").doesNotExist())
                .andExpect(jsonPath("$.riskAssessment.errorMessage").doesNotExist());
        mvc.perform(get("/api/v2/admin/code-review-tasks/version-1/tree")
                        .param("prefix", "src"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].editable").value(false));
        mvc.perform(get("/api/v2/admin/code-review-tasks/version-1/files/content")
                        .param("path", "src/train.py"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.languageId").value("python"))
                .andExpect(jsonPath("$.readOnly").value(true));
        mvc.perform(get("/api/v2/admin/code-review-tasks/version-1/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description")
                        .value("Process invocation detected"))
                .andExpect(jsonPath("$[0].sourceSnippet").doesNotExist())
                .andExpect(jsonPath("$[0].secretValue").doesNotExist());
        mvc.perform(post("/api/v2/admin/code-review-tasks/version-1/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("risk-1"));
    }

    @Test
    void mapsForbiddenNotFoundAndPreviewLimitToStableStatuses() throws Exception {
        when(service.detail("forbidden")).thenThrow(new CodeApprovalForbiddenException());
        when(service.detail("missing")).thenThrow(new CodeAssetAccessException());
        when(service.content("version-1", "large.txt"))
                .thenThrow(new CodeContentTooLargeException());

        mvc.perform(get("/api/v2/admin/code-review-tasks/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CODE_APPROVAL_FORBIDDEN"));
        mvc.perform(get("/api/v2/admin/code-review-tasks/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CODE_ASSET_NOT_FOUND"));
        mvc.perform(get("/api/v2/admin/code-review-tasks/version-1/files/content")
                        .param("path", "large.txt"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.details.reasonCode").value("FILE_TOO_LARGE"));
    }

    @Test
    void adminRouteBadRequestUsesSanitizedCodeResourceDetails() throws Exception {
        mvc.perform(get("/api/v2/admin/code-review-tasks")
                        .param("page", "not-a-number")
                        .header("X-Trace-Id", "trace-admin"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "trace-admin"))
                .andExpect(jsonPath("$.details.reasonCode").value("INVALID_REQUEST"));
    }

    @Test
    void nonAdministratorGetsForbiddenBeforeMalformedParameterBinding() throws Exception {
        when(authContext.isAdmin()).thenReturn(false);

        mvc.perform(get("/api/v2/admin/code-review-tasks")
                        .param("page", "not-a-number")
                        .header("X-Trace-Id", "trace-denied"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Trace-Id", "trace-denied"))
                .andExpect(jsonPath("$.errorCode").value("CODE_APPROVAL_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    private static V2AdminCodeRiskAssessment assessment() {
        return new V2AdminCodeRiskAssessment(
                "risk-1", "version-1", "validation-1", "a".repeat(64),
                "risk-v1", "scanner-v1", "COMPLETED", "HIGH", "MANUAL_REVIEW",
                2, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH.plusSeconds(1)
        );
    }
}
