package com.tss.platform.service;

import com.tss.platform.controller.v2.V2DatasetPreviewController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2DatasetPreviewDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2DatasetPreviewControllerTest {

    @Test
    void previewRouteReturnsDescriptorFromV2Service() throws Exception {
        V2DatasetPreviewDescriptorService service =
                mock(V2DatasetPreviewDescriptorService.class);
        V2DatasetPreviewDescriptor descriptor = new V2DatasetPreviewDescriptor();
        descriptor.setDatasetVersionId("version-1");
        descriptor.setMode("SAMPLE_GALLERY");
        descriptor.setCapabilities(List.of("LIST_SAMPLES"));
        descriptor.setLinks(Map.of(
                "items", "/api/dataset-versions/version-1/samples"
        ));
        when(service.describe("version-1")).thenReturn(descriptor);

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new V2DatasetPreviewController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(get("/api/v2/dataset-versions/version-1/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersionId").value("version-1"))
                .andExpect(jsonPath("$.mode").value("SAMPLE_GALLERY"))
                .andExpect(jsonPath("$.capabilities[0]").value("LIST_SAMPLES"))
                .andExpect(jsonPath("$.links.items").value(
                        "/api/dataset-versions/version-1/samples"
                ));
    }
}
