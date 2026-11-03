package com.tss.platform.controller;

import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadProgressDto;
import com.tss.platform.service.DatasetUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasetVersionPackageUploadControllerTest {

    @Test
    void exposesAppendInitAndCompleteRoutes() throws Exception {
        DatasetUploadService service = mock(DatasetUploadService.class);
        DatasetUploadProgressDto progress = new DatasetUploadProgressDto();
        progress.setUploadId("append-upload-1");
        progress.setStatus("UPLOADING");
        when(service.initAppendPackage(
                org.mockito.ArgumentMatchers.eq("draft-1"),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(progress);
        when(service.completeAppendPackage(
                org.mockito.ArgumentMatchers.eq("draft-1"),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Map.of(
                "draftVersionId", "draft-1",
                "packageId", "package-1",
                "packageRole", "APPEND",
                "importJobId", "ijob-1",
                "versionStatus", "DRAFT",
                "importStatus", "PENDING"
        ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new DatasetVersionPackageUploadController(service))
                .build();

        mvc.perform(post("/api/dataset-versions/draft-1/packages/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileName":"append.zip",
                                  "fileSize":1024,
                                  "sampleGrouping":"MANIFEST"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploadId").value("append-upload-1"));

        mvc.perform(post("/api/dataset-versions/draft-1/packages/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"append-upload-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packageRole").value("APPEND"))
                .andExpect(jsonPath("$.data.versionStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.importStatus").value("PENDING"));
    }

    @Test
    void returnsFailureEnvelopeForInvalidDraft() throws Exception {
        DatasetUploadService service = mock(DatasetUploadService.class);
        when(service.initAppendPackage(
                org.mockito.ArgumentMatchers.eq("ready-1"),
                org.mockito.ArgumentMatchers.any(DatasetPackageAppendInitRequest.class)
        )).thenThrow(new IllegalArgumentException(
                "dataset workspace version not found or no permission"
        ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new DatasetVersionPackageUploadController(service))
                .build();

        mvc.perform(post("/api/dataset-versions/ready-1/packages/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileName":"append.zip",
                                  "fileSize":1024,
                                  "sampleGrouping":"MANIFEST"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMessage")
                        .value("dataset workspace version not found or no permission"));
    }
}
