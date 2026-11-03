package com.tss.platform.controller;

import com.tss.platform.dto.DatasetWorkspacePublishDto;
import com.tss.platform.service.DatasetWorkspacePublishService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasetWorkspacePublishControllerTest {

    @Test
    void publishesDraftThroughDedicatedEndpoint() throws Exception {
        DatasetWorkspacePublishService service =
                mock(DatasetWorkspacePublishService.class);
        DatasetWorkspacePublishDto dto = new DatasetWorkspacePublishDto();
        dto.setDatasetVersionId("draft-3");
        dto.setDatasetAssetId("asset-1");
        dto.setParentVersionId("ready-2");
        dto.setVersionNo(3);
        dto.setStatus("READY");
        dto.setCurrentVersionId("draft-3");
        dto.setPublishedAt(Instant.parse("2026-06-13T00:00:00Z"));
        when(service.publish("draft-3")).thenReturn(dto);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new DatasetWorkspacePublishController(service)
        ).build();

        mvc.perform(post("/api/dataset-versions/draft-3/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.datasetVersionId").value("draft-3"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.currentVersionId").value("draft-3"))
                .andExpect(jsonPath("$.data.storagePath").doesNotExist());
    }

    @Test
    void returnsExistingFailureEnvelopeForInvalidPublish() throws Exception {
        DatasetWorkspacePublishService service =
                mock(DatasetWorkspacePublishService.class);
        when(service.publish("ready-2")).thenThrow(
                new IllegalArgumentException("dataset version must be DRAFT")
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new DatasetWorkspacePublishController(service)
        ).build();

        mvc.perform(post("/api/dataset-versions/ready-2/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMessage")
                        .value("dataset version must be DRAFT"));
    }
}
