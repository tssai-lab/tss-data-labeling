package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.dto.v2.V2DatasetEditability;
import com.tss.platform.dto.v2.V2DatasetListItem;
import com.tss.platform.dto.v2.V2DatasetPublishBlocker;
import com.tss.platform.dto.v2.V2DatasetPublishReadiness;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2DatasetCatalogServiceTest {

    @Test
    void exposesPageReadyFieldsWithoutInternalStorageOrJobIds() throws Exception {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        DatasetVersion ready = fixture.version("ready-1", "READY", 1);
        asset.setCurrentVersionId(ready.getId());
        fixture.stub(List.of(asset), List.of(ready), List.of());
        when(fixture.fileCountService.countCurrentVersionFiles(asset, ready)).thenReturn(9L);

        PageResponse<V2DatasetListItem> page =
                fixture.service.list(null, null, null, null, null);

        V2DatasetListItem item = page.getData().get(0);
        assertEquals(asset.getId(), item.getDatasetId());
        assertEquals(ready.getId(), item.getCurrentVersion().getVersionId());
        assertEquals("v1", item.getCurrentVersion().getVersionLabel());
        assertEquals(1, item.getCurrentVersion().getVersionNo());
        assertEquals("READY", item.getCurrentVersion().getStatus());
        assertEquals("READY", item.getDisplayStatus());
        assertEquals(9L, item.getCurrentVersionFileCount());
        assertFalse(item.getHasDraft());
        assertNull(item.getWorkspaceId());
        assertNull(item.getPublishReadiness());
        assertTrue(item.getAvailableActions().contains("PREVIEW"));
        assertTrue(item.getAvailableActions().contains("CREATE_WORKSPACE"));
        assertFalse(item.getAvailableActions().contains("PUBLISH"));

        String json = new ObjectMapper().writeValueAsString(item);
        assertTrue(json.contains("\"currentVersionFileCount\":9"));
        assertTrue(json.contains("\"fileCount\":9"));
        assertFalse(json.contains("storagePath"));
        assertFalse(json.contains("ownerUserId"));
        assertFalse(json.contains("currentVersionId"));
        assertFalse(json.contains("importJobId"));
        assertFalse(json.contains("errorDetailsJson"));
    }

    @Test
    void failedImportTakesPriorityAndReturnsSanitizedUserError() {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        DatasetVersion ready = fixture.version("ready-1", "READY", 1);
        DatasetVersion draft = fixture.version("draft-2", "DRAFT", 2);
        asset.setCurrentVersionId(ready.getId());
        ImportJob job = fixture.job(draft.getId(), "FAILED", 0);
        job.setErrorCode("INVALID_MANIFEST");
        job.setErrorMessage("Manifest 内容无效，请检查后重试");
        job.setErrorDetailsJson(
                "{\"field\":\"samples[0].data[0].path\","
                        + "\"path\":\"missing.png\","
                        + "\"reason\":\"path not found in zip\"}"
        );
        fixture.stub(List.of(asset), List.of(ready, draft), List.of(job));

        V2DatasetListItem item = fixture.service
                .list(null, null, 1, null, 20)
                .getData()
                .get(0);

        assertEquals("IMPORT_FAILED", item.getDisplayStatus());
        assertTrue(item.getHasDraft());
        assertEquals(draft.getId(), item.getWorkspaceId());
        assertFalse(item.getPublishReadiness().canPublish());
        assertEquals("INVALID_MANIFEST", item.getUserError().getErrorCode());
        assertEquals(
                "samples[0].data[0].path",
                item.getUserError().getDetails().get("field")
        );
        assertEquals("missing.png", item.getUserError().getDetails().get("path"));
        assertTrue(item.getAvailableActions().contains("ADD_DATA"));
        assertFalse(item.getAvailableActions().contains("PUBLISH"));
    }

    @Test
    void partialImportTakesPriorityAndKeepsDraftNotPublishable() {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        DatasetVersion ready = fixture.version("ready-1", "READY", 1);
        DatasetVersion draft = fixture.version("draft-2", "DRAFT", 2);
        asset.setCurrentVersionId(ready.getId());
        ImportJob job = fixture.job(draft.getId(), "PARTIAL", 50);
        job.setImportedSamples(1);
        job.setTotalSamples(2);
        job.setErrorCode("PARTIAL_IMPORT_FAILED");
        job.setErrorMessage("部分样本导入失败，可增量重试");
        job.setErrorDetailsJson("{\"failedSamples\":1,\"totalSamples\":2}");
        fixture.stub(List.of(asset), List.of(ready, draft), List.of(job));

        V2DatasetListItem item = fixture.service
                .list(null, null, 1, null, 20)
                .getData()
                .get(0);

        assertEquals("IMPORT_PARTIAL", item.getDisplayStatus());
        assertFalse(item.getPublishReadiness().canPublish());
        assertFalse(item.getAvailableActions().contains("PUBLISH"));
        assertEquals("PARTIAL_IMPORT_FAILED", item.getUserError().getErrorCode());
        assertEquals(1, item.getUserError().getDetails().get("failedSamples"));
    }

    @Test
    void runningImportTakesPriorityOverEditing() {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        DatasetVersion draft = fixture.version("draft-1", "DRAFT", 1);
        ImportJob job = fixture.job(draft.getId(), "RUNNING", 45);
        fixture.stub(List.of(asset), List.of(draft), List.of(job));

        V2DatasetListItem item = fixture.service
                .list(null, null, null, null, null)
                .getData()
                .get(0);

        assertEquals("IMPORTING", item.getDisplayStatus());
        assertEquals(45, item.getImportProgress());
        assertFalse(item.getPublishReadiness().canPublish());
    }

    @Test
    void draftWithoutSamplesIsNotPublishable() {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        DatasetVersion draft = fixture.version("draft-1", "DRAFT", 1);
        fixture.stub(List.of(asset), List.of(draft), List.of());
        when(fixture.sampleRepo.countByDatasetVersionIdAndDeletedFalse(draft.getId()))
                .thenReturn(0L);

        V2DatasetListItem item = fixture.service
                .list(null, null, null, null, null)
                .getData()
                .get(0);

        assertFalse(item.getPublishReadiness().canPublish());
        assertFalse(item.getAvailableActions().contains("PUBLISH"));
    }

    @Test
    void singleModalDraftListExposesWorkspaceAddDataAction() {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        asset.setType("POINT_CLOUD");
        DatasetVersion draft = fixture.version("draft-1", "DRAFT", 1);
        fixture.stub(List.of(asset), List.of(draft), List.of());

        V2DatasetListItem item = fixture.service
                .list(null, null, null, null, null)
                .getData()
                .get(0);

        assertTrue(item.getHasDraft());
        assertEquals(draft.getId(), item.getWorkspaceId());
        assertTrue(item.getAvailableActions().contains("ADD_DATA"));
        verify(fixture.readinessService).evaluateCatalog(asset, draft);
        verify(fixture.readinessService, never()).evaluate(asset, draft);
    }

    @Test
    void olderFailedImportKeepsDraftNotPublishableWhenLatestImportSucceeds() {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        DatasetVersion draft = fixture.version("draft-1", "DRAFT", 1);
        ImportJob failed = fixture.job(draft.getId(), "FAILED", 0);
        failed.setId("job-failed");
        failed.setCreatedAt(Instant.parse("2026-01-10T00:00:00Z"));
        failed.setErrorCode("DUPLICATE_SAMPLE");
        failed.setErrorMessage("上传内容包含已存在的样本");
        failed.setErrorDetailsJson("{\"sampleName\":\"scene-1\"}");
        ImportJob succeeded = fixture.job(draft.getId(), "SUCCESS", 100);
        succeeded.setId("job-success");
        succeeded.setCreatedAt(Instant.parse("2026-01-11T00:00:00Z"));
        fixture.stub(List.of(asset), List.of(draft), List.of(failed, succeeded));

        V2DatasetListItem item = fixture.service
                .list(null, null, null, null, null)
                .getData()
                .get(0);

        assertFalse(item.getPublishReadiness().canPublish());
        assertEquals("IMPORT_FAILED", item.getDisplayStatus());
        assertEquals(0, item.getImportProgress());
        assertEquals("DUPLICATE_SAMPLE", item.getUserError().getErrorCode());
        assertEquals("scene-1", item.getUserError().getDetails().get("sampleName"));
        assertFalse(item.getAvailableActions().contains("PUBLISH"));
    }

    @Test
    void newerRunningImportTakesPriorityOverOlderFailedImport() {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        DatasetVersion draft = fixture.version("draft-1", "DRAFT", 1);
        ImportJob failed = fixture.job(draft.getId(), "FAILED", 0);
        failed.setId("job-failed");
        failed.setCreatedAt(Instant.parse("2026-01-10T00:00:00Z"));
        failed.setErrorCode("DUPLICATE_SAMPLE");
        ImportJob running = fixture.job(draft.getId(), "RUNNING", 45);
        running.setId("job-running");
        running.setCreatedAt(Instant.parse("2026-01-11T00:00:00Z"));
        fixture.stub(List.of(asset), List.of(draft), List.of(failed, running));

        V2DatasetListItem item = fixture.service
                .list(null, null, null, null, null)
                .getData()
                .get(0);

        assertEquals("IMPORTING", item.getDisplayStatus());
        assertEquals(45, item.getImportProgress());
        assertNull(item.getUserError());
        assertFalse(item.getPublishReadiness().canPublish());
    }

    @Test
    void selectorIgnoresSupersededAndPrioritizesActiveOrUnresolvedJobs() {
        ImportJob superseded = new ImportJob();
        superseded.setId("job-superseded");
        superseded.setStatus("SUPERSEDED");
        superseded.setCreatedAt(Instant.parse("2026-01-12T00:00:00Z"));
        ImportJob success = new ImportJob();
        success.setId("job-success");
        success.setStatus("SUCCESS");
        success.setCreatedAt(Instant.parse("2026-01-10T00:00:00Z"));
        ImportJob partial = new ImportJob();
        partial.setId("job-partial");
        partial.setStatus("PARTIAL");
        partial.setCreatedAt(Instant.parse("2026-01-11T00:00:00Z"));
        ImportJob running = new ImportJob();
        running.setId("job-running");
        running.setStatus("RUNNING");
        running.setCreatedAt(Instant.parse("2026-01-09T00:00:00Z"));

        assertEquals(
                "job-success",
                V2ImportJobStatusSelector.statusJobOf(List.of(superseded, success)).getId()
        );
        assertEquals(
                "job-partial",
                V2ImportJobStatusSelector.statusJobOf(List.of(superseded, success, partial)).getId()
        );
        assertEquals(
                "job-running",
                V2ImportJobStatusSelector.statusJobOf(
                        List.of(superseded, success, partial, running)
                ).getId()
        );
    }

    @Test
    void listItemSerializationDoesNotExposeInternalStorageFields() throws Exception {
        Fixture fixture = new Fixture();
        DatasetAsset asset = fixture.asset();
        DatasetVersion ready = fixture.version("ready-1", "READY", 1);
        ready.setStoragePath("users/7/datasets/asset-1/v1/data.zip");
        asset.setCurrentVersionId(ready.getId());
        fixture.stub(List.of(asset), List.of(ready), List.of());

        PageResponse<V2DatasetListItem> page =
                fixture.service.list("MULTIMODAL", null, 1, null, 20);

        String json = new ObjectMapper().writeValueAsString(page);
        assertFalse(json.contains("storagePath"));
        assertFalse(json.contains("ownerUserId"));
        assertFalse(json.contains("packageId"));
        assertFalse(json.contains("zipDataOffset"));
        assertFalse(json.contains("compressedSize"));
    }

    @Test
    void enrichesOnlyAssetsReturnedByDatabasePage() {
        Fixture fixture = new Fixture();
        DatasetAsset first = fixture.asset("asset-1", "first");
        DatasetAsset second = fixture.asset("asset-2", "second");
        DatasetVersion firstReady = fixture.version("ready-1", "asset-1", "READY", 1);
        DatasetVersion secondReady = fixture.version("ready-2", "asset-2", "READY", 1);
        first.setCurrentVersionId(firstReady.getId());
        second.setCurrentVersionId(secondReady.getId());
        fixture.stubPage(List.of(second), 2L, List.of(secondReady), List.of());
        when(fixture.fileCountService.countCurrentVersionFiles(second, secondReady)).thenReturn(22L);

        PageResponse<V2DatasetListItem> page = fixture.service.list(null, null, 2, null, 1);

        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getPage());
        assertEquals(1, page.getPageSize());
        assertEquals(1, page.getData().size());
        assertEquals("asset-2", page.getData().get(0).getDatasetId());
        assertEquals(22L, page.getData().get(0).getFileCount());
        verify(fixture.fileCountService).countCurrentVersionFiles(second, secondReady);
        verify(fixture.fileCountService, never()).countCurrentVersionFiles(first, firstReady);
    }

    private static final class Fixture {
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        private final DatasetSampleRepository sampleRepo = mock(DatasetSampleRepository.class);
        private final DatasetVersionFileCountService fileCountService =
                mock(DatasetVersionFileCountService.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final DatasetWorkspaceReadinessService readinessService =
                mock(DatasetWorkspaceReadinessService.class);
        private final DatasetWorkspaceSourceInspector sourceInspector =
                mock(DatasetWorkspaceSourceInspector.class);
        private final DatasetCatalogQueryService catalogQueryService =
                new DatasetCatalogQueryService(
                        assetRepo,
                        versionRepo,
                        importJobRepo,
                        fileCountService,
                        authContext
                );
        private final V2DatasetCatalogService service = new V2DatasetCatalogService(
                catalogQueryService,
                new ObjectMapper(),
                readinessService,
                sourceInspector
        );

        private Fixture() {
            when(readinessService.evaluateCatalog(
                    any(DatasetAsset.class),
                    any(DatasetVersion.class)
            )).thenAnswer(invocation -> {
                DatasetVersion draft = invocation.getArgument(1);
                return new V2DatasetPublishReadiness(
                        false,
                        draft.getWorkspaceRevision() == null
                                ? 0L
                                : draft.getWorkspaceRevision(),
                        List.of(new V2DatasetPublishBlocker(
                                "TEST_BLOCKER",
                                "not ready"
                        ))
                );
            });
            when(sourceInspector.inspect(
                    any(DatasetAsset.class),
                    nullable(DatasetVersion.class)
            )).thenAnswer(invocation -> {
                DatasetVersion ready = invocation.getArgument(1);
                return ready == null
                        ? new V2DatasetEditability(
                                false,
                                List.of(new V2DatasetPublishBlocker(
                                        "READY_VERSION_REQUIRED",
                                        "ready required"
                                ))
                        )
                        : new V2DatasetEditability(true, List.of());
            });
        }

        private void stub(
                List<DatasetAsset> assets,
                List<DatasetVersion> versions,
                List<ImportJob> jobs
        ) {
            when(authContext.isAdmin()).thenReturn(false);
            when(authContext.currentUserId()).thenReturn(7);
            stubPage(assets, assets.size(), versions, jobs);
        }

        private void stubPage(
                List<DatasetAsset> assets,
                long total,
                List<DatasetVersion> versions,
                List<ImportJob> jobs
        ) {
            when(authContext.isAdmin()).thenReturn(false);
            when(authContext.currentUserId()).thenReturn(7);
            when(assetRepo.searchCatalogForOwner(
                    eq(7),
                    nullable(String.class),
                    nullable(String.class),
                    org.mockito.ArgumentMatchers.any(Pageable.class)
            )).thenReturn(new PageImpl<>(assets, Pageable.unpaged(), total));
            when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection())).thenReturn(versions);
            when(importJobRepo.findByDatasetVersionIdIn(anyCollection())).thenReturn(jobs);
            for (DatasetVersion version : versions) {
                if ("DRAFT".equals(version.getStatus())) {
                    when(sampleRepo.countByDatasetVersionIdAndDeletedFalse(version.getId()))
                            .thenReturn(1L);
                }
            }
        }

        private DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setName("multimodal");
            asset.setType("MULTIMODAL");
            asset.setOwnerUserId(7);
            asset.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            asset.setUpdatedAt(asset.getCreatedAt());
            asset.setDeleted(false);
            return asset;
        }

        private DatasetAsset asset(String id, String name) {
            DatasetAsset asset = asset();
            asset.setId(id);
            asset.setName(name);
            return asset;
        }

        private DatasetVersion version(String id, String status, int versionNo) {
            return version(id, "asset-1", status, versionNo);
        }

        private DatasetVersion version(String id, String assetId, String status, int versionNo) {
            DatasetVersion version = new DatasetVersion();
            version.setId(id);
            version.setAssetId(assetId);
            version.setVersion("v" + versionNo);
            version.setVersionLabel("v" + versionNo);
            version.setVersionNo(versionNo);
            version.setStatus(status);
            version.setOwnerUserId(7);
            version.setCreatedAt(Instant.parse("2026-01-0" + versionNo + "T00:00:00Z"));
            version.setDeleted(false);
            return version;
        }

        private ImportJob job(String versionId, String status, int progress) {
            ImportJob job = new ImportJob();
            job.setId("job-" + versionId);
            job.setDatasetVersionId(versionId);
            job.setStatus(status);
            job.setProgress(progress);
            job.setCreatedAt(Instant.parse("2026-01-10T00:00:00Z"));
            return job;
        }
    }
}
