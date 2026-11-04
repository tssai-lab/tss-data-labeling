package com.tss.platform.service;

import com.tss.platform.controller.DatasetPreviewController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetPreviewV1CompatibilityTest {

    @Test
    void typedPreviewAccessFailureKeepsLegacyImageEndpointBadRequestContract() {
        DatasetPreviewService previewService = mock(DatasetPreviewService.class);
        when(previewService.openImage("version-1", "images/a.png"))
                .thenThrow(new DatasetPreviewAccessException(
                        DatasetPreviewAccessException.Reason.NOT_PREVIEWABLE,
                        "dataset version status must be READY or DEPRECATED for preview"
                ));

        ResponseEntity<?> response = new DatasetPreviewController(previewService)
                .image("version-1", "images/a.png");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
