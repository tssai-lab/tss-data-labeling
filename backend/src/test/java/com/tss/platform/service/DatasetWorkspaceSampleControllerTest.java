package com.tss.platform.controller;

import com.tss.platform.dto.DatasetSampleDataDto;
import com.tss.platform.dto.DatasetSampleDetailDto;
import com.tss.platform.dto.DatasetWorkspaceSampleListItemDto;
import com.tss.platform.dto.DatasetWorkspaceSampleMutationDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.service.DatasetWorkspaceSampleMutationService;
import com.tss.platform.service.DatasetWorkspaceSampleService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasetWorkspaceSampleControllerTest {

    @Test
    void exposesDraftWorkspaceSampleReadEndpointsWithSafeResponses() throws Exception {
        DatasetWorkspaceSampleService service = mock(DatasetWorkspaceSampleService.class);
        DatasetWorkspaceSampleMutationService mutationService =
                mock(DatasetWorkspaceSampleMutationService.class);
        DatasetWorkspaceSampleController controller =
                new DatasetWorkspaceSampleController(service, mutationService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        DatasetWorkspaceSampleListItemDto item =
                new DatasetWorkspaceSampleListItemDto();
        item.setSampleId("sample-1");
        item.setDatasetVersionId("draft-1");
        item.setDeleted(false);
        PageResponse<DatasetWorkspaceSampleListItemDto> page = new PageResponse<>();
        page.setData(List.of(item));
        page.setPage(1);
        page.setPageSize(20);
        page.setTotal(1);
        page.setTotalPages(1);
        when(service.listSamples("draft-1", 1, 20, null)).thenReturn(page);

        DatasetSampleDataDto data = new DatasetSampleDataDto();
        data.setSampleDataId("data-1");
        data.setContentType("image/png");
        DatasetSampleDetailDto detail = new DatasetSampleDetailDto();
        detail.setSampleId("sample-1");
        detail.setDatasetVersionId("draft-1");
        detail.setData(List.of(data));
        detail.setAnnotations(List.of());
        when(service.getSample("sample-1")).thenReturn(detail);
        when(service.listSampleData("sample-1")).thenReturn(List.of(data));

        mvc.perform(get("/api/dataset-versions/draft-1/workspace/samples")
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data[0].sampleId").value("sample-1"))
                .andExpect(jsonPath("$.data.data[0].deleted").value(false))
                .andExpect(jsonPath("$.data.pageSize").value(20));

        String detailJson = mvc.perform(get("/api/dataset-samples/sample-1/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].sampleDataId").value("data-1"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        for (String forbidden : List.of(
                "storagePath",
                "bucket",
                "originalPath",
                "zipEntryOffset",
                "zipDataOffset",
                "compressedSize",
                "crc32",
                "packageId",
                "objectName",
                "previewUrl",
                "downloadUrl"
        )) {
            assertFalse(detailJson.contains(forbidden));
        }

        mvc.perform(get("/api/dataset-samples/sample-1/workspace/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sampleDataId").value("data-1"));
    }

    @Test
    void forwardsIncludeDeletedOnlyToWorkspaceListing() throws Exception {
        DatasetWorkspaceSampleService queryService =
                mock(DatasetWorkspaceSampleService.class);
        DatasetWorkspaceSampleMutationService mutationService =
                mock(DatasetWorkspaceSampleMutationService.class);
        DatasetWorkspaceSampleListItemDto item =
                new DatasetWorkspaceSampleListItemDto();
        item.setSampleId("deleted-sample");
        item.setDatasetVersionId("draft-1");
        item.setDeleted(true);
        PageResponse<DatasetWorkspaceSampleListItemDto> page = new PageResponse<>();
        page.setData(List.of(item));
        page.setPage(1);
        page.setPageSize(20);
        page.setTotal(1);
        page.setTotalPages(1);
        when(queryService.listSamples("draft-1", 1, 20, true)).thenReturn(page);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new DatasetWorkspaceSampleController(queryService, mutationService)
        ).build();

        mvc.perform(get("/api/dataset-versions/draft-1/workspace/samples")
                        .param("page", "1")
                        .param("pageSize", "20")
                        .param("includeDeleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data[0].sampleId")
                        .value("deleted-sample"))
                .andExpect(jsonPath("$.data.data[0].deleted").value(true));
    }

    @Test
    void exposesDraftWorkspaceDeleteAndRestoreEndpoints() throws Exception {
        DatasetWorkspaceSampleService queryService =
                mock(DatasetWorkspaceSampleService.class);
        DatasetWorkspaceSampleMutationService mutationService =
                mock(DatasetWorkspaceSampleMutationService.class);
        DatasetWorkspaceSampleMutationDto deleted =
                new DatasetWorkspaceSampleMutationDto();
        deleted.setSampleId("sample-1");
        deleted.setDatasetVersionId("draft-1");
        deleted.setDeleted(true);
        DatasetWorkspaceSampleMutationDto restored =
                new DatasetWorkspaceSampleMutationDto();
        restored.setSampleId("sample-1");
        restored.setDatasetVersionId("draft-1");
        restored.setDeleted(false);
        when(mutationService.deleteSample("sample-1")).thenReturn(deleted);
        when(mutationService.restoreSample("sample-1")).thenReturn(restored);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new DatasetWorkspaceSampleController(queryService, mutationService)
        ).build();

        mvc.perform(delete("/api/dataset-samples/sample-1/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sampleId").value("sample-1"))
                .andExpect(jsonPath("$.data.datasetVersionId").value("draft-1"))
                .andExpect(jsonPath("$.data.deleted").value(true));

        mvc.perform(post("/api/dataset-samples/sample-1/workspace/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deleted").value(false));
    }

    @Test
    void returnsExistingApiFailureEnvelopeForWorkspaceValidationErrors() throws Exception {
        DatasetWorkspaceSampleService service = mock(DatasetWorkspaceSampleService.class);
        DatasetWorkspaceSampleMutationService mutationService =
                mock(DatasetWorkspaceSampleMutationService.class);
        when(service.listSamples("ready-1", null, null, null))
                .thenThrow(new IllegalArgumentException(
                        "dataset workspace version not found or no permission"
                ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(
                        new DatasetWorkspaceSampleController(service, mutationService)
                )
                .build();

        mvc.perform(get("/api/dataset-versions/ready-1/workspace/samples")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMessage")
                        .value("dataset workspace version not found or no permission"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
