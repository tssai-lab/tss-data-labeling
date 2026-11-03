package com.tss.platform.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DatasetUploadCompleteResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void legacyAndManifestCompleteResponseKeepsExistingJsonFields() throws Exception {
        DatasetUploadCompleteResponse response = new DatasetUploadCompleteResponse();
        response.setUploadId("upload-1");
        response.setId("version-1");
        response.setAssetId("asset-1");
        response.setName("dataset");
        response.setVersion("v1");
        response.setVersionNo(1);
        response.setVersionLabel("v1");
        response.setDescription("description");
        response.setChangeLog("change");
        response.setParentVersionId("parent-1");
        response.setType("MULTIMODAL");
        response.setCvTaskType(null);
        response.setAnnotationFormat("COCO");
        response.setRemark("remark");
        response.setFileName("dataset.zip");
        response.setSizeBytes(1024L);
        response.setStatus("COMPLETED");
        response.setUploadStatus("COMPLETED");
        response.setDatasetVersionId("version-1");
        response.setVersionStatus("DRAFT");
        response.setImportJobId("job-1");
        response.setStrictManifest(true);
        response.setImportStatus("PENDING");
        response.setOwnerUserId(7);
        response.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        response.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));

        Map<String, Object> json = toMap(response);

        assertEquals(Set.of(
                "uploadId",
                "id",
                "assetId",
                "name",
                "version",
                "versionNo",
                "versionLabel",
                "description",
                "changeLog",
                "parentVersionId",
                "type",
                "cvTaskType",
                "annotationFormat",
                "remark",
                "fileName",
                "sizeBytes",
                "status",
                "uploadStatus",
                "datasetVersionId",
                "versionStatus",
                "importJobId",
                "strictManifest",
                "importStatus",
                "ownerUserId",
                "createdAt",
                "updatedAt"
        ), json.keySet());
        assertFalse(json.containsKey("storagePath"));
    }

    @Test
    void appendPackageCompleteResponseKeepsExistingJsonFields() throws Exception {
        DatasetAppendPackageCompleteResponse response = new DatasetAppendPackageCompleteResponse();
        response.setUploadId("upload-1");
        response.setDraftVersionId("draft-1");
        response.setDatasetVersionId("draft-1");
        response.setPackageId("package-1");
        response.setPackageRole("APPEND");
        response.setPackageOrder(3);
        response.setImportJobId("job-1");
        response.setStrictManifest(false);
        response.setUploadStatus("COMPLETED");
        response.setVersionStatus("DRAFT");
        response.setImportStatus("PENDING");

        Map<String, Object> json = toMap(response);

        assertEquals(Set.of(
                "uploadId",
                "draftVersionId",
                "datasetVersionId",
                "packageId",
                "packageRole",
                "packageOrder",
                "importJobId",
                "strictManifest",
                "uploadStatus",
                "versionStatus",
                "importStatus"
        ), json.keySet());
        assertFalse(json.containsKey("storagePath"));
    }

    @Test
    void cvFolderUploadResponseKeepsExistingJsonFields() throws Exception {
        DatasetCvFolderUploadResponse response = new DatasetCvFolderUploadResponse();
        response.setUploadId(null);
        response.setId("version-1");
        response.setAssetId("asset-1");
        response.setName("images");
        response.setVersion("v1");
        response.setVersionNo(1);
        response.setVersionLabel("v1");
        response.setDescription("description");
        response.setChangeLog("change");
        response.setParentVersionId(null);
        response.setType("CV");
        response.setCvTaskType("IMAGE_CLASSIFICATION");
        response.setAnnotationFormat("NONE");
        response.setRemark("remark");
        response.setFileName("images-v1-folder.zip");
        response.setSizeBytes(2048L);
        response.setStatus("COMPLETED");
        response.setOwnerUserId(7);
        response.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        response.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));

        Map<String, Object> json = toMap(response);

        assertEquals(Set.of(
                "uploadId",
                "id",
                "assetId",
                "name",
                "version",
                "versionNo",
                "versionLabel",
                "description",
                "changeLog",
                "parentVersionId",
                "type",
                "cvTaskType",
                "annotationFormat",
                "remark",
                "fileName",
                "sizeBytes",
                "status",
                "ownerUserId",
                "createdAt",
                "updatedAt"
        ), json.keySet());
        assertFalse(json.containsKey("storagePath"));
        assertFalse(json.containsKey("importJobId"));
    }

    private Map<String, Object> toMap(Object response) throws Exception {
        return objectMapper.readValue(
                objectMapper.writeValueAsString(response),
                new TypeReference<>() {
                }
        );
    }
}
