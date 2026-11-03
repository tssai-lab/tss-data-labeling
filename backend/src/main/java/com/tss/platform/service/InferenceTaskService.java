package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.platform.dto.CreateInferenceTaskRequest;
import com.tss.platform.dto.InferenceTaskDto;
import com.tss.platform.dto.InferenceTaskResultDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.dto.UpdateInferenceResultRequest;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.InferenceScriptVersion;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.inference.InferenceExecutorRouter;
import com.tss.platform.inference.InferenceHardwareOptionService;
import com.tss.platform.inference.InferenceResourceProfileService;
import com.tss.platform.dto.InferenceResourceProfileDto;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class InferenceTaskService {

    public static final String INPUT_MODE_SINGLE_OBJECT = "SINGLE_OBJECT";
    public static final String INPUT_MODE_DATASET_VERSION = "DATASET_VERSION";

    private static final String STATUS_PENDING = "pending";
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final String SOURCE_INFERENCE_TASK = "INFERENCE_TASK";
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "pending",
            "queued",
            "scheduled",
            "running",
            "success",
            "failed",
            "stopped"
    );
    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of("pending", "queued", "scheduled", "running");
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of("success", "failed", "stopped");

    private final InferenceTaskRepository taskRepo;
    private final ModelVersionRepository modelVersionRepo;
    private final ModelAssetRepository modelAssetRepo;
    private final ModelArtifactAttestationService modelArtifactAttestationService;
    private final DatasetVersionRepository datasetVersionRepo;
    private final DatasetAssetRepository datasetAssetRepo;
    private final InferenceScriptService scriptService;
    private final InferenceExecutorRouter executorRouter;
    private final InferenceResourceProfileService resourceProfileService;
    private final InferenceHardwareOptionService hardwareOptionService;
    private final MinioService minioService;
    private final MinioDeleteTaskService minioDeleteTaskService;
    private final AuthContext authContext;
    private final ObjectMapper objectMapper;

    public InferenceTaskService(
            InferenceTaskRepository taskRepo,
            ModelVersionRepository modelVersionRepo,
            ModelAssetRepository modelAssetRepo,
            ModelArtifactAttestationService modelArtifactAttestationService,
            DatasetVersionRepository datasetVersionRepo,
            DatasetAssetRepository datasetAssetRepo,
            InferenceScriptService scriptService,
            InferenceExecutorRouter executorRouter,
            InferenceResourceProfileService resourceProfileService,
            InferenceHardwareOptionService hardwareOptionService,
            MinioService minioService,
            MinioDeleteTaskService minioDeleteTaskService,
            AuthContext authContext,
            ObjectMapper objectMapper
    ) {
        this.taskRepo = taskRepo;
        this.modelVersionRepo = modelVersionRepo;
        this.modelAssetRepo = modelAssetRepo;
        this.modelArtifactAttestationService = modelArtifactAttestationService;
        this.datasetVersionRepo = datasetVersionRepo;
        this.datasetAssetRepo = datasetAssetRepo;
        this.scriptService = scriptService;
        this.executorRouter = executorRouter;
        this.resourceProfileService = resourceProfileService;
        this.hardwareOptionService = hardwareOptionService;
        this.minioService = minioService;
        this.minioDeleteTaskService = minioDeleteTaskService;
        this.authContext = authContext;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InferenceTaskDto createTask(CreateInferenceTaskRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body cannot be empty");
        }
        ModelVersion modelVersion = requireAccessibleModel(req.getModelVersionId());
        InferenceScriptVersion scriptVersion = scriptService.requireAccessibleVersion(req.getScriptVersionId());
        String inputMode = normalizeInputMode(req.getInputMode());
        String datasetVersionId = null;
        String inputObjectName = null;
        if (INPUT_MODE_DATASET_VERSION.equals(inputMode)) {
            DatasetVersion datasetVersion = requireAccessibleReadyDataset(req.getDatasetVersionId());
            datasetVersionId = datasetVersion.getId();
        } else {
            inputObjectName = requireAccessibleSingleObject(req.getInputObjectName());
        }

        JsonNode params = toJsonNode(req.getParams(), "params 必须是合法 JSON");
        InferenceResourceProfileDto resourceProfile = resourceProfileService
                .resolveForCreate(req.getResourceProfileId());
        String resourceProfileId = resourceProfile.id();
        InferenceHardwareOptionService.HardwareSelection hardwareSelection =
                hardwareOptionService.requireForCreate(
                        resourceProfile, req.getHardwareTargetId(), req.getGpuMemoryLimitMiB());
        String taskId = "infer-task-" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();

        InferenceTask task = new InferenceTask();
        task.setId(taskId);
        task.setName(defaultName(req.getName(), taskId));
        task.setModelVersionId(modelVersion.getId());
        task.setScriptVersionId(scriptVersion.getId());
        task.setInputMode(inputMode);
        task.setDatasetVersionId(datasetVersionId);
        task.setInputObjectName(inputObjectName);
        task.setResourceProfileId(resourceProfileId);
        task.setHardwareTargetId(hardwareSelection.hardwareTargetId());
        task.setGpuNodeName(hardwareSelection.nodeName());
        task.setGpuHostIndex(hardwareSelection.hostGpuIndex());
        task.setGpuUuid(hardwareSelection.gpuUuid());
        task.setGpuModel(hardwareSelection.gpuModel());
        task.setGpuTotalMemoryMiB(hardwareSelection.gpuTotalMemoryMiB());
        task.setGpuMemoryLimitMiB(hardwareSelection.gpuMemoryLimitMiB());
        task.setParamsJson(writeJson(params, "params 必须是合法 JSON"));
        task.setStatus(STATUS_PENDING);
        task.setProgress(progressOf(STATUS_PENDING));
        task.setCurrentAttempt(1);
        task.setRetryCount(0);
        task.setMaxRetries(DEFAULT_MAX_RETRIES);
        task.setRemark(normalizeText(req.getRemark()));
        task.setOwnerUserId(authContext.currentUserId());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        InferenceTask saved = taskRepo.save(task);
        startInferenceAfterCommit(saved.getId(), saved.getCurrentAttempt());
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<InferenceTaskDto> listTasks(Integer page, Integer pageSize, String status) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        String normalizedStatus = status == null || status.isBlank() ? null : normalizeStatus(status);
        PageRequest pageRequest = PageRequest.of(safePage - 1, safePageSize);
        Page<InferenceTask> result;
        if (authContext.isAdmin()) {
            result = normalizedStatus == null
                    ? taskRepo.findAllByOrderByCreatedAtDesc(pageRequest)
                    : taskRepo.findAllByStatusOrderByCreatedAtDesc(normalizedStatus, pageRequest);
        } else {
            result = normalizedStatus == null
                    ? taskRepo.findAllByOwnerUserIdOrderByCreatedAtDesc(authContext.currentUserId(), pageRequest)
                    : taskRepo.findAllByOwnerUserIdAndStatusOrderByCreatedAtDesc(
                            authContext.currentUserId(),
                            normalizedStatus,
                            pageRequest
                    );
        }

        PageResponse<InferenceTaskDto> response = new PageResponse<>();
        response.setData(result.getContent().stream().map(this::toDto).toList());
        response.setTotal(result.getTotalElements());
        response.setPage(safePage);
        response.setPageSize(safePageSize);
        response.setTotalPages(result.getTotalPages());
        return response;
    }

    @Transactional(readOnly = true)
    public InferenceTaskDto getTask(String id) {
        return toDto(requireAccessibleTask(id));
    }

    @Transactional(readOnly = true)
    public InferenceTaskResultDto getResult(String id) {
        InferenceTask task = requireAccessibleTask(id);
        InferenceTaskResultDto dto = new InferenceTaskResultDto();
        dto.setId(task.getId());
        dto.setStatus(task.getStatus());
        dto.setProgress(task.getProgress());
        dto.setCurrentAttempt(safeInteger(task.getCurrentAttempt(), 1));
        dto.setRetryCount(safeInteger(task.getRetryCount(), 0));
        dto.setMaxRetries(safeInteger(task.getMaxRetries(), DEFAULT_MAX_RETRIES));
        dto.setRetryable(isRetryable(task));
        dto.setLastRetryAt(task.getLastRetryAt());
        dto.setResult(fromJson(task.getResultJson()));
        dto.setLogPath(task.getLogPath());
        dto.setOutputPath(task.getOutputPath());
        dto.setErrorMessage(task.getErrorMessage());
        return dto;
    }

    @Transactional
    public InferenceTaskDto stopTask(String id) {
        InferenceTask task = requireAccessibleTaskForUpdate(id);
        int attempt = safeInteger(task.getCurrentAttempt(), 1);
        task.setStatus("stopped");
        task.setProgress(progressOf("stopped"));
        task.setFinishedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        InferenceTask saved = taskRepo.save(task);
        stopInferenceAfterCommit(saved.getId(), attempt);
        return toDto(saved);
    }

    @Transactional
    public InferenceTaskDto retryTask(String id) {
        InferenceTask task = requireAccessibleTaskForUpdate(id);
        if (!"failed".equals(task.getStatus())) {
            throw new IllegalArgumentException("only failed inference tasks can be retried");
        }
        int retryCount = safeInteger(task.getRetryCount(), 0);
        int maxRetries = safeInteger(task.getMaxRetries(), DEFAULT_MAX_RETRIES);
        if (retryCount >= maxRetries) {
            throw new IllegalArgumentException("inference task retry limit exceeded");
        }

        validateRetryDependencies(task);

        Instant now = Instant.now();
        int previousAttempt = safeInteger(task.getCurrentAttempt(), 1);
        int nextAttempt = previousAttempt + 1;
        task.setCurrentAttempt(nextAttempt);
        task.setRetryCount(retryCount + 1);
        task.setStatus(STATUS_PENDING);
        task.setProgress(progressOf(STATUS_PENDING));
        task.setResultJson(null);
        task.setLogPath(null);
        task.setOutputPath(null);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setServerIp(null);
        task.setQueueSortIndex(0);
        task.setLastRetryAt(now);
        task.setUpdatedAt(now);

        InferenceTask saved = taskRepo.save(task);
        restartInferenceAfterCommit(saved.getId(), previousAttempt, nextAttempt);
        return toDto(saved);
    }

    @Transactional
    public Map<String, Object> deleteTask(String id) {
        InferenceTask task = requireAccessibleTaskForUpdate(id);
        boolean shouldStop = ACTIVE_TASK_STATUSES.contains(task.getStatus());
        int attempt = safeInteger(task.getCurrentAttempt(), 1);

        int queuedObjectCount = queueTaskOutputDeletes(task);
        taskRepo.delete(task);
        if (shouldStop) {
            stopInferenceAfterCommit(task.getId(), attempt);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("deleted", true);
        result.put("minioDeleteQueued", queuedObjectCount > 0);
        result.put("queuedObjectCount", queuedObjectCount);
        return result;
    }

    @Transactional
    public InferenceTaskDto updateResultInternal(String taskId, Integer attempt, UpdateInferenceResultRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body cannot be empty");
        }
        InferenceTask task = taskRepo.findByIdForUpdate(requireText(taskId, "id cannot be empty"))
                .orElseThrow(() -> new IllegalArgumentException("inference task does not exist: " + taskId));
        if (shouldIgnoreCallback(task, attempt, req)) {
            return toDto(task);
        }
        applyResult(task, req);
        return toDto(taskRepo.save(task));
    }

    @Transactional
    public InferenceTaskDto updateResultInternal(String taskId, UpdateInferenceResultRequest req) {
        return updateResultInternal(taskId, null, req);
    }

    @Transactional
    public void markFailedFromExecutor(String taskId, String message) {
        taskRepo.findByIdForUpdate(taskId).ifPresent(task -> {
            if (TERMINAL_TASK_STATUSES.contains(task.getStatus())) {
                return;
            }
            task.setStatus("failed");
            task.setProgress(progressOf("failed"));
            task.setErrorMessage(normalizeText(message));
            task.setFinishedAt(Instant.now());
            task.setUpdatedAt(Instant.now());
            taskRepo.save(task);
        });
    }

    @Transactional
    public void markQueuedFromExecutor(String taskId) {
        taskRepo.findByIdForUpdate(taskId).ifPresent(task -> {
            if (TERMINAL_TASK_STATUSES.contains(task.getStatus())) {
                return;
            }
            // scheduled 状态的任务已分配到节点，不要降级回 queued
            if ("scheduled".equals(task.getStatus())) {
                return;
            }
            task.setStatus("queued");
            task.setProgress(progressOf("queued"));
            task.setUpdatedAt(Instant.now());
            taskRepo.save(task);
        });
    }

    public InferenceTaskDto toDto(InferenceTask task) {
        InferenceTaskDto dto = new InferenceTaskDto();
        dto.setId(task.getId());
        dto.setName(task.getName());
        dto.setModelVersionId(task.getModelVersionId());
        dto.setScriptVersionId(task.getScriptVersionId());
        dto.setInputMode(task.getInputMode());
        dto.setDatasetVersionId(task.getDatasetVersionId());
        dto.setInputObjectName(task.getInputObjectName());
        dto.setResourceProfileId(task.getResourceProfileId());
        dto.setHardwareTargetId(task.getHardwareTargetId());
        dto.setGpuNodeName(task.getGpuNodeName());
        dto.setGpuHostIndex(task.getGpuHostIndex());
        dto.setGpuUuid(task.getGpuUuid());
        dto.setGpuModel(task.getGpuModel());
        dto.setGpuTotalMemoryMiB(task.getGpuTotalMemoryMiB());
        dto.setGpuMemoryLimitMiB(task.getGpuMemoryLimitMiB());
        dto.setParams(fromJson(task.getParamsJson()));
        dto.setStatus(task.getStatus());
        dto.setProgress(task.getProgress());
        dto.setCurrentAttempt(safeInteger(task.getCurrentAttempt(), 1));
        dto.setRetryCount(safeInteger(task.getRetryCount(), 0));
        dto.setMaxRetries(safeInteger(task.getMaxRetries(), DEFAULT_MAX_RETRIES));
        dto.setRetryable(isRetryable(task));
        dto.setLastRetryAt(task.getLastRetryAt());
        dto.setResult(fromJson(task.getResultJson()));
        dto.setLogPath(task.getLogPath());
        dto.setOutputPath(task.getOutputPath());
        dto.setErrorMessage(task.getErrorMessage());
        dto.setStartedAt(task.getStartedAt());
        dto.setFinishedAt(task.getFinishedAt());
        dto.setRemark(task.getRemark());
        dto.setOwnerUserId(task.getOwnerUserId());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        return dto;
    }

    private boolean shouldIgnoreCallback(
            InferenceTask task,
            Integer attempt,
            UpdateInferenceResultRequest req
    ) {
        int currentAttempt = safeInteger(task.getCurrentAttempt(), 1);
        if ((attempt == null && currentAttempt > 1)
                || (attempt != null && attempt != currentAttempt)) {
            return true;
        }
        if (TERMINAL_TASK_STATUSES.contains(task.getStatus())) {
            return true;
        }
        if (req.getStatus() == null || req.getStatus().isBlank()) {
            return false;
        }
        String nextStatus = normalizeStatus(req.getStatus());
        return statusOrder(nextStatus) < statusOrder(task.getStatus());
    }

    private void applyResult(InferenceTask task, UpdateInferenceResultRequest req) {
        String previousStatus = task.getStatus();
        String nextStatus = previousStatus;
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            nextStatus = normalizeStatus(req.getStatus());
            task.setStatus(nextStatus);
            if (req.getProgress() == null) {
                task.setProgress(progressOf(nextStatus));
            }
        }
        if (req.getProgress() != null) {
            int nextProgress = validateProgress(req.getProgress());
            if (Objects.equals(previousStatus, nextStatus)
                    && !TERMINAL_TASK_STATUSES.contains(nextStatus)) {
                nextProgress = Math.max(safeInteger(task.getProgress(), 0), nextProgress);
            }
            task.setProgress(nextProgress);
        }
        if (req.getResult() != null) {
            task.setResultJson(writeJson(toJsonNode(req.getResult(), "result 必须是合法 JSON"), "result 必须是合法 JSON"));
        }
        if (req.getLogPath() != null) {
            task.setLogPath(blankToNull(req.getLogPath()));
        }
        if (req.getOutputPath() != null) {
            task.setOutputPath(blankToNull(req.getOutputPath()));
        }
        if (req.getErrorMessage() != null) {
            task.setErrorMessage(blankToNull(req.getErrorMessage()));
        }
        if (("running".equals(nextStatus) || "success".equals(nextStatus))
                && task.getErrorMessage() != null
                && task.getErrorMessage().startsWith("等待所选物理 GPU")) {
            task.setErrorMessage(null);
        }
        if (req.getStartedAt() != null) {
            task.setStartedAt(req.getStartedAt());
        } else if (task.getStartedAt() == null && ("running".equals(task.getStatus()) || "success".equals(task.getStatus()))) {
            task.setStartedAt(Instant.now());
        }
        if (req.getFinishedAt() != null) {
            task.setFinishedAt(req.getFinishedAt());
        } else if ("success".equals(task.getStatus()) || "failed".equals(task.getStatus()) || "stopped".equals(task.getStatus())) {
            task.setFinishedAt(Instant.now());
        }
        task.setUpdatedAt(Instant.now());
    }

    private int statusOrder(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case "pending" -> 0;
            case "queued" -> 1;
            case "scheduled" -> 2;
            case "running" -> 3;
            case "success", "failed", "stopped" -> 4;
            default -> 0;
        };
    }

    private ModelVersion requireAccessibleModel(String modelVersionId) {
        ModelVersion version = modelVersionRepo.findByIdAndDeletedFalse(requireText(modelVersionId, "modelVersionId 不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("模型版本不存在: " + modelVersionId));
        Integer ownerUserId = version.getOwnerUserId();
        if (ownerUserId == null) {
            ownerUserId = modelAssetRepo.findByIdAndDeletedFalse(version.getAssetId())
                    .map(ModelAsset::getOwnerUserId)
                    .orElse(null);
        }
        authContext.requireOwnerAccess(ownerUserId, "no permission for modelVersionId: " + modelVersionId);
        ModelArtifactAttestationService.AttestedArtifact attested =
                modelArtifactAttestationService.attestReady(version.getId());
        version.setArtifactSha256(attested.sha256());
        version.setArtifactAttestedSha256(attested.sha256());
        version.setArtifactAttestedAt(attested.version().getArtifactAttestedAt());
        return version;
    }

    private DatasetVersion requireAccessibleReadyDataset(String datasetVersionId) {
        DatasetVersion version = datasetVersionRepo.findByIdAndDeletedFalse(requireText(datasetVersionId, "datasetVersionId 不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("数据集版本不存在: " + datasetVersionId));
        if (!"READY".equals(version.getStatus())) {
            throw new IllegalArgumentException("数据集版本必须是 READY 状态");
        }
        Integer ownerUserId = version.getOwnerUserId();
        if (ownerUserId == null) {
            ownerUserId = datasetAssetRepo.findByIdAndDeletedFalse(version.getAssetId())
                    .map(DatasetAsset::getOwnerUserId)
                    .orElse(null);
        }
        authContext.requireOwnerAccess(ownerUserId, "no permission for datasetVersionId: " + datasetVersionId);
        if (version.getStoragePath() == null || version.getStoragePath().isBlank()) {
            throw new IllegalArgumentException("数据集版本缺少存储路径");
        }
        return version;
    }

    private String requireAccessibleSingleObject(String inputObjectName) {
        String cleanName = cleanObjectName(requireText(inputObjectName, "inputObjectName 不能为空"));
        if (!authContext.isAdmin() && !cleanName.startsWith(authContext.userPrefix(authContext.currentUserId()))) {
            throw new IllegalArgumentException("object not found or no permission");
        }
        try {
            minioService.stat(cleanName);
        } catch (Exception e) {
            throw new IllegalArgumentException("单文件输入不存在或不可访问: " + e.getMessage());
        }
        return cleanName;
    }

    private void validateRetryDependencies(InferenceTask task) {
        resourceProfileService.resolveForExecution(task.getResourceProfileId());
        requireAccessibleModel(task.getModelVersionId());
        scriptService.requireAccessibleVersion(task.getScriptVersionId());
        String inputMode = normalizeInputMode(task.getInputMode());
        if (INPUT_MODE_DATASET_VERSION.equals(inputMode)) {
            requireAccessibleReadyDataset(task.getDatasetVersionId());
        } else {
            requireAccessibleSingleObject(task.getInputObjectName());
        }
    }

    private InferenceTask requireAccessibleTask(String id) {
        InferenceTask task = taskRepo.findById(requireText(id, "id 不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("推理任务不存在"));
        authContext.requireOwnerAccess(task.getOwnerUserId(), "推理任务不存在或无权限");
        return task;
    }

    private InferenceTask requireAccessibleTaskForUpdate(String id) {
        InferenceTask task = taskRepo.findByIdForUpdate(requireText(id, "id 不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("推理任务不存在"));
        authContext.requireOwnerAccess(task.getOwnerUserId(), "推理任务不存在或无权限");
        return task;
    }

    private String normalizeInputMode(String inputMode) {
        String normalized = requireText(inputMode, "inputMode 不能为空").toUpperCase(Locale.ROOT);
        if (!INPUT_MODE_SINGLE_OBJECT.equals(normalized) && !INPUT_MODE_DATASET_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("inputMode only supports SINGLE_OBJECT, DATASET_VERSION");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        String normalized = requireText(status, "status cannot be empty").toLowerCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status only supports pending, queued, scheduled, running, success, failed, stopped");
        }
        return normalized;
    }

    private int queueTaskOutputDeletes(InferenceTask task) {
        LinkedHashSet<String> objectNames = new LinkedHashSet<>();
        String logObjectName = minioPathToObjectName(task.getLogPath());
        if (logObjectName != null) {
            objectNames.add(logObjectName);
        }

        String outputObjectName = minioPathToObjectName(task.getOutputPath());
        if (outputObjectName != null) {
            String outputPrefix = outputObjectName.endsWith("/") ? outputObjectName : outputObjectName + "/";
            try {
                objectNames.addAll(minioService.listObjectNames(outputPrefix));
            } catch (Exception e) {
                throw new IllegalArgumentException("列出推理输出文件失败: " + e.getMessage());
            }
        }

        String taskOutputPrefix = taskOutputPrefix(task);
        if (taskOutputPrefix != null) {
            try {
                objectNames.addAll(minioService.listObjectNames(taskOutputPrefix));
            } catch (Exception e) {
                throw new IllegalArgumentException("failed to list inference output files: " + e.getMessage());
            }
        }

        int queuedCount = 0;
        for (String objectName : objectNames) {
            try {
                minioDeleteTaskService.enqueueDefaultBucketDelete(
                        objectName,
                        SOURCE_INFERENCE_TASK,
                        task.getId(),
                        task.getOwnerUserId()
                );
                queuedCount++;
            } catch (Exception e) {
                throw new IllegalArgumentException("创建推理结果文件删除任务失败: " + e.getMessage());
            }
        }
        return queuedCount;
    }

    private boolean isRetryable(InferenceTask task) {
        return task != null
                && "failed".equals(task.getStatus())
                && safeInteger(task.getRetryCount(), 0) < safeInteger(task.getMaxRetries(), DEFAULT_MAX_RETRIES);
    }

    private String taskOutputPrefix(InferenceTask task) {
        if (task.getOwnerUserId() == null || task.getId() == null || task.getId().isBlank()) {
            return null;
        }
        return "users/" + task.getOwnerUserId() + "/inference-results/" + task.getId() + "/";
    }

    private String minioPathToObjectName(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("minio://")) {
            normalized = normalized.substring("minio://".length());
        }
        String objectName = cleanObjectName(normalized);
        return objectName.isBlank() ? null : objectName;
    }

    private Integer validateProgress(Integer progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        return progress;
    }

    private int safeInteger(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private Integer progressOf(String status) {
        if ("success".equals(status)) {
            return 100;
        }
        if ("running".equals(status)) {
            return 10;
        }
        if ("scheduled".equals(status)) {
            return 5;
        }
        return 0;
    }

    private JsonNode toJsonNode(Object value, String message) {
        try {
            if (value == null) {
                return objectMapper.createObjectNode();
            }
            if (value instanceof String textValue) {
                String text = textValue.trim();
                return text.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
            }
            return objectMapper.valueToTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    private JsonNode fromJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private String writeJson(JsonNode node, String message) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    private void startInferenceAfterCommit(String taskId, Integer attempt) {
        runAfterCommit(() -> executorRouter.start(taskId, attempt));
    }

    private void stopInferenceAfterCommit(String taskId, Integer attempt) {
        runAfterCommit(() -> executorRouter.stop(taskId, attempt));
    }

    private void restartInferenceAfterCommit(
            String taskId,
            Integer previousAttempt,
            Integer nextAttempt
    ) {
        runAfterCommit(() -> {
            executorRouter.stop(taskId, previousAttempt);
            executorRouter.start(taskId, nextAttempt);
        });
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private String cleanObjectName(String objectName) {
        String normalized = objectName.replace('\\', '/').replaceAll("^/+", "");
        for (String part : normalized.split("/")) {
            if (".".equals(part) || "..".equals(part) || part.contains("\u0000")) {
                throw new IllegalArgumentException("objectName 非法");
            }
        }
        return normalized;
    }

    private String defaultName(String name, String fallback) {
        String normalized = normalizeText(name);
        return normalized == null ? fallback : normalized;
    }

    private String requireText(String value, String message) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return normalizeText(value);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
