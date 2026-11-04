package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.MinioConfig;
import com.tss.platform.config.InferenceKubernetesResourceProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.CreateInferenceTaskRequest;
import com.tss.platform.dto.InferenceTaskDto;
import com.tss.platform.dto.UpdateInferenceResultRequest;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.InferenceScriptVersion;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.entity.MinioDeleteTask;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.inference.InferenceExecutorRouter;
import com.tss.platform.inference.InferenceHardwareOptionService;
import com.tss.platform.inference.InferenceResourceProfileService;
import com.tss.platform.inference.KubernetesInferenceExecutor;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.InferenceScriptAssetRepository;
import com.tss.platform.repository.InferenceScriptVersionRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.repository.MinioDeleteTaskRepository;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.TrainingEnvironmentService;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InferenceTaskServiceTest {

    private FakeInferenceTaskRepository taskRepo;
    private FakeModelVersionRepository modelVersionRepo;
    private FakeDatasetVersionRepository datasetVersionRepo;
    private FakeScriptService scriptService;
    private FakeExecutorRouter executorRouter;
    private FakeMinioService minioService;
    private FakeMinioDeleteTaskService minioDeleteTaskService;
    private ModelArtifactAttestationService attestation;
    private InferenceHardwareOptionService hardwareOptionService;
    private InferenceTaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = new FakeInferenceTaskRepository();
        modelVersionRepo = new FakeModelVersionRepository();
        datasetVersionRepo = new FakeDatasetVersionRepository();
        scriptService = new FakeScriptService();
        executorRouter = new FakeExecutorRouter();
        minioService = new FakeMinioService();
        minioDeleteTaskService = new FakeMinioDeleteTaskService();
        attestation = mock(ModelArtifactAttestationService.class);
        when(attestation.attestReady(anyString())).thenAnswer(invocation -> {
            ModelVersion version = modelVersionRepo.model;
            if (version == null || !"READY".equals(version.getStatus())) {
                throw new IllegalArgumentException("模型版本必须是 READY 状态");
            }
            return new ModelArtifactAttestationService.AttestedArtifact(
                    version,
                    new com.tss.platform.entity.ModelAsset(),
                    1L,
                    "a".repeat(64),
                    null
            );
        });

        hardwareOptionService = mock(InferenceHardwareOptionService.class);
        when(hardwareOptionService.requireForCreate(
                any(), nullable(String.class), nullable(Long.class)))
                .thenReturn(new InferenceHardwareOptionService.HardwareSelection(
                        null, null, null, null, null, null, null));
        TrainingKubernetesProperties kubernetesProperties = new TrainingKubernetesProperties();
        kubernetesProperties.setExactGpuSelectionEnabled(true);
        service = new InferenceTaskService(
                taskRepo.proxy(),
                modelVersionRepo.proxy(),
                emptyProxy(ModelAssetRepository.class),
                attestation,
                datasetVersionRepo.proxy(),
                emptyProxy(DatasetAssetRepository.class),
                scriptService,
                executorRouter,
                new InferenceResourceProfileService(
                        new InferenceKubernetesResourceProperties(), kubernetesProperties),
                hardwareOptionService,
                minioService,
                minioDeleteTaskService,
                new FakeAuthContext(),
                new ObjectMapper()
        );
    }

    @Test
    void createsDatasetInferenceTask() {
        modelVersionRepo.model = modelVersion();
        scriptService.version = scriptVersion();
        datasetVersionRepo.dataset = datasetVersion();

        CreateInferenceTaskRequest req = new CreateInferenceTaskRequest();
        req.setName("batch infer");
        req.setModelVersionId("model-ver-1");
        req.setScriptVersionId("script-ver-1");
        req.setInputMode(InferenceTaskService.INPUT_MODE_DATASET_VERSION);
        req.setDatasetVersionId("dataset-ver-1");
        req.setParams(Map.of("threshold", 0.5));

        InferenceTaskDto dto = service.createTask(req);

        assertEquals("batch infer", dto.getName());
        assertEquals(InferenceTaskService.INPUT_MODE_DATASET_VERSION, dto.getInputMode());
        assertEquals("dataset-ver-1", dto.getDatasetVersionId());
        assertEquals("pending", dto.getStatus());
        assertEquals(1, dto.getCurrentAttempt());
        assertEquals(0, dto.getRetryCount());
        assertEquals(3, dto.getMaxRetries());
        assertEquals(false, dto.getRetryable());
        assertEquals("cpu-small", dto.getResourceProfileId());
        assertEquals(dto.getId(), executorRouter.startedTaskId);
        verify(attestation).attestReady("model-ver-1");
    }

    @Test
    void singleObjectRequiresObjectName() {
        modelVersionRepo.model = modelVersion();
        scriptService.version = scriptVersion();

        CreateInferenceTaskRequest req = new CreateInferenceTaskRequest();
        req.setModelVersionId("model-ver-1");
        req.setScriptVersionId("script-ver-1");
        req.setInputMode(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createTask(req)
        );
        assertEquals("inputObjectName 不能为空", error.getMessage());
    }

    @Test
    void createsSingleObjectInferenceTask() {
        modelVersionRepo.model = modelVersion();
        scriptService.version = scriptVersion();

        CreateInferenceTaskRequest req = new CreateInferenceTaskRequest();
        req.setModelVersionId("model-ver-1");
        req.setScriptVersionId("script-ver-1");
        req.setInputMode(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT);
        req.setInputObjectName("users/7/files/input.jpg");

        InferenceTaskDto dto = service.createTask(req);

        assertEquals(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT, dto.getInputMode());
        assertEquals("users/7/files/input.jpg", dto.getInputObjectName());
        assertEquals(dto.getId(), executorRouter.startedTaskId);
    }

    @Test
    void persistsSelectedGpuIdentityAndSoftBudget() {
        modelVersionRepo.model = modelVersion();
        scriptService.version = scriptVersion();
        when(hardwareOptionService.requireForCreate(any(), anyString(), any()))
                .thenReturn(new InferenceHardwareOptionService.HardwareSelection(
                        "hw-gpu-1", "tss-ai-worker-01", "1", "GPU-selected",
                        "RTX 4080", 16376L, 4096L));

        CreateInferenceTaskRequest req = new CreateInferenceTaskRequest();
        req.setModelVersionId("model-ver-1");
        req.setScriptVersionId("script-ver-1");
        req.setInputMode(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT);
        req.setInputObjectName("users/7/files/input.jpg");
        req.setResourceProfileId(InferenceResourceProfileService.GPU_PROFILE_ID);
        req.setHardwareTargetId("hw-gpu-1");
        req.setGpuMemoryLimitMiB(4096L);

        InferenceTaskDto dto = service.createTask(req);

        assertEquals("GPU-selected", dto.getGpuUuid());
        assertEquals("tss-ai-worker-01", dto.getGpuNodeName());
        assertEquals("1", dto.getGpuHostIndex());
        assertEquals(4096L, dto.getGpuMemoryLimitMiB());
    }

    @Test
    void rejectsUnknownResourceProfileBeforeSavingTask() {
        modelVersionRepo.model = modelVersion();
        scriptService.version = scriptVersion();

        CreateInferenceTaskRequest req = new CreateInferenceTaskRequest();
        req.setModelVersionId("model-ver-1");
        req.setScriptVersionId("script-ver-1");
        req.setInputMode(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT);
        req.setInputObjectName("users/7/files/input.jpg");
        req.setResourceProfileId("gpu-1");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createTask(req)
        );

        assertEquals("不支持的推理资源规格: gpu-1", error.getMessage());
        assertEquals(0, taskRepo.tasks.size());
        assertNull(executorRouter.startedTaskId);
    }

    @Test
    void rejectsDraftModelVersion() {
        ModelVersion draft = modelVersion();
        draft.setStatus("DRAFT");
        modelVersionRepo.model = draft;
        scriptService.version = scriptVersion();

        CreateInferenceTaskRequest req = new CreateInferenceTaskRequest();
        req.setModelVersionId("model-ver-1");
        req.setScriptVersionId("script-ver-1");
        req.setInputMode(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT);
        req.setInputObjectName("users/7/files/input.jpg");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createTask(req)
        );

        assertEquals("模型版本必须是 READY 状态", error.getMessage());
    }

    @Test
    void internalCallbackUpdatesResult() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("running");
        task.setProgress(10);
        taskRepo.tasks.put(task.getId(), task);

        UpdateInferenceResultRequest req = new UpdateInferenceResultRequest();
        req.setStatus("success");
        req.setResult(Map.of("count", 3));
        req.setOutputPath("minio://inference-results/infer-task-1/outputs/");
        req.setLogPath("minio://inference-results/infer-task-1/infer.log");

        service.updateResultInternal("infer-task-1", req);

        InferenceTask saved = taskRepo.tasks.get("infer-task-1");
        assertEquals("success", saved.getStatus());
        assertEquals(100, saved.getProgress());
        assertEquals("{\"count\":3}", saved.getResultJson());
        assertEquals("minio://inference-results/infer-task-1/outputs/", saved.getOutputPath());
    }

    @Test
    void retryFailedTaskStartsNewAttemptAndClearsCurrentResult() {
        modelVersionRepo.model = modelVersion();
        scriptService.version = scriptVersion();
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setModelVersionId("model-ver-1");
        task.setStatus("failed");
        task.setProgress(0);
        task.setCurrentAttempt(1);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setScriptVersionId("script-ver-1");
        task.setInputMode(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT);
        task.setInputObjectName("users/7/files/input.jpg");
        task.setOwnerUserId(7);
        task.setResultJson("{\"old\":true}");
        task.setLogPath("minio://users/7/inference-results/infer-task-1/attempt-1/infer.log");
        task.setOutputPath("minio://users/7/inference-results/infer-task-1/attempt-1/outputs/");
        task.setErrorMessage("script failed");
        task.setStartedAt(java.time.Instant.parse("2026-08-10T01:00:00Z"));
        task.setFinishedAt(java.time.Instant.parse("2026-08-10T01:01:00Z"));
        task.setServerIp("10.0.0.10");
        task.setGpuNodeName("tss-ai-worker-01");
        task.setGpuHostIndex("1");
        task.setGpuUuid("GPU-selected");
        task.setHardwareTargetId("hw-gpu-1");
        task.setGpuMemoryLimitMiB(4096L);
        task.setQueueSortIndex(9);
        taskRepo.tasks.put(task.getId(), task);

        InferenceTaskDto dto = service.retryTask("infer-task-1");

        InferenceTask saved = taskRepo.tasks.get("infer-task-1");
        assertEquals("pending", saved.getStatus());
        assertEquals(0, saved.getProgress());
        assertEquals(2, saved.getCurrentAttempt());
        assertEquals(1, saved.getRetryCount());
        assertNull(saved.getResultJson());
        assertNull(saved.getLogPath());
        assertNull(saved.getOutputPath());
        assertNull(saved.getErrorMessage());
        assertNull(saved.getStartedAt());
        assertNull(saved.getFinishedAt());
        assertNull(saved.getServerIp());
        assertEquals("GPU-selected", saved.getGpuUuid());
        assertEquals("tss-ai-worker-01", saved.getGpuNodeName());
        assertEquals(4096L, saved.getGpuMemoryLimitMiB());
        assertEquals(0, saved.getQueueSortIndex());
        assertEquals("infer-task-1", executorRouter.stoppedTaskId);
        assertEquals("infer-task-1", executorRouter.startedTaskId);
        assertEquals(2, executorRouter.startedAttempt);
        assertEquals(1, executorRouter.stoppedAttempt);
        assertEquals(2, dto.getCurrentAttempt());
        assertEquals(false, dto.getRetryable());
    }

    @Test
    void retryRejectsNonFailedTask() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("running");
        task.setCurrentAttempt(1);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setOwnerUserId(7);
        taskRepo.tasks.put(task.getId(), task);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.retryTask("infer-task-1")
        );

        assertEquals("only failed inference tasks can be retried", error.getMessage());
    }

    @Test
    void retryRejectsTaskOwnedByAnotherUser() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("failed");
        task.setCurrentAttempt(1);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setOwnerUserId(8);
        taskRepo.tasks.put(task.getId(), task);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.retryTask("infer-task-1")
        );

        assertEquals("推理任务不存在或无权限", error.getMessage());
        assertEquals(1, task.getCurrentAttempt());
        assertEquals(0, task.getRetryCount());
    }

    @Test
    void retryRejectsTaskAtRetryLimit() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("failed");
        task.setCurrentAttempt(4);
        task.setRetryCount(3);
        task.setMaxRetries(3);
        task.setOwnerUserId(7);
        taskRepo.tasks.put(task.getId(), task);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.retryTask("infer-task-1")
        );

        assertEquals("inference task retry limit exceeded", error.getMessage());
    }

    @Test
    void ignoresStaleAttemptCallback() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("running");
        task.setProgress(55);
        task.setCurrentAttempt(2);
        task.setRetryCount(1);
        task.setMaxRetries(3);
        taskRepo.tasks.put(task.getId(), task);

        UpdateInferenceResultRequest req = new UpdateInferenceResultRequest();
        req.setStatus("failed");
        req.setErrorMessage("old attempt failed late");

        service.updateResultInternal("infer-task-1", 1, req);

        InferenceTask saved = taskRepo.tasks.get("infer-task-1");
        assertEquals("running", saved.getStatus());
        assertEquals(55, saved.getProgress());
        assertNull(saved.getErrorMessage());
    }

    @Test
    void ignoresLegacyCallbackWithoutAttemptAfterRetry() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("running");
        task.setProgress(55);
        task.setCurrentAttempt(2);
        task.setRetryCount(1);
        task.setMaxRetries(3);
        taskRepo.tasks.put(task.getId(), task);

        UpdateInferenceResultRequest req = new UpdateInferenceResultRequest();
        req.setStatus("failed");
        req.setErrorMessage("old callback without attempt");

        service.updateResultInternal("infer-task-1", null, req);

        InferenceTask saved = taskRepo.tasks.get("infer-task-1");
        assertEquals("running", saved.getStatus());
        assertEquals(55, saved.getProgress());
        assertNull(saved.getErrorMessage());
    }

    @Test
    void ignoresCallbackAfterAttemptReachedTerminalState() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("success");
        task.setProgress(100);
        task.setCurrentAttempt(2);
        task.setRetryCount(1);
        task.setMaxRetries(3);
        taskRepo.tasks.put(task.getId(), task);

        UpdateInferenceResultRequest req = new UpdateInferenceResultRequest();
        req.setStatus("running");
        req.setProgress(35);

        service.updateResultInternal("infer-task-1", 2, req);

        InferenceTask saved = taskRepo.tasks.get("infer-task-1");
        assertEquals("success", saved.getStatus());
        assertEquals(100, saved.getProgress());
    }

    @Test
    void sameAttemptProgressDoesNotMoveBackwards() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("running");
        task.setProgress(55);
        task.setCurrentAttempt(1);
        taskRepo.tasks.put(task.getId(), task);

        UpdateInferenceResultRequest req = new UpdateInferenceResultRequest();
        req.setStatus("running");
        req.setProgress(35);

        service.updateResultInternal("infer-task-1", 1, req);

        assertEquals(55, taskRepo.tasks.get("infer-task-1").getProgress());
    }

    @Test
    void retryRejectsUnavailableDependenciesWithoutConsumingAttempt() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setModelVersionId("missing-model-version");
        task.setScriptVersionId("script-ver-1");
        task.setInputMode(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT);
        task.setInputObjectName("users/7/files/input.jpg");
        task.setStatus("failed");
        task.setCurrentAttempt(1);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setOwnerUserId(7);
        taskRepo.tasks.put(task.getId(), task);

        assertThrows(IllegalArgumentException.class, () -> service.retryTask("infer-task-1"));

        assertEquals("failed", task.getStatus());
        assertEquals(1, task.getCurrentAttempt());
        assertEquals(0, task.getRetryCount());
        assertNull(executorRouter.startedTaskId);
        assertNull(executorRouter.stoppedTaskId);
    }

    @Test
    void resultIncludesRetryMetadata() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("failed");
        task.setProgress(0);
        task.setCurrentAttempt(2);
        task.setRetryCount(1);
        task.setMaxRetries(3);
        task.setOwnerUserId(7);
        taskRepo.tasks.put(task.getId(), task);

        var result = service.getResult("infer-task-1");

        assertEquals(2, result.getCurrentAttempt());
        assertEquals(1, result.getRetryCount());
        assertEquals(3, result.getMaxRetries());
        assertEquals(true, result.getRetryable());
    }

    @Test
    void deletesRunningTaskAndQueuesOutputCleanup() {
        InferenceTask task = new InferenceTask();
        task.setId("infer-task-1");
        task.setStatus("running");
        task.setScriptVersionId("script-ver-1");
        task.setOwnerUserId(7);
        task.setLogPath("minio://users/7/inference-results/infer-task-1/infer.log");
        task.setOutputPath("minio://users/7/inference-results/infer-task-1/outputs/");
        taskRepo.tasks.put(task.getId(), task);
        minioService.objectsByPrefix.put(
                "users/7/inference-results/infer-task-1/outputs/",
                List.of(
                        "users/7/inference-results/infer-task-1/outputs/result.json",
                        "users/7/inference-results/infer-task-1/outputs/prediction.jpg"
                )
        );

        Map<String, Object> result = service.deleteTask("infer-task-1");

        assertEquals(true, result.get("deleted"));
        assertEquals(3, result.get("queuedObjectCount"));
        assertEquals("infer-task-1", executorRouter.stoppedTaskId);
        assertFalse(taskRepo.tasks.containsKey("infer-task-1"));
        assertEquals(
                List.of(
                        "users/7/inference-results/infer-task-1/infer.log",
                        "users/7/inference-results/infer-task-1/outputs/result.json",
                        "users/7/inference-results/infer-task-1/outputs/prediction.jpg"
                ),
                minioDeleteTaskService.queuedObjectNames
        );
    }

    private static ModelVersion modelVersion() {
        ModelVersion version = new ModelVersion();
        version.setId("model-ver-1");
        version.setAssetId("model-asset-1");
        version.setStoragePath("users/7/models/model.zip");
        version.setStatus("READY");
        version.setOwnerUserId(7);
        version.setDeleted(false);
        return version;
    }

    private static InferenceScriptVersion scriptVersion() {
        InferenceScriptVersion version = new InferenceScriptVersion();
        version.setId("script-ver-1");
        version.setStoragePath("users/7/inference-scripts/script.zip");
        version.setEntryFile("infer.py");
        version.setRuntime("PYTHON3");
        version.setStatus("READY");
        version.setOwnerUserId(7);
        version.setDeleted(false);
        return version;
    }

    private static DatasetVersion datasetVersion() {
        DatasetVersion version = new DatasetVersion();
        version.setId("dataset-ver-1");
        version.setAssetId("dataset-asset-1");
        version.setStoragePath("users/7/datasets/data.zip");
        version.setStatus("READY");
        version.setOwnerUserId(7);
        version.setDeleted(false);
        return version;
    }

    @SuppressWarnings("unchecked")
    private static <T> T emptyProxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType.equals(boolean.class)) {
            return false;
        }
        if (returnType.equals(int.class) || returnType.equals(long.class)) {
            return returnType.equals(long.class) ? 0L : 0;
        }
        if (returnType.equals(Optional.class)) {
            return Optional.empty();
        }
        return null;
    }

    private static class FakeInferenceTaskRepository {
        final Map<String, InferenceTask> tasks = new HashMap<>();
        int findForUpdateCount;

        InferenceTaskRepository proxy() {
            return (InferenceTaskRepository) Proxy.newProxyInstance(
                    InferenceTaskRepository.class.getClassLoader(),
                    new Class<?>[]{InferenceTaskRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "save" -> {
                            InferenceTask task = (InferenceTask) args[0];
                            tasks.put(task.getId(), task);
                            yield task;
                        }
                        case "findById" -> Optional.ofNullable(tasks.get((String) args[0]));
                        case "findByIdForUpdate" -> {
                            findForUpdateCount++;
                            yield Optional.ofNullable(tasks.get((String) args[0]));
                        }
                        case "delete" -> {
                            InferenceTask task = (InferenceTask) args[0];
                            tasks.remove(task.getId());
                            yield null;
                        }
                        case "countByScriptVersionId" -> tasks.values()
                                .stream()
                                .filter(task -> Objects.equals(task.getScriptVersionId(), args[0]))
                                .count();
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }
    }

    private static class FakeModelVersionRepository {
        ModelVersion model;

        ModelVersionRepository proxy() {
            return (ModelVersionRepository) Proxy.newProxyInstance(
                    ModelVersionRepository.class.getClassLoader(),
                    new Class<?>[]{ModelVersionRepository.class},
                    (proxy, method, args) -> {
                        if ("findByIdAndDeletedFalse".equals(method.getName()) && model != null && model.getId().equals(args[0])) {
                            return Optional.of(model);
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }
    }

    private static class FakeDatasetVersionRepository {
        DatasetVersion dataset;

        DatasetVersionRepository proxy() {
            return (DatasetVersionRepository) Proxy.newProxyInstance(
                    DatasetVersionRepository.class.getClassLoader(),
                    new Class<?>[]{DatasetVersionRepository.class},
                    (proxy, method, args) -> {
                        if ("findByIdAndDeletedFalse".equals(method.getName()) && dataset != null && dataset.getId().equals(args[0])) {
                            return Optional.of(dataset);
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }
    }

    private static class FakeScriptService extends InferenceScriptService {
        InferenceScriptVersion version;

        FakeScriptService() {
            super(
                    emptyProxy(InferenceScriptAssetRepository.class),
                    emptyProxy(InferenceScriptVersionRepository.class),
                    emptyProxy(InferenceTaskRepository.class),
                    new FakeMinioService(),
                    new FakeMinioDeleteTaskService(),
                    new FakeAuthContext(),
                    new ObjectMapper()
            );
        }

        @Override
        public InferenceScriptVersion requireAccessibleVersion(String versionId) {
            if (version != null && version.getId().equals(versionId)) {
                return version;
            }
            throw new IllegalArgumentException("推理脚本版本不存在");
        }
    }

    private static class FakeExecutorRouter extends InferenceExecutorRouter {
        String startedTaskId;
        String stoppedTaskId;
        Integer startedAttempt;
        Integer stoppedAttempt;

        FakeExecutorRouter() {
            super(
                    new TrainingKubernetesProperties(),
                    nullTrainingEnvironmentService(),
                    nullKubernetesInferenceExecutor(),
                    emptyProxy(InferenceTaskRepository.class),
                    noopTransactionManager()
            );
        }

        @Override
        public void start(String taskId) {
            this.startedTaskId = taskId;
        }

        @Override
        public void start(String taskId, Integer attempt) {
            this.startedTaskId = taskId;
            this.startedAttempt = attempt;
        }

        @Override
        public void stop(String taskId) {
            this.stoppedTaskId = taskId;
        }

        @Override
        public void stop(String taskId, Integer attempt) {
            this.stoppedTaskId = taskId;
            this.stoppedAttempt = attempt;
        }
    }

    private static class FakeMinioService extends MinioService {
        final Map<String, List<String>> objectsByPrefix = new HashMap<>();

        FakeMinioService() {
            super(null, new MinioConfig());
        }

        @Override
        public StatObjectResponse stat(String objectName) {
            return null;
        }

        @Override
        public List<String> listObjectNames(String prefix) {
            return objectsByPrefix.getOrDefault(prefix, List.of());
        }
    }

    private static class FakeMinioDeleteTaskService extends MinioDeleteTaskService {
        final List<String> queuedObjectNames = new ArrayList<>();

        FakeMinioDeleteTaskService() {
            super(
                    emptyProxy(MinioDeleteTaskRepository.class),
                    new FakeMinioService(),
                    new MinioConfig(),
                    noopTransactionManager()
            );
        }

        @Override
        public MinioDeleteTask enqueueDefaultBucketDelete(
                String objectName,
                String sourceType,
                String sourceId,
                Integer ownerUserId
        ) {
            queuedObjectNames.add(objectName);
            return null;
        }
    }

    private static class FakeAuthContext extends AuthContext {
        @Override
        public Integer currentUserId() {
            return 7;
        }

        @Override
        public boolean isAdmin() {
            return false;
        }

        @Override
        public void requireOwnerAccess(Integer ownerUserId, String message) {
            if (ownerUserId == null || !ownerUserId.equals(7)) {
                throw new IllegalArgumentException(message);
            }
        }

        @Override
        public String userPrefix(Integer ownerUserId) {
            return "users/" + ownerUserId + "/";
        }
    }

    private static TrainingEnvironmentService nullTrainingEnvironmentService() {
        return null;
    }

    private static KubernetesInferenceExecutor nullKubernetesInferenceExecutor() {
        return null;
    }

    private static PlatformTransactionManager noopTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
