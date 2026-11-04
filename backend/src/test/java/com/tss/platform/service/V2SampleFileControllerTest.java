package com.tss.platform.service;

import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.controller.v2.V2SampleFileController;
import com.tss.platform.service.SampleFileService.SampleFileStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2SampleFileControllerTest {

    @Test
    void sampleDataPreviewRouteStreamsThroughV2Path() throws Exception {
        SampleFileService service = mock(SampleFileService.class);
        when(service.openDataPreview("data-1", null)).thenReturn(new SampleFileStream(
                new ByteArrayInputStream(new byte[] {1}),
                "front.jpg",
                "image/jpeg",
                1L
        ));

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new V2SampleFileController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(get("/api/v2/dataset-sample-data/data-1/preview"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        containsString("front.jpg")
                ));
    }

    @Test
    void unsatisfiedRangeReturnsRfcContentRangeHeader() throws Exception {
        SampleFileService service = mock(SampleFileService.class);
        when(service.openDataPreview("data-1", "bytes=2-"))
                .thenThrow(new SampleFileException(
                        HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                        "requested range is outside the file",
                        1L
                ));

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new V2SampleFileController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(get("/api/v2/dataset-sample-data/data-1/preview")
                        .header(HttpHeaders.RANGE, "bytes=2-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */1"))
                .andExpect(jsonPath("$.details.rangeTotal").value(1));
    }
}
