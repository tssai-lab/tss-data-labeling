package com.tss.platform.api;

import com.tss.platform.controller.v2.CodeReviewAdminAuthorizationInterceptor;
import com.tss.platform.controller.v2.V2AdminCodeAssetController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2AdminCodeAssetDto;
import com.tss.platform.dto.v2.V2AdminCodeAssetPage;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.V2AdminCodeAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2AdminCodeAssetControllerTest {

    private V2AdminCodeAssetService service;
    private AuthContext authContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(V2AdminCodeAssetService.class);
        authContext = mock(AuthContext.class);
        when(authContext.isAdmin()).thenReturn(true);
        mvc = MockMvcBuilders
                .standaloneSetup(new V2AdminCodeAssetController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .addInterceptors(new CodeReviewAdminAuthorizationInterceptor(authContext))
                .build();
    }

    @Test
    void exposesPagedCrossOwnerAssetsWithoutStorageDetails() throws Exception {
        V2AdminCodeAssetDto item = asset();
        when(service.list(
                eq(7), eq("trainer"), eq("CUSTOM_PYTHON"),
                eq(0), eq(20), eq("UPDATED_AT"), eq("DESC")
        )).thenReturn(new V2AdminCodeAssetPage(
                List.of(item), 0, 20, 1, 1
        ));

        mvc.perform(get("/api/v2/admin/code-assets")
                        .param("ownerUserId", "7")
                        .param("keyword", "trainer")
                        .param("trainingProfile", "CUSTOM_PYTHON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ownerUserId").value(7))
                .andExpect(jsonPath("$.items[0].assetRevision").value(4))
                .andExpect(jsonPath("$.items[0].storagePath").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void exposesPatchAndSoftDeleteWithExistingRevisionContract() throws Exception {
        when(service.patch(eq("asset-1"), any())).thenReturn(asset());

        mvc.perform(patch("/api/v2/admin/code-assets/asset-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetRevision\":4,\"name\":\"Managed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(7));

        mvc.perform(delete("/api/v2/admin/code-assets/asset-1")
                        .param("expectedAssetRevision", "4"))
                .andExpect(status().isNoContent());

        verify(service).delete("asset-1", 4L);
    }

    @Test
    void nonAdministratorIsForbiddenBeforeMalformedBinding() throws Exception {
        when(authContext.isAdmin()).thenReturn(false);

        mvc.perform(get("/api/v2/admin/code-assets")
                        .param("page", "not-a-number"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CODE_APPROVAL_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    private static V2AdminCodeAssetDto asset() {
        return new V2AdminCodeAssetDto(
                "asset-1",
                "Trainer",
                7,
                "CUSTOM_PYTHON",
                "training",
                "python3.11",
                "src/train.py",
                "CUSTOM",
                null,
                4L,
                Instant.EPOCH,
                Instant.EPOCH,
                false
        );
    }
}
