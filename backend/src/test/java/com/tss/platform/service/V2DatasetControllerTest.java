package com.tss.platform.service;

import com.tss.platform.controller.v2.V2DatasetController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.dto.v2.V2DatasetListItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2DatasetControllerTest {

    @Test
    void listRouteDelegatesToV2CatalogService() throws Exception {
        V2DatasetCatalogService service = mock(V2DatasetCatalogService.class);
        V2DatasetListItem item = new V2DatasetListItem();
        item.setDatasetId("asset-1");
        item.setName("multimodal");
        item.setCurrentVersionFileCount(9L);
        item.setFileCount(9L);
        PageResponse<V2DatasetListItem> page = new PageResponse<>();
        page.setData(List.of(item));
        page.setPage(1);
        page.setPageSize(20);
        page.setTotal(1);
        page.setTotalPages(1);
        when(service.list(null, null, null, null, null)).thenReturn(page);

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new V2DatasetController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(get("/api/v2/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].datasetId").value("asset-1"))
                .andExpect(jsonPath("$.data[0].currentVersionFileCount").value(9))
                .andExpect(jsonPath("$.data[0].fileCount").value(9));
    }

    @Test
    void listRoutePassesServerSideArtifactSpecificationFilter() throws Exception {
        V2DatasetCatalogService service = mock(V2DatasetCatalogService.class);
        PageResponse<V2DatasetListItem> page = new PageResponse<>();
        page.setData(List.of());
        page.setPage(2);
        page.setPageSize(20);
        page.setTotal(0);
        page.setTotalPages(0);
        when(service.list(
                null,
                null,
                2,
                null,
                20,
                List.of("YOLO", "FOLDER_CLASSIFICATION")
        )).thenReturn(page);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new V2DatasetController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(get("/api/v2/datasets")
                        .param("page", "2")
                        .param("pageSize", "20")
                        .param("artifactSpecIds", "YOLO,FOLDER_CLASSIFICATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));

        verify(service).list(
                null,
                null,
                2,
                null,
                20,
                List.of("YOLO", "FOLDER_CLASSIFICATION")
        );
    }
}
