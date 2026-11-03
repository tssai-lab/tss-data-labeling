package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.TrainingModelArtifactDto;
import com.tss.platform.dto.UpdateTrainingResultRequest;
import com.tss.platform.dto.UpdateHyperParamsRequest;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.TrainingExecutorRouter;
import com.tss.platform.training.TrainingFailureDiagnosticService;
import com.tss.platform.training.plan.TrainingOutputValidator;
import com.tss.platform.training.plan.TrainingRunSpecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingExperimentServiceApplyResultTest {

    private TrainingExperimentVersionRepository repo;
    private TrainingExperimentService service;
    private AuthContext authContext;
    private TrainingFailureDiagnosticService failureDiagnosticService;
    private TrainingOutputValidator trainingOutputValidator;
    private MlflowTrackingService mlflowTrackingService;
    private TrainingExecutorRouter executor;

    @BeforeEach
    void setUp() {
        repo = mock(TrainingExperimentVersionRepository.class);
        authContext = mock(AuthContext.class);
        failureDiagnosticService = mock(TrainingFailureDiagnosticService.class);
        trainingOutputValidator = mock(TrainingOutputValidator.class);
        mlflowTrackingService = mock(MlflowTrackingService.class);
        executor = mock(TrainingExecutorRouter.class);
        doNothing().when(authContext).requireOwnerAccess(anyInt(), anyString());
        service = new TrainingExperimentService(
                repo,
                mock(ModelVersionRepository.class),
                mock(ModelAssetRepository.class),
                mock(ModelArtifactAttestationService.class),
                mock(DatasetVersionRepository.class),
                mock(DatasetAssetRepository.class),
                mock(CodeVersionRepository.class),
                mock(CodeAssetRepository.class),
                mock(CodeVersionService.class),
                mock(TrainingRunSpecFactory.class),
                trainingOutputValidator,
                executor,
                mock(JobScheduler.class),
                new org.springframework.transaction.support.TransactionTemplate(
                        mock(org.springframework.transaction.PlatformTransactionManager.class)),
                new ObjectMapper(),
                authContext,
                mlflowTrackingService,
                failureDiagnosticService,
                mock(TrainingSubmissionGuard.class)
        );
    }

    @Test
    void updateResultInternalKeepsProgressMonotonic() {
        TrainingExperimentVersion version = runningVersion("train-1", 50);
        when(repo.findById("train-1")).thenReturn(Optional.of(version));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateTrainingResultRequest req = new UpdateTrainingResultRequest();
        req.setStatus("running");
        req.setProgress(30);

        service.updateResultInternal("train-1", req);

        assertEquals(50, version.getProgress());
    }

    @Test
    void updateResultInternalSetsProgressTo100OnSuccess() {
        TrainingExperimentVersion version = runningVersion("train-2", 80);
        when(repo.findById("train-2")).thenReturn(Optional.of(version));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateTrainingResultRequest req = new UpdateTrainingResultRequest();
        req.setStatus("success");
        req.setProgress(95);

        service.updateResultInternal("train-2", req);

        assertEquals(100, version.getProgress());
    }

    @Test
    void successBackfillsFinalMetricsAtConfiguredLastEpochInsteadOfZero() {
        TrainingExperimentVersion version = runningVersion("train-metrics", 80);
        version.setRunId("run-1");
        version.setMlflowTrackingUri("http://mlflow:5000");
        version.setHyperParamsJson("{\"epochs\":2}");
        when(repo.findById("train-metrics")).thenReturn(Optional.of(version));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateTrainingResultRequest req = new UpdateTrainingResultRequest();
        req.setStatus("success");
        req.setMetrics(java.util.Map.of("train_loss", 0.25));

        service.updateResultInternal("train-metrics", req);

        verify(mlflowTrackingService).logMetricsToUri(
                eq("http://mlflow:5000"),
                eq("run-1"),
                eq(java.util.Map.of("train_loss", 0.25)),
                eq(2)
        );
    }

    @Test
    void runSpecSuccessIgnoresLegacyInnerArchiveDigest() {
        TrainingExperimentVersion version = runningVersion("train-runspec", 80);
        version.setRunSpecJson("{\"outputs\":{\"artifacts\":[{\"path\":\"model.zip\",\"publishAsModel\":true}]}}");
        when(repo.findById("train-runspec")).thenReturn(Optional.of(version));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(trainingOutputValidator.validate(any(), any(), any(), any(), any())).thenReturn(
                new TrainingOutputValidator.ValidatedOutput(
                        "{}",
                        new ObjectMapper().createObjectNode(),
                        Instant.parse("2026-08-28T16:00:00Z"),
                        Instant.parse("2026-08-28T16:01:00Z"),
                        "training-results/train-runspec/training-output.json",
                        "a".repeat(64),
                        128L
                )
        );

        TrainingModelArtifactDto legacy = new TrainingModelArtifactDto();
        legacy.setObjectName("training-results/train-runspec/artifacts/model.zip");
        legacy.setSha256("b".repeat(64));
        legacy.setSizeBytes(4096L);
        UpdateTrainingResultRequest req = new UpdateTrainingResultRequest();
        req.setStatus("success");
        req.setProgress(100);
        req.setModelArtifact(legacy);

        service.updateResultInternal("train-runspec", req);

        assertEquals("training-results/train-runspec/artifacts/model.zip", version.getModelArtifactPath());
        assertNull(version.getModelArtifactSha256());
        assertNull(version.getModelArtifactSizeBytes());
        assertEquals(TrainingModelPublishService.STATUS_PENDING, version.getModelPublishStatus());
    }

    @Test
    void legacySuccessStillRecordsLegacyModelArtifact() {
        TrainingExperimentVersion version = runningVersion("train-legacy", 80);
        when(repo.findById("train-legacy")).thenReturn(Optional.of(version));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TrainingModelArtifactDto legacy = new TrainingModelArtifactDto();
        legacy.setObjectName("training-results/train-legacy/artifacts/model.zip");
        legacy.setSha256("c".repeat(64));
        legacy.setSizeBytes(4096L);
        UpdateTrainingResultRequest req = new UpdateTrainingResultRequest();
        req.setStatus("success");
        req.setProgress(100);
        req.setModelArtifact(legacy);

        service.updateResultInternal("train-legacy", req);

        assertEquals("c".repeat(64), version.getModelArtifactSha256());
        assertEquals(4096L, version.getModelArtifactSizeBytes());
    }

    @Test
    void updateResultInternalDoesNotOverwriteUserRemark() {
        TrainingExperimentVersion version = runningVersion("train-remark", 80);
        version.setRemark("用户填写的验收说明");
        when(repo.findById("train-remark")).thenReturn(Optional.of(version));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateTrainingResultRequest req = new UpdateTrainingResultRequest();
        req.setStatus("running");
        req.setRemark("trainingOutput=minio://internal/path.json");

        service.updateResultInternal("train-remark", req);

        assertEquals("用户填写的验收说明", version.getRemark());
    }

    @Test
    void submittedTaskAllowsRemarkUpdateWithoutChangingHyperParams() {
        TrainingExperimentVersion version = runningVersion("train-submitted", 100);
        version.setExperimentId("exp-submitted");
        version.setRunSpecJson("{}");
        version.setHyperParamsJson("{\"epochs\":1,\"batchSize\":4}");
        version.setRemark("旧备注");
        when(repo.findByExperimentIdAndVersionNo("exp-submitted", 1))
                .thenReturn(Optional.of(version));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateHyperParamsRequest req = new UpdateHyperParamsRequest();
        req.setHyperParams(java.util.Map.of("batchSize", 4, "epochs", 1));
        req.setRemark("恢复后的用户备注");

        service.updateHyperParams("exp-submitted", 1, req);

        assertEquals("{\"epochs\":1,\"batchSize\":4}", version.getHyperParamsJson());
        assertEquals("恢复后的用户备注", version.getRemark());
    }

    @Test
    void submittedTaskStillRejectsHyperParamChanges() {
        TrainingExperimentVersion version = runningVersion("train-immutable", 100);
        version.setExperimentId("exp-immutable");
        version.setRunSpecJson("{}");
        version.setHyperParamsJson("{\"epochs\":1}");
        when(repo.findByExperimentIdAndVersionNo("exp-immutable", 1))
                .thenReturn(Optional.of(version));

        UpdateHyperParamsRequest req = new UpdateHyperParamsRequest();
        req.setHyperParams(java.util.Map.of("epochs", 2));
        req.setRemark("只想改备注");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateHyperParams("exp-immutable", 1, req)
        );

        assertEquals("任务提交后参数快照不可修改，只能修改备注", error.getMessage());
        assertEquals("{\"epochs\":1}", version.getHyperParamsJson());
    }

    @Test
    void stopTrainingPreservesExistingProgress() {
        TrainingExperimentVersion version = runningVersion("train-3", 42);
        when(repo.findById("train-3")).thenReturn(Optional.of(version));
        when(repo.stopIfActive(eq("train-3"), any())).thenAnswer(invocation -> {
            version.setStatus("stopped");
            return 1;
        });

        service.stopTraining("train-3");

        assertEquals("stopped", version.getStatus());
        assertEquals(42, version.getProgress());
    }

    @Test
    void stopTrainingAttachesTheSnapshotCapturedBeforeJobDeletion() {
        TrainingExperimentVersion version = runningVersion("train-stop-log", 42);
        version.setOwnerUserId(7);
        String logPath = "minio://users/7/training-failure-diagnostics/train-stop-log/failure.log";
        when(repo.findById("train-stop-log")).thenReturn(Optional.of(version));
        when(repo.stopIfActive(eq("train-stop-log"), any())).thenAnswer(invocation -> {
            version.setStatus("stopped");
            return 1;
        });
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(failureDiagnosticService.archiveBeforeStop(eq(version), anyString()))
                .thenReturn(new TrainingFailureDiagnosticService.CaptureResult(true, logPath));

        var result = service.stopTraining("train-stop-log");

        assertEquals("stopped", result.getStatus());
        assertEquals(logPath, result.getLogPath());
        var order = org.mockito.Mockito.inOrder(failureDiagnosticService, executor);
        order.verify(failureDiagnosticService).archiveBeforeStop(eq(version), anyString());
        order.verify(executor).stop("train-stop-log");
    }

    @Test
    void completionDuringExternalStopIsReturnedWithoutOverwritingItsResult() {
        TrainingExperimentVersion initial = runningVersion("race", 42);
        TrainingExperimentVersion completed = runningVersion("race", 100);
        completed.setStatus("success");
        completed.setProducedModelVersionId("result-model");
        when(repo.findById("race")).thenReturn(Optional.of(initial), Optional.of(completed));
        var result = service.stopTraining("race");
        assertEquals("success", result.getStatus());
        assertEquals("result-model", result.getProducedModelVersionId());
        verify(executor).stop("race");
        verify(repo).stopIfActive(eq("race"), any());
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void failedExternalStopDoesNotWriteStoppedAndFinishedTasksNeedNoExternalStop() {
        TrainingExperimentVersion version = runningVersion("stop-failure", 42);
        when(repo.findById("stop-failure")).thenReturn(Optional.of(version));
        org.mockito.Mockito.doThrow(new IllegalStateException("cluster unavailable")).when(executor).stop("stop-failure");
        assertThrows(IllegalStateException.class, () -> service.stopTraining("stop-failure"));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).stopIfActive(anyString(), any());
        version.setStatus("success");
        assertEquals("success", service.stopTraining("stop-failure").getStatus());
        org.mockito.Mockito.verify(executor, org.mockito.Mockito.times(1)).stop("stop-failure");
    }

    @Test
    void cancelledTasksIgnoreLateInternalProgress() {
        TrainingExperimentVersion version = runningVersion("cancelled", 42);
        version.setStatus("cancelled");
        when(repo.findById("cancelled")).thenReturn(Optional.of(version));
        UpdateTrainingResultRequest request = new UpdateTrainingResultRequest();
        request.setStatus("running");
        request.setProgress(90);
        assertEquals("cancelled", service.updateResultInternal("cancelled", request).getStatus());
        assertEquals(42, version.getProgress());
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void deletingExperimentQueuesItsFailureDiagnostics() {
        TrainingExperimentVersion version = runningVersion("train-delete", 42);
        version.setOwnerUserId(7);
        version.setLogPath(
                "minio://users/7/training-failure-diagnostics/train-delete/failure.log"
        );
        when(repo.findById("train-delete")).thenReturn(Optional.of(version));
        when(repo.findByExperimentIdOrderByVersionNoAsc("exp-1")).thenReturn(java.util.List.of(version));

        service.deleteExperiment("train-delete");

        verify(failureDiagnosticService).enqueueDeletion(version);
        verify(repo).deleteByExperimentId("exp-1");
    }

    private static TrainingExperimentVersion runningVersion(String id, int progress) {
        TrainingExperimentVersion version = new TrainingExperimentVersion();
        version.setId(id);
        version.setExperimentId("exp-1");
        version.setVersionNo(1);
        version.setStatus("running");
        version.setProgress(progress);
        return version;
    }
}
