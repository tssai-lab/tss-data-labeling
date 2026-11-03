package com.tss.platform.service;

import com.tss.platform.controller.v2.V2DatasetSampleController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.DatasetMultimodalExternalIdAnnotationDto;
import com.tss.platform.dto.DatasetMultimodalExternalIdDataDto;
import com.tss.platform.dto.DatasetMultimodalExternalIdSampleDto;
import com.tss.platform.dto.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2DatasetSampleControllerTest {

    @Test
    void multimodalRouteUsesV2FileLinks() throws Exception {
        SampleService service = mock(SampleService.class);
        DatasetMultimodalExternalIdDataDto data =
                new DatasetMultimodalExternalIdDataDto();
        data.setSampleDataId("data-1");
        data.setPreviewUrl("/api/dataset-sample-data/data-1/preview");
        data.setDownloadUrl("/api/dataset-sample-data/data-1/download");
        DatasetMultimodalExternalIdAnnotationDto annotation =
                new DatasetMultimodalExternalIdAnnotationDto();
        annotation.setAnnotationId("annotation-1");
        annotation.setDownloadUrl("/api/dataset-annotations/annotation-1/download");
        DatasetMultimodalExternalIdSampleDto sample =
                new DatasetMultimodalExternalIdSampleDto();
        sample.setSampleId("sample-1");
        sample.setData(List.of(data));
        sample.setAnnotations(List.of(annotation));
        PageResponse<DatasetMultimodalExternalIdSampleDto> page = new PageResponse<>();
        page.setData(List.of(sample));
        when(service.findMultimodalByExternalId(
                "scene-1",
                List.of("version-1"),
                1,
                20
        )).thenReturn(page);

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new V2DatasetSampleController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(get("/api/v2/dataset-samples/multimodal")
                        .param("externalId", "scene-1")
                        .param("datasetVersionIds", "version-1")
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].data[0].previewUrl")
                        .value("/api/v2/dataset-sample-data/data-1/preview"))
                .andExpect(jsonPath("$.data[0].data[0].downloadUrl")
                        .value("/api/v2/dataset-sample-data/data-1/download"))
                .andExpect(jsonPath("$.data[0].annotations[0].downloadUrl")
                        .value("/api/v2/dataset-annotations/annotation-1/download"));
    }

}
