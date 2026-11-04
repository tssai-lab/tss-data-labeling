package com.tss.platform.service;

import com.tss.platform.controller.v2.V2DatasetWorkspaceController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2DatasetPublishResult;
import com.tss.platform.dto.v2.V2DatasetVersionSummary;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2DatasetWorkspaceControllerTest {

    @Test
    void publishRouteDelegatesToWorkspaceService() throws Exception {
        V2DatasetWorkspaceService service =
                mock(V2DatasetWorkspaceService.class);
        V2DatasetPublishResult result = new V2DatasetPublishResult();
        result.setDatasetId("asset-1");
        result.setCurrentVersion(new V2DatasetVersionSummary(
                "version-2",
                "v2",
                2,
                "READY"
        ));
        result.setPublishedAt(Instant.parse("2026-07-04T00:00:00Z"));
        when(service.publish("draft-2", 7L)).thenReturn(result);

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new V2DatasetWorkspaceController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(post("/api/v2/dataset-workspaces/draft-2/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedWorkspaceRevision\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value("asset-1"))
                .andExpect(jsonPath("$.currentVersion.versionId")
                        .value("version-2"))
                .andExpect(jsonPath("$.currentVersion.versionLabel")
                        .value("v2"))
                .andExpect(jsonPath("$.currentVersion.status")
                        .value("READY"))
                .andExpect(jsonPath("$.status").doesNotExist());
    }
}
