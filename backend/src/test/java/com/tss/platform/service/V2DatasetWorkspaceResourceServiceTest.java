package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.v2.V2DatasetContentUpdateRequest;
import com.tss.platform.dto.v2.V2DatasetWorkspaceMutationRequest;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2DatasetWorkspaceResourceServiceTest {

    @Test
    void appliesRfc7396MergePatchAndReturnsTheNewRevision() throws Exception {
        Fixture fixture = new Fixture();
        DatasetSample sample = fixture.sample();
        sample.setTags(new LinkedHashMap<>(Map.of(
                "keep", 1,
                "remove", 2,
                "nested", new LinkedHashMap<>(Map.of("a", 1, "b", 2))
        )));
        when(fixture.sampleRepo.findByIdForUpdate("sample-1"))
                .thenReturn(Optional.of(sample));
        when(fixture.sampleRepo.save(any(DatasetSample.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = fixture.service.patchSample(
                "workspace-1",
                "sample-1",
                fixture.mapper.readTree("""
                        {
                          "expectedWorkspaceRevision": 3,
                          "tags": {
                            "remove": null,
                            "nested": {"b": null, "c": 3}
                          }
                        }
                        """)
        );

        assertEquals(4L, result.workspaceRevision());
        Map<String, Object> tags = result.resource().tags();
        assertEquals(1, tags.get("keep"));
        assertFalse(tags.containsKey("remove"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) tags.get("nested");
        assertEquals(1, nested.get("a"));
        assertEquals(3, nested.get("c"));
        assertFalse(nested.containsKey("b"));
    }

    @Test
    void rejectsServerManagedAndImmutablePatchFields() throws Exception {
        Fixture fixture = new Fixture();

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.patchSample(
                        "workspace-1",
                        "sample-1",
                        fixture.mapper.readTree("""
                                {
                                  "expectedWorkspaceRevision": 3,
                                  "externalId": "changed",
                                  "sampleIndex": 99
                                }
                                """)
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("PATCH_FIELD_NOT_ALLOWED", error.getErrorCode());
    }

    @Test
    void refusesToDeleteDataReferencedByAnActiveAnnotation() {
        Fixture fixture = new Fixture();
        DatasetSample sample = fixture.sample();
        DatasetSampleData data = new DatasetSampleData();
        data.setId("data-1");
        data.setDatasetVersionId("workspace-1");
        data.setSampleId("sample-1");
        data.setDeleted(false);
        when(fixture.sampleRepo.findByIdAndDatasetVersionId(
                "sample-1",
                "workspace-1"
        )).thenReturn(Optional.of(sample));
        when(fixture.dataRepo.findByIdAndDatasetVersionIdForUpdate(
                "data-1",
                "workspace-1"
        )).thenReturn(Optional.of(data));
        when(fixture.annotationRepo
                .countByDatasetVersionIdAndSampleDataIdAndDeletedFalse(
                        "workspace-1",
                        "data-1"
                )).thenReturn(1L);

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.deleteData(
                        "workspace-1",
                        "sample-1",
                        "data-1",
                        new V2DatasetWorkspaceMutationRequest(3L)
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("RESOURCE_IN_USE", error.getErrorCode());
        assertTrue(error.getDetails().containsValue("data-1"));
    }

    @Test
    void replacingDataContentSwitchesToRawPackageAndReleasesOldOverlay() {
        Fixture fixture = new Fixture();
        DatasetSample sample = fixture.sample();
        DatasetSampleData data = new DatasetSampleData();
        data.setId("data-1");
        data.setDatasetVersionId("workspace-1");
        data.setSampleId("sample-1");
        data.setPackageId("old-overlay");
        data.setFileName("labels.json");
        data.setFormat("json");
        data.setContentType("application/json");
        data.setDeleted(false);
        DatasetPackage replacement = new DatasetPackage();
        replacement.setId("new-overlay");
        when(fixture.sampleRepo.findByIdForUpdate("sample-1"))
                .thenReturn(Optional.of(sample));
        when(fixture.dataRepo.findByIdAndDatasetVersionIdForUpdate(
                "data-1",
                "workspace-1"
        )).thenReturn(Optional.of(data));
        when(fixture.rawStorageService.storeText(
                eq(fixture.asset),
                eq(fixture.workspace),
                any(DatasetWorkspaceTextFilePolicy.ValidatedText.class)
        )).thenReturn(replacement);
        when(fixture.dataRepo.saveAndFlush(data)).thenReturn(data);

        var result = fixture.service.replaceDataContent(
                "workspace-1",
                "sample-1",
                "data-1",
                new V2DatasetContentUpdateRequest(
                        "{\"label\":1}",
                        null,
                        null,
                        null,
                        3L
                )
        );

        assertEquals("new-overlay", data.getPackageId());
        assertEquals(4L, result.workspaceRevision());
        verify(fixture.rawStorageService).releaseIfUnreferenced(
                fixture.workspace,
                "old-overlay",
                7
        );
    }

    private static final class Fixture {
        private final ObjectMapper mapper = new ObjectMapper();
        private final DatasetWorkspaceCommandService commandService =
                mock(DatasetWorkspaceCommandService.class);
        private final DatasetWorkspaceRawStorageService rawStorageService =
                mock(DatasetWorkspaceRawStorageService.class);
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final DatasetSampleRepository sampleRepo =
                mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final DatasetVersion workspace = workspace();
        private final DatasetAsset asset = asset();
        private final V2DatasetWorkspaceResourceService service =
                new V2DatasetWorkspaceResourceService(
                        commandService,
                        new DatasetWorkspaceTextFilePolicy(mapper),
                        rawStorageService,
                        auditService,
                        sampleRepo,
                        dataRepo,
                        annotationRepo,
                        mapper
                );

        private Fixture() {
            var access = new DatasetWorkspaceCommandService.WorkspaceAccess(
                    asset,
                    workspace
            );
            when(commandService.lockForMutation("workspace-1", 3L))
                    .thenReturn(access);
            when(commandService.incrementRevision(workspace)).thenReturn(4L);
            when(dataRepo.findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
                    "sample-1",
                    "workspace-1"
            )).thenReturn(List.of());
            when(annotationRepo
                    .findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(
                            "sample-1",
                            "workspace-1"
                    )).thenReturn(List.of());
        }

        private DatasetSample sample() {
            DatasetSample sample = new DatasetSample();
            sample.setId("sample-1");
            sample.setDatasetVersionId("workspace-1");
            sample.setExternalId("scene-1");
            sample.setSampleIndex(0);
            sample.setDeleted(false);
            return sample;
        }

        private static DatasetVersion workspace() {
            DatasetVersion workspace = new DatasetVersion();
            workspace.setId("workspace-1");
            workspace.setAssetId("asset-1");
            workspace.setStatus("DRAFT");
            workspace.setWorkspaceRevision(3L);
            return workspace;
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setOwnerUserId(7);
            return asset;
        }
    }
}
