package com.tss.platform.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ModelVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyStoragePathResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    @Test
    void dataAssetResponsesHideStoragePath() throws Exception {
        ModelUploadProgressDto modelProgress = new ModelUploadProgressDto();
        modelProgress.setUploadId("model-upload-1");
        modelProgress.setStoragePath("users/7/models/internal.zip");

        CodeUploadResultDto codeUpload = CodeUploadResultDto.builder()
                .codeAssetId("code-asset-1")
                .storagePath("users/7/codes/internal.zip")
                .build();

        ModelVersion modelVersion = new ModelVersion();
        modelVersion.setId("model-version-1");
        modelVersion.setStoragePath("users/7/models/version.zip");

        DatasetVersion datasetVersion = new DatasetVersion();
        datasetVersion.setId("dataset-version-1");
        datasetVersion.setStoragePath("users/7/datasets/version.zip");

        assertFalse(objectMapper.writeValueAsString(modelProgress).contains("storagePath"));
        assertFalse(objectMapper.writeValueAsString(codeUpload).contains("storagePath"));
        assertFalse(objectMapper.writeValueAsString(modelVersion).contains("storagePath"));
        assertFalse(objectMapper.writeValueAsString(datasetVersion).contains("storagePath"));
    }

    @Test
    void versionRequestsStillBindStoragePathForTamperValidation() throws Exception {
        ModelVersion modelVersion = objectMapper.readValue(
                "{\"storagePath\":\"users/7/models/tampered.zip\"}",
                ModelVersion.class
        );
        DatasetVersion datasetVersion = objectMapper.readValue(
                "{\"storagePath\":\"users/7/datasets/tampered.zip\"}",
                DatasetVersion.class
        );

        assertEquals("users/7/models/tampered.zip", modelVersion.getStoragePath());
        assertEquals("users/7/datasets/tampered.zip", datasetVersion.getStoragePath());
    }

    @Test
    void inferenceScriptContractRemainsOutOfScope() throws Exception {
        InferenceScriptVersionDto inference = new InferenceScriptVersionDto();
        inference.setStoragePath("users/7/inference/scripts/script.py");

        assertTrue(objectMapper.writeValueAsString(inference).contains("storagePath"));
    }
}
