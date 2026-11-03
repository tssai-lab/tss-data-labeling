package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.platform.dto.CreateExperimentVersionRequest;
import com.tss.platform.dto.CreateTrainingExperimentRequest;
import com.tss.platform.dto.TrainingExperimentVersionDto;
import com.tss.platform.dto.TrainingModelArtifactDto;
import com.tss.platform.dto.UpdateHyperParamsRequest;
import com.tss.platform.dto.UpdateTrainingResultRequest;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.model.TaskType;
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
import com.tss.platform.training.KubernetesJobNaming;
import com.tss.platform.training.plan.TrainingOutputValidator;
import com.tss.platform.training.plan.TrainingRunSnapshot;
import com.tss.platform.training.plan.TrainingRunSpecFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TrainingExperimentService {

    private static final String STATUS_PENDING = "pending";
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "pending",
            "queued",
            "scheduled",
            "running",
            "success",
            "failed",
            "stopped"
    );
    private static final Set<String> TERMINAL_STATUSES = Set.of("success", "failed", "stopped", "cancelled");

    private final TrainingExperimentVersionRepository repo;
    private final ModelVersionRepository modelVersionRepo;
    private final ModelAssetRepository modelAssetRepo;
    private final ModelArtifactAttestationService modelArtifactAttestationService;
    private final DatasetVersionRepository datasetVersionRepo;
    private final DatasetAssetRepository datasetAssetRepo;
    private final CodeVersionRepository codeVersionRepo;
    private final CodeAssetRepository codeAssetRepo;
    private final CodeVersionService codeVersionService;
    private final TrainingRunSpecFactory trainingRunSpecFactory;
    private final TrainingOutputValidator trainingOutputValidator;
    private final TrainingExecutorRouter trainingExecutorRouter;
    private final JobScheduler jobScheduler;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final AuthContext authContext;
    private final MlflowTrackingService mlflowTrackingService;
    private final TrainingFailureDiagnosticService failureDiagnosticService;
    private final TrainingSubmissionGuard submissionGuard;

    private static final Logger LOG = LoggerFactory.getLogger(TrainingExperimentService.class);

    public TrainingExperimentService(
            TrainingExperimentVersionRepository repo,
            ModelVersionRepository modelVersionRepo,
            ModelAssetRepository modelAssetRepo,
            ModelArtifactAttestationService modelArtifactAttestationService,
            DatasetVersionRepository datasetVersionRepo,
            DatasetAssetRepository datasetAssetRepo,
            CodeVersionRepository codeVersionRepo,
            CodeAssetRepository codeAssetRepo,
            CodeVersionService codeVersionService,
            TrainingRunSpecFactory trainingRunSpecFactory,
            TrainingOutputValidator trainingOutputValidator,
            TrainingExecutorRouter trainingExecutorRouter,
            @Lazy JobScheduler jobScheduler,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            AuthContext authContext,
            MlflowTrackingService mlflowTrackingService,
            TrainingFailureDiagnosticService failureDiagnosticService,
            TrainingSubmissionGuard submissionGuard
    ) {
        this.repo = repo;
        this.modelVersionRepo = modelVersionRepo;
        this.modelAssetRepo = modelAssetRepo;
        this.modelArtifactAttestationService = modelArtifactAttestationService;
        this.datasetVersionRepo = datasetVersionRepo;
        this.datasetAssetRepo = datasetAssetRepo;
        this.codeVersionRepo = codeVersionRepo;
        this.codeAssetRepo = codeAssetRepo;
        this.codeVersionService = codeVersionService;
        this.trainingRunSpecFactory = trainingRunSpecFactory;
        this.trainingOutputValidator = trainingOutputValidator;
        this.trainingExecutorRouter = trainingExecutorRouter;
        this.jobScheduler = jobScheduler;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.authContext = authContext;
        this.mlflowTrackingService = mlflowTrackingService;
        this.failureDiagnosticService = failureDiagnosticService;
        this.submissionGuard = submissionGuard;
    }

    @Transactional
    public TrainingExperimentVersionDto createExperiment(CreateTrainingExperimentRequest req) {
        return submissionGuard.createOnce(authContext.currentUserId(), "new", req.getSubmissionKey(), req,
                () -> doCreateExperiment(req), this::getByIdOrExperimentId);
    }

    private TrainingExperimentVersionDto doCreateExperiment(CreateTrainingExperimentRequest req) {
        requireText(req.getCodeVersionId(), "codeVersionId 不能为空");
        requireText(req.getDatasetVersionId(), "datasetVersionId 不能为空");
        String trainingProfile = blankToNull(req.getTrainingProfile());
        String requestedPlanId = blankToNull(req.getPlanId());
        if (trainingProfile != null && requestedPlanId != null && !trainingProfile.equals(requestedPlanId)) {
            throw new IllegalArgumentException("trainingProfile 与 planId 不一致");
        }
        String effectivePlanId = requestedPlanId != null ? requestedPlanId : trainingProfile;
        requireText(effectivePlanId, "planId 不能为空");
        Object initialParams = req.getHyperParams() != null ? req.getHyperParams() : req.getParams();
        ResolvedCodeArtifact approvedCodeArtifact;

        String baseModelVersionId = resolveBaseModelVersionId(
                req.getBaseModelVersionId(),
                req.getModelVersionId()
        );
        requireText(baseModelVersionId, "baseModelVersionId 不能为空");
        approvedCodeArtifact = codeVersionService.requireApprovedForTraining(req.getCodeVersionId().trim());
        validateBaseModelVersion(baseModelVersionId);
        if (initialParams == null) {
            initialParams = Map.of();
        }

        String experimentId = "exp-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // 组装数据库实体
        TrainingExperimentVersion version = new TrainingExperimentVersion();
        version.setId(newVersionId()); //版本主键
        version.setExperimentId(experimentId); //同一个实验的多版本共用experimentId
        version.setVersionNo(1); //新建实验，第一个版本号=1
        version.setName(defaultName(req.getName(), experimentId)); //名字为空就用默认名
        version.setModelVersionId(baseModelVersionId);
        version.setCodeVersionId(req.getCodeVersionId().trim());
        version.setTrainingProfile(effectivePlanId);
        version.setTrainingMode("FROM_SCRATCH"); //固定训练模式：从头训练（非微调续训）
        version.setDatasetVersionId(req.getDatasetVersionId().trim());
        version.setStatus(STATUS_PENDING);
        version.setProgress(progressOf(STATUS_PENDING));
        version.setRemark(req.getRemark());
        version.setOwnerUserId(authContext.currentUserId());
        Instant now = Instant.now();
        version.setCreatedAt(now);
        version.setUpdatedAt(now);

        // 创建训练运行快照 TrainingRunSnapshot
        TrainingRunSnapshot snapshot = trainingRunSpecFactory.create(
                new TrainingRunSpecFactory.CreateCommand(
                        version.getId(),
                        now,
                        effectivePlanId,
                        blankToNull(req.getPlanVersion()),
                        blankToNull(req.getTrainingMode()),
                        blankToNull(req.getResourceProfileId()),
                        false,
                        version.getModelVersionId(),
                        version.getDatasetVersionId(),
                        version.getCodeVersionId(),
                        toParameterMap(initialParams),
                        approvedCodeArtifact,
                        req.getResourceRequest()
                )
        );
        applyRunSnapshot(version, snapshot);
        version.setHyperParamsJson(toJson(snapshot.resolvedParameters()));

        // save 持久化到数据库；
        TrainingExperimentVersion saved = repo.save(version);

        // 一般是事务提交之后，异步调用，通知调度系统启动训练任务
        startTrainingAfterCommit(saved.getId());
        return toDto(saved);
    }

    @Transactional
    public TrainingExperimentVersionDto createVersion(String experimentId, CreateExperimentVersionRequest req) {
        return submissionGuard.createOnce(authContext.currentUserId(), "version:" + experimentId, req.getSubmissionKey(), req,
                () -> doCreateVersion(experimentId, req), this::getByIdOrExperimentId);
    }

    private TrainingExperimentVersionDto doCreateVersion(String experimentId, CreateExperimentVersionRequest req) {
        // 同实验的两个不同创建请求排队分配编号，唯一约束仍作最终兜底。
        submissionGuard.lockExperiment(experimentId);
        TrainingExperimentVersion latest = repo.findTopByExperimentIdOrderByVersionNoDesc(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("experimentId 不存在"));
        requireExperimentAccess(latest);

        TrainingExperimentVersion version = new TrainingExperimentVersion();
        version.setId(newVersionId());
        version.setExperimentId(experimentId);
        version.setVersionNo(latest.getVersionNo() + 1);
        version.setName(defaultName(req.getName(), latest.getName()));
        // 只有请求同时传入两个不同字段时才算冲突；未传旧字段时可用新字段覆盖上一版模型。
        String resolvedModelVersionId = firstText(
                resolveBaseModelVersionId(req.getBaseModelVersionId(), req.getModelVersionId()),
                latest.getModelVersionId()
        );
        version.setModelVersionId(resolvedModelVersionId);
        version.setCodeVersionId(firstRequiredText(req.getCodeVersionId(), latest.getCodeVersionId(), "codeVersionId 不能为空"));
        version.setDatasetVersionId(firstRequiredText(req.getDatasetVersionId(), latest.getDatasetVersionId(), "datasetVersionId 不能为空"));
        String requestedPlanId = blankToNull(req.getPlanId());
        String inheritedPlanId = firstText(latest.getTrainingPlanId(), latest.getTrainingProfile());
        String effectivePlanId = firstText(requestedPlanId, inheritedPlanId);
        requireText(effectivePlanId, "planId 不能为空");
        version.setTrainingProfile(effectivePlanId);
        version.setTrainingMode(firstText(req.getTrainingMode(), latest.getTrainingMode()));
        requireText(resolvedModelVersionId, "baseModelVersionId 不能为空");
        ResolvedCodeArtifact approvedCodeArtifact = codeVersionService.requireApprovedForTraining(version.getCodeVersionId());
        validateBaseModelVersion(resolvedModelVersionId);
        Object params = req.getHyperParams() != null ? req.getHyperParams() : req.getParams();
        version.setStatus(STATUS_PENDING);
        version.setProgress(progressOf(STATUS_PENDING));
        version.setRemark(req.getRemark() != null ? req.getRemark() : latest.getRemark());
        version.setOwnerUserId(latest.getOwnerUserId());
        Instant now = Instant.now();
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        Object effectiveParams = params != null ? params : fromJson(latest.getHyperParamsJson());
        TrainingRunSnapshot snapshot = trainingRunSpecFactory.create(
                new TrainingRunSpecFactory.CreateCommand(
                        version.getId(),
                        now,
                        effectivePlanId,
                        firstText(req.getPlanVersion(), latest.getTrainingPlanVersion()),
                        firstText(req.getTrainingMode(), latest.getTrainingMode()),
                        firstText(req.getResourceProfileId(), latest.getResourceProfileId()),
                        false,
                        version.getModelVersionId(),
                        version.getDatasetVersionId(),
                        version.getCodeVersionId(),
                        toParameterMap(effectiveParams),
                        approvedCodeArtifact,
                        req.getResourceRequest()
                )
        );
        applyRunSnapshot(version, snapshot);
        version.setHyperParamsJson(toJson(snapshot.resolvedParameters()));
        TrainingExperimentVersion saved = repo.save(version);
        startTrainingAfterCommit(saved.getId());
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<TrainingExperimentVersionDto> listVersions(String experimentId) {
        return repo.findByExperimentIdOrderByVersionNoAsc(experimentId)
                .stream()
                .filter(this::canAccessExperiment)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TrainingExperimentVersionDto getVersion(String experimentId, Integer versionNo) {
        TrainingExperimentVersion version = repo.findByExperimentIdAndVersionNo(experimentId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("指定实验版本不存在"));
        requireExperimentAccess(version);
        return toDto(version);
    }

    @Transactional(readOnly = true)
    public TrainingExperimentVersionDto getLatestByExperimentId(String experimentId) {
        TrainingExperimentVersion version = repo.findTopByExperimentIdOrderByVersionNoDesc(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("experimentId 不存在"));
        requireExperimentAccess(version);
        return toDto(version);
    }

    @Transactional(readOnly = true)
    public TrainingExperimentVersionDto getByIdOrExperimentId(String id) {
        TrainingExperimentVersion byId = repo.findById(id).orElse(null);
        if (byId != null) {
            requireExperimentAccess(byId);
            return toDto(byId);
        }
        return getLatestByExperimentId(id);
    }

    @Transactional(readOnly = true)
    public List<TrainingExperimentVersionDto> listLatestExperiments() {
        return listLatestExperiments(null, null, null, null, null).getContent();
    }

    @Transactional(readOnly = true)
    public Page<TrainingExperimentVersionDto> listLatestExperiments(
            Integer current, Integer pageSize, String status, String name, String experimentId) {
        // 不传分页参数的旧客户端保持兼容；平台页面传入页码，每页最多 200 条。
        Pageable pageable = pageSize == null
                ? Pageable.unpaged()
                : PageRequest.of(
                        Math.max(1, current == null ? 1 : current) - 1, Math.min(200, Math.max(1, pageSize)));
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) normalizedStatus = normalizedStatus.toLowerCase(Locale.ROOT);
        return repo.searchLatestExperiments(authContext.isAdmin() ? null : authContext.currentUserId(),
                normalizedStatus, listKeyword(name), listKeyword(experimentId), pageable).map(this::toDto);
    }

    private static String listKeyword(String value) {
        if (value == null || value.isBlank()) return null;
        return "%" + value.trim().toLowerCase(Locale.ROOT).replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
    }

    @Transactional
    public TrainingExperimentVersionDto updateHyperParams(
            String experimentId,
            Integer versionNo,
            UpdateHyperParamsRequest req
    ) {
        TrainingExperimentVersion version = repo.findByExperimentIdAndVersionNo(experimentId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("指定实验版本不存在"));
        requireExperimentAccess(version);
        if (req == null) {
            throw new IllegalArgumentException("request body cannot be empty");
        }
        Object params = req.getHyperParams() != null ? req.getHyperParams() : req.getParams();
        if (version.getRunSpecJson() != null) {
            if (req.getRemark() == null) {
                throw new IllegalArgumentException("任务提交后参数快照不可修改，请基于该版本创建新训练版本");
            }
            if (params != null && !sameJson(version.getHyperParamsJson(), params)) {
                throw new IllegalArgumentException("任务提交后参数快照不可修改，只能修改备注");
            }
            version.setRemark(req.getRemark());
            version.setUpdatedAt(Instant.now());
            return toDto(repo.save(version));
        }
        if (params == null) {
            throw new IllegalArgumentException("hyperParams 不能为空");
        }
        version.setHyperParamsJson(toJson(params));
        if (req.getRemark() != null) {
            version.setRemark(req.getRemark());
        }
        version.setUpdatedAt(Instant.now());
        return toDto(repo.save(version));
    }

    public TrainingExperimentVersionDto stopTraining(String idOrExperimentId) {
        TrainingExperimentVersion version = repo.findById(idOrExperimentId)
                .orElseGet(() -> repo.findTopByExperimentIdOrderByVersionNoDesc(idOrExperimentId)
                        .orElseThrow(() -> new IllegalArgumentException("训练任务不存在")));
        requireExperimentAccess(version);
        if (version.getStatus() != null && TERMINAL_STATUSES.contains(version.getStatus())) return toDto(version);
        if (!"queued".equals(version.getStatus()) && !"scheduled".equals(version.getStatus()) && !"running".equals(version.getStatus())) {
            throw new IllegalArgumentException("只有调度中、已分配或训练中的任务可以停止");
        }
        // Job 删除后日志会消失，因此先保存一份有界、脱敏的现场。
        TrainingFailureDiagnosticService.CaptureResult capture = failureDiagnosticService.archiveBeforeStop(
                version,
                KubernetesJobNaming.jobNameForTraining(version.getId())
        );
        try {
            trainingExecutorRouter.stop(version.getId());
        } catch (RuntimeException exception) {
            if (capture != null && capture.archived()) {
                failureDiagnosticService.enqueueDeletion(version, capture.logPath());
            }
            throw exception;
        }
        // 外部停止完成后用短事务读取最新记录；并发完成已落库时保留其结果。
        return transactionTemplate.execute(tx -> {
            repo.stopIfActive(version.getId(), Instant.now());
            TrainingExperimentVersion current = repo.findById(version.getId())
                    .orElseThrow(() -> new IllegalArgumentException("训练任务不存在"));
            requireExperimentAccess(current);
            if (capture != null && capture.archived() && capture.logPath() != null) {
                if ("stopped".equals(current.getStatus())
                        && (current.getLogPath() == null || current.getLogPath().isBlank())) {
                    current.setLogPath(capture.logPath());
                    current.setUpdatedAt(Instant.now());
                    current = repo.save(current);
                } else if (!capture.logPath().equals(current.getLogPath())) {
                    failureDiagnosticService.enqueueDeletion(version, capture.logPath());
                }
            }
            return toDto(current);
        });
    }

    @Transactional
    public TrainingExperimentVersionDto updateStatus(String idOrExperimentId, String status) {
        TrainingExperimentVersion version = repo.findById(idOrExperimentId)
                .orElseGet(() -> repo.findTopByExperimentIdOrderByVersionNoDesc(idOrExperimentId)
                        .orElseThrow(() -> new IllegalArgumentException("训练任务不存在")));
        requireExperimentAccess(version);
        String normalizedStatus = normalizeStatus(status);
        if (version.getStatus() != null && TERMINAL_STATUSES.contains(version.getStatus())) return toDto(version);
        version.setStatus(normalizedStatus);
        version.setProgress(nextProgress(version, normalizedStatus, null));
        version.setUpdatedAt(Instant.now());
        return toDto(repo.save(version));
    }

    @Transactional
    public TrainingExperimentVersionDto updateResult(
            String experimentId,
            Integer versionNo,
            UpdateTrainingResultRequest req
    ) {
        TrainingExperimentVersion version = repo.findByExperimentIdAndVersionNo(experimentId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("指定实验版本不存在"));
        requireExperimentAccess(version);
        applyResult(version, req, true);
        return toDto(repo.save(version));
    }

    @Transactional
    public TrainingExperimentVersionDto updateResultByIdOrExperimentId(
            String idOrExperimentId,
            UpdateTrainingResultRequest req
    ) {
        TrainingExperimentVersion version = repo.findById(idOrExperimentId)
                .orElseGet(() -> repo.findTopByExperimentIdOrderByVersionNoDesc(idOrExperimentId)
                        .orElseThrow(() -> new IllegalArgumentException("训练任务不存在")));
        requireExperimentAccess(version);
        applyResult(version, req, true);
        return toDto(repo.save(version));
    }

    /** Worker 内部回调，跳过用户权限校验（由 InternalTrainingCallbackController 校验 token） */
    @Transactional
    public TrainingExperimentVersionDto updateResultInternal(
            String trainingId,
            UpdateTrainingResultRequest req
    ) {
        TrainingExperimentVersion version = repo.findById(trainingId)
                .orElseThrow(() -> new IllegalArgumentException("训练任务不存在: " + trainingId));
        if (TERMINAL_STATUSES.contains(version.getStatus())) {
            if ("success".equals(version.getStatus())
                    && req != null
                    && "success".equalsIgnoreCase(blankToNull(req.getStatus()) == null ? "" : req.getStatus().trim())
                    && version.getProducedModelVersionId() == null) {
                if (version.getRunSpecJson() != null && version.getTrainingOutputJson() == null) {
                    applyValidatedOutput(version, trainingOutputValidator.validate(
                            version,
                            req.getTrainingOutput(),
                            req.getTrainingOutputObjectName(),
                            req.getTrainingOutputSha256(),
                            req.getTrainingOutputSizeBytes()
                    ));
                }
                applyLegacyModelArtifact(version, req.getModelArtifact());
                prepareModelPublish(version, req.getModelArtifact() != null);
                version.setUpdatedAt(Instant.now());
                return toDto(repo.save(version));
            }
            return toDto(version);
        }
        applyResult(version, req, false);
        return toDto(repo.save(version));
    }

    @Transactional
    public TrainingExperimentVersionDto requestModelPublish(String trainingId) {
        TrainingExperimentVersion version = repo.findById(trainingId)
                .orElseThrow(() -> new IllegalArgumentException("训练任务不存在: " + trainingId));
        requireExperimentAccess(version);
        if (!"success".equals(version.getStatus())) {
            throw new IllegalArgumentException("只有已完成的训练任务可以发布模型");
        }
        if (version.getRunSpecJson() == null || version.getRunSpecJson().isBlank()) {
            throw new IllegalArgumentException("当前训练方案不支持自动发布模型");
        }
        if (version.getProducedModelVersionId() != null) {
            version.setModelPublishStatus(TrainingModelPublishService.STATUS_PUBLISHED);
            version.setModelPublishError(null);
            return toDto(repo.save(version));
        }
        if (!TrainingModelPublishService.STATUS_PUBLISHING.equals(version.getModelPublishStatus())) {
            version.setModelPublishStatus(TrainingModelPublishService.STATUS_PENDING);
            version.setModelPublishError(null);
        }
        prepareModelPublish(version, true);
        version.setUpdatedAt(Instant.now());
        return toDto(repo.save(version));
    }

    @Transactional
    public void deleteExperiment(String idOrExperimentId) {
        TrainingExperimentVersion byId = repo.findById(idOrExperimentId).orElse(null);
        if (byId != null) {
            requireExperimentAccess(byId);
            List<TrainingExperimentVersion> versions = repo.findByExperimentIdOrderByVersionNoAsc(byId.getExperimentId());
            stopActiveTrainingJobs(versions);
            versions.forEach(failureDiagnosticService::enqueueDeletion);
            repo.deleteByExperimentId(byId.getExperimentId());
            return;
        }
        List<TrainingExperimentVersion> versions = repo.findByExperimentIdOrderByVersionNoAsc(idOrExperimentId);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("训练任务不存在");
        }
        requireExperimentAccess(versions.get(0));
        stopActiveTrainingJobs(versions);
        versions.forEach(failureDiagnosticService::enqueueDeletion);
        repo.deleteByExperimentId(idOrExperimentId);
    }

    /** 删除实验前，先停掉仍在运行/已调度的版本对应的 K8s Job，避免删记录后 Job 成孤儿继续占用底层资源 */
    private void stopActiveTrainingJobs(List<TrainingExperimentVersion> versions) {
        versions.forEach(v -> {
            if ("running".equals(v.getStatus()) || "scheduled".equals(v.getStatus())) {
                trainingExecutorRouter.stop(v.getId());
            }
        });
    }

    public TrainingExperimentVersionDto toDto(TrainingExperimentVersion version) {
        TrainingExperimentVersionDto dto = new TrainingExperimentVersionDto();
        dto.setId(version.getId());
        dto.setExperimentId(version.getExperimentId());
        dto.setVersionNo(version.getVersionNo());
        dto.setName(version.getName());
        dto.setModelVersionId(version.getModelVersionId());
        dto.setBaseModelVersionId(version.getModelVersionId());
        dto.setCodeVersionId(version.getCodeVersionId());
        dto.setTrainingProfile(version.getTrainingProfile());
        dto.setTrainingPlanId(version.getTrainingPlanId());
        dto.setTrainingPlanVersion(version.getTrainingPlanVersion());
        dto.setTrainingMode(version.getTrainingMode());
        dto.setResourceProfileId(version.getResourceProfileId());
        dto.setRunSpec(version.getRunSpecJson() == null ? null : fromJson(version.getRunSpecJson()));
        dto.setRunSpecSha256(version.getRunSpecSha256());
        dto.setInputModelSha256(version.getInputModelSha256());
        dto.setInputDatasetSha256(version.getInputDatasetSha256());
        dto.setInputCodeSha256(version.getInputCodeSha256());
        dto.setCodeApprovalRecordId(version.getCodeApprovalRecordId());
        dto.setRuntimeImage(version.getRuntimeImage());
        dto.setRuntimeImageDigest(version.getRuntimeImageDigest());
        dto.setDatasetVersionId(version.getDatasetVersionId());
        dto.setHyperParams(fromJson(version.getHyperParamsJson()));
        dto.setStatus(version.getStatus());
        dto.setProgress(version.getProgress() != null ? version.getProgress() : progressOf(version.getStatus()));
        dto.setMetrics(fromJson(version.getMetricsJson()));
        dto.setRunId(version.getRunId());
        dto.setMlflowExperimentId(version.getMlflowExperimentId());
        dto.setMlflowTrackingUri(version.getMlflowTrackingUri());
        dto.setLogPath(version.getLogPath());
        dto.setOutputPath(version.getOutputPath());
        dto.setProducedModelVersionId(version.getProducedModelVersionId());
        dto.setModelPublishStatus(version.getModelPublishStatus());
        dto.setModelPublishError(version.getModelPublishError());
        dto.setModelPublishedAt(version.getModelPublishedAt());
        dto.setModelArtifactPath(version.getModelArtifactPath());
        dto.setModelArtifactSha256(version.getModelArtifactSha256());
        dto.setModelArtifactSizeBytes(version.getModelArtifactSizeBytes());
        dto.setTrainingOutput(version.getTrainingOutputJson() == null ? null : fromJson(version.getTrainingOutputJson()));
        dto.setTrainingOutputSha256(version.getTrainingOutputSha256());
        dto.setTrainingOutputObjectName(version.getTrainingOutputObjectName());
        dto.setTrainingOutputSizeBytes(version.getTrainingOutputSizeBytes());
        dto.setErrorMessage(version.getErrorMessage());
        dto.setStartedAt(version.getStartedAt());
        dto.setFinishedAt(version.getFinishedAt());
        dto.setRemark(version.getRemark());
        dto.setOwnerUserId(version.getOwnerUserId());
        dto.setCreatedAt(version.getCreatedAt());
        dto.setUpdatedAt(version.getUpdatedAt());
        return dto;
    }

    private String toJson(Object value) {
        try {
            JsonNode node = toJsonNode(value);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("hyperParams 必须是合法 JSON");
        }
    }

    private Map<String, ?> toParameterMap(Object value) {
        try {
            JsonNode node = toJsonNode(value);
            if (!node.isObject()) {
                throw new IllegalArgumentException("训练参数必须是 JSON 对象");
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("训练参数必须是合法 JSON 对象");
        }
    }

    private void applyRunSnapshot(TrainingExperimentVersion version, TrainingRunSnapshot snapshot) {
        version.setTrainingPlanId(snapshot.runSpec().plan().id());
        version.setTrainingPlanVersion(snapshot.runSpec().plan().version());
        version.setTrainingMode(snapshot.runSpec().trainingMode().name());
        version.setResourceProfileId(snapshot.runSpec().resources().profileId());
        version.setRunSpecJson(snapshot.runSpecJson());
        version.setRunSpecSha256(snapshot.runSpecSha256());
        version.setInputModelSha256(snapshot.inputModelSha256());
        version.setInputDatasetSha256(snapshot.inputDatasetSha256());
        version.setInputCodeSha256(snapshot.inputCodeSha256());
        version.setCodeApprovalRecordId(snapshot.codeApprovalRecordId());
        version.setRuntimeImage(snapshot.runtimeImage());
        version.setRuntimeImageDigest(snapshot.runtimeImageDigest());
    }

    private JsonNode toJsonNode(Object value) throws Exception {
        if (value == null) {
            return objectMapper.createObjectNode();
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(text);
        }
        return objectMapper.valueToTree(value);
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

    private boolean sameJson(String storedJson, Object submittedValue) {
        try {
            return fromJson(storedJson).equals(toJsonNode(submittedValue));
        } catch (Exception e) {
            throw new IllegalArgumentException("hyperParams 必须是合法 JSON");
        }
    }

    private void applyResult(
            TrainingExperimentVersion version,
            UpdateTrainingResultRequest req,
            boolean acceptRemark
    ) {
        if (req == null) {
            throw new IllegalArgumentException("request body cannot be empty");
        }
        String nextStatus = req.getStatus() == null || req.getStatus().isBlank()
                ? version.getStatus()
                : normalizeStatus(req.getStatus());
        if (version.getStatus() != null && TERMINAL_STATUSES.contains(version.getStatus()) && !version.getStatus().equals(nextStatus)) return;
        boolean wasSuccess = "success".equals(version.getStatus());
        TrainingOutputValidator.ValidatedOutput validatedOutput = null;
        if ("success".equals(nextStatus) && version.getRunSpecJson() != null) {
            validatedOutput = trainingOutputValidator.validate(
                    version,
                    req.getTrainingOutput(),
                    req.getTrainingOutputObjectName(),
                    req.getTrainingOutputSha256(),
                    req.getTrainingOutputSizeBytes()
            );
        }
        if (nextStatus != null) {
            version.setStatus(nextStatus);
        }
        if (req.getProgress() != null
                || (req.getStatus() != null && !req.getStatus().isBlank())) {
            version.setProgress(nextProgress(version, nextStatus, req.getProgress()));
        }
        if (validatedOutput != null) {
            applyValidatedOutput(version, validatedOutput);
        } else if (req.getMetrics() != null) {
            version.setMetricsJson(toResultJson(req.getMetrics(), "metrics must be valid JSON"));
        }
        if (req.getRunId() != null) {
            version.setRunId(blankToNull(req.getRunId()));
        }
        if (req.getMlflowExperimentId() != null) {
            version.setMlflowExperimentId(blankToNull(req.getMlflowExperimentId()));
        }
        if (req.getMlflowTrackingUri() != null) {
            version.setMlflowTrackingUri(blankToNull(req.getMlflowTrackingUri()));
        }
        if (req.getLogPath() != null) {
            version.setLogPath(blankToNull(req.getLogPath()));
        }
        if (req.getOutputPath() != null) {
            version.setOutputPath(blankToNull(req.getOutputPath()));
        }
        applyLegacyModelArtifact(version, req.getModelArtifact());
        if ("success".equals(nextStatus)) {
            prepareModelPublish(version, false);
        }
        if (req.getErrorMessage() != null) {
            version.setErrorMessage(blankToNull(req.getErrorMessage()));
        }
        if (validatedOutput == null && req.getStartedAt() != null) {
            version.setStartedAt(req.getStartedAt());
        }
        if (validatedOutput == null && req.getFinishedAt() != null) {
            version.setFinishedAt(req.getFinishedAt());
        }
        if (acceptRemark && req.getRemark() != null) {
            version.setRemark(req.getRemark());
        }
        version.setUpdatedAt(Instant.now());
        // 本次回调把任务置为成功：把最终指标补写到 MLflow（供前端指标可视化读取）
        if ("success".equals(nextStatus) && !wasSuccess) {
            syncMetricsToMlflow(version);
        }
    }

    /**
     * 训练成功回调时，把最终指标补写到训练脚本上报的 MLflow run 里。
     * 只写数值型指标（MLflow metrics 只接受数值），非数值字段（如 trainingPlan 名称）自动跳过。
     */
    private void syncMetricsToMlflow(TrainingExperimentVersion version) {
        try {
            if (version.getRunId() == null || version.getRunId().isBlank()) {
                return;
            }
            Map<String, Double> numeric = numericMetrics(version.getMetricsJson());
            if (numeric.isEmpty()) {
                return;
            }
            mlflowTrackingService.logMetricsToUri(
                    version.getMlflowTrackingUri(),
                    version.getRunId(),
                    numeric,
                    metricBackfillStep(version)
            );
        } catch (Exception e) {
            LOG.warn("训练指标同步到 MLflow 失败: trainingId={}, error={}", version.getId(), e.getMessage());
        }
    }

    /**
     * 最终指标只是过程指标的兜底，横轴应落在实际训练末轮，不能伪造成 step=0。
     * 旧任务没有合法 epochs 时按第 1 轮处理，仍保持单点终值语义。
     */
    private int metricBackfillStep(TrainingExperimentVersion version) {
        try {
            JsonNode params = version.getHyperParamsJson() == null || version.getHyperParamsJson().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(version.getHyperParamsJson());
            JsonNode epochs = params.path("epochs");
            if (epochs.canConvertToInt() && epochs.asInt() > 0) {
                return epochs.asInt();
            }
        } catch (Exception ignored) {
            // 兼容历史脏数据：最终指标仍可作为第 1 轮单点展示。
        }
        return 1;
    }

    private Map<String, Double> numericMetrics(String metricsJson) {
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            JsonNode node = metricsJson == null || metricsJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(metricsJson);
            if (!node.isObject()) {
                return result;
            }
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isNumber()) {
                    result.put(entry.getKey(), value.asDouble());
                } else if (value.isTextual()) {
                    try {
                        result.put(entry.getKey(), Double.parseDouble(value.asText()));
                    } catch (NumberFormatException ignored) {
                        // 非数值字符串指标（如 trainingPlan 名称）跳过
                    }
                }
            });
        } catch (Exception ignored) {
            // 指标 JSON 解析失败则视为无可写指标
        }
        return result;
    }

    private void applyValidatedOutput(
            TrainingExperimentVersion version,
            TrainingOutputValidator.ValidatedOutput validatedOutput
    ) {
        version.setMetricsJson(toResultJson(validatedOutput.metrics(), "TrainingOutput metrics must be valid JSON"));
        version.setTrainingOutputJson(validatedOutput.json());
        version.setTrainingOutputObjectName(validatedOutput.objectName());
        version.setTrainingOutputSha256(validatedOutput.sha256());
        version.setTrainingOutputSizeBytes(validatedOutput.sizeBytes());
        version.setStartedAt(validatedOutput.startedAt());
        version.setFinishedAt(validatedOutput.finishedAt());
    }

    private void applyModelArtifact(
            TrainingExperimentVersion version,
            TrainingModelArtifactDto artifact
    ) {
        if (artifact == null) {
            return;
        }
        String objectName = blankToNull(artifact.getObjectName());
        if (objectName != null) {
            version.setModelArtifactPath(objectName);
        }
        String sha256 = blankToNull(artifact.getSha256());
        if (sha256 != null) {
            version.setModelArtifactSha256(sha256.toLowerCase(Locale.ROOT));
        }
        if (artifact.getSizeBytes() != null && artifact.getSizeBytes() > 0) {
            version.setModelArtifactSizeBytes(artifact.getSizeBytes());
        }
    }

    private void applyLegacyModelArtifact(
            TrainingExperimentVersion version,
            TrainingModelArtifactDto artifact
    ) {
        if (version.getRunSpecJson() != null && !version.getRunSpecJson().isBlank()) {
            return;
        }
        applyModelArtifact(version, artifact);
    }

    private void prepareModelPublish(
            TrainingExperimentVersion version,
            boolean allowRetryAfterArtifactCallback
    ) {
        if (version.getProducedModelVersionId() != null) {
            version.setModelPublishStatus(TrainingModelPublishService.STATUS_PUBLISHED);
            version.setModelPublishError(null);
            return;
        }
        if (version.getRunSpecJson() != null && !version.getRunSpecJson().isBlank()) {
            if (version.getModelArtifactPath() == null || version.getModelArtifactPath().isBlank()) {
                version.setModelArtifactPath(defaultRunSpecModelPath(version));
            }
        }
        if (version.getModelPublishStatus() == null
                || (allowRetryAfterArtifactCallback
                    && TrainingModelPublishService.STATUS_FAILED.equals(version.getModelPublishStatus()))) {
            version.setModelPublishStatus(TrainingModelPublishService.STATUS_PENDING);
            version.setModelPublishError(null);
        }
    }

    private String defaultRunSpecModelPath(TrainingExperimentVersion version) {
        try {
            JsonNode artifacts = objectMapper.readTree(version.getRunSpecJson())
                    .path("outputs").path("artifacts");
            if (!artifacts.isArray()) {
                throw new IllegalArgumentException("RunSpec has no output artifacts");
            }
            String path = null;
            for (JsonNode artifact : artifacts) {
                if (artifact.path("publishAsModel").asBoolean(false)) {
                    if (path != null || !artifact.path("path").isTextual()) {
                        throw new IllegalArgumentException("RunSpec publishable model artifact is invalid");
                    }
                    path = artifact.path("path").asText();
                }
            }
            if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) {
                throw new IllegalArgumentException("RunSpec publishable model artifact is invalid");
            }
            return "training-results/" + version.getId() + "/artifacts/" + path;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("RunSpec cannot resolve publishable model artifact", exception);
        }
    }

    private String toResultJson(Object value, String errorMessage) {
        try {
            JsonNode node = toJsonNode(value);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status cannot be empty");
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status only supports pending, queued, scheduled, running, success, failed, stopped");
        }
        return normalized;
    }

    private Integer validateProgress(Integer progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        return progress;
    }

    private Integer progressOf(String status) {
        if ("success".equals(status)) {
            return 100;
        }
        if ("running".equals(status)) {
            return 50;
        }
        if ("scheduled".equals(status)) {
            return 5;
        }
        if ("failed".equals(status) || "stopped".equals(status)) {
            return 0;
        }
        return 0;
    }

    private int currentProgress(TrainingExperimentVersion version) {
        return version.getProgress() == null ? 0 : version.getProgress();
    }

    private int nextProgress(
            TrainingExperimentVersion version,
            String status,
            Integer reportedProgress
    ) {
        if ("success".equals(status)) {
            return 100;
        }
        int candidate = reportedProgress == null
                ? progressOf(status)
                : validateProgress(reportedProgress);
        return Math.max(currentProgress(version), candidate);
    }

    private void startTrainingAfterCommit(String trainingId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // afterCommit 仍运行在原事务的线程上，线程局部仍绑定着刚提交事务的旧 EntityManager。
                    // 若直接执行，scheduleOrStart 新开的事务会复用旧 EM（其事务已结束），导致
                    // @Modifying 的 atomicBindNode 报 "no transaction is in progress"。
                    // 用独立线程执行，保证全新的线程局部与干净的事务上下文。
                    Thread thread = new Thread(
                            () -> scheduleOrStart(trainingId),
                            "training-schedule-" + trainingId
                    );
                    thread.setDaemon(true);
                    thread.start();
                }
            });
            return;
        }
        scheduleOrStart(trainingId);
    }

    private void scheduleOrStart(String trainingId) {
        LOG.info("scheduleOrStart enter, trainingId={}", trainingId);
        String result = transactionTemplate.execute(status -> {
            TrainingExperimentVersion task = repo.findById(trainingId).orElse(null);
            if (task == null) {
                LOG.warn("scheduleOrStart task not found, trainingId={}", trainingId);
                return null;
            }
            if (task.getStatus() != null && TERMINAL_STATUSES.contains(task.getStatus())) return null;
            LOG.info("scheduleOrStart in tx, trainingId={}, planId={}", trainingId, task.getTrainingPlanId());
            if (task.getTrainingPlanId() != null && !task.getTrainingPlanId().isBlank()) {
                Map<String, String> nodeSelector = jobScheduler.resolveNodeSelector(task);
                String assignedNode = jobScheduler.assignNodeForTraining(task, nodeSelector);
                if (assignedNode != null && !assignedNode.isBlank()) {
                    boolean bindOk = jobScheduler.bindTask(task.getId(), assignedNode);
                    if (bindOk) {
                        LOG.info("bindTask ok, assignedNode={}", assignedNode);
                        return assignedNode;
                    }
                    // 别的线程已抢先绑定，不要重复提交 K8s 任务
                    LOG.info("scheduleOrStart: task already bound by another thread, skip start. taskId={}", trainingId);
                    return null;
                } else {
                    LOG.info("no available node, enqueueTask");
                    jobScheduler.enqueueTask(task);
                    return null;
                }
            } else {
                LOG.info("no trainingPlanId, return __start__");
                return "__start__";
            }
        });

        // 事务已提交、绑定已落库后再启动异步提交，避免异步线程读到未提交的 serverIp
        LOG.info("scheduleOrStart after tx commit, result={}", result);
        if (result != null) {
            LOG.info("trigger start, result={}", result);
            trainingExecutorRouter.start(trainingId);
        } else {
            LOG.info("result is null, skip start");
        }
    }

    private String newVersionId() {
        return "train-ver-" + UUID.randomUUID().toString().replace("-", "");
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String firstRequiredText(String value, String fallback, String message) {
        String result = firstText(value, fallback);
        requireText(result, message);
        return result;
    }

    private void validateBaseModelVersion(String modelVersionId) {
        requireText(modelVersionId, "baseModelVersionId 不能为空");
        ModelVersion version = modelVersionRepo.findByIdAndDeletedFalse(modelVersionId.trim())
                .orElseThrow(() -> new IllegalArgumentException("基础模型权重版本不存在: " + modelVersionId));
        ModelAsset asset = modelAssetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("模型资产不存在: " + version.getAssetId()));
        Integer ownerUserId = version.getOwnerUserId() != null ? version.getOwnerUserId() : asset.getOwnerUserId();
        authContext.requireOwnerAccess(ownerUserId, "model version not found or no permission");
        ModelArtifactAttestationService.AttestedArtifact attested =
                modelArtifactAttestationService.attestReady(version.getId());
        applyAttestation(version, attested);
    }

    private String resolveBaseModelVersionId(String baseModelVersionId, String modelVersionId) {
        String base = blankToNull(baseModelVersionId);
        String legacy = blankToNull(modelVersionId);
        if (base != null && legacy != null && !base.equals(legacy)) {
            throw new IllegalArgumentException("baseModelVersionId 与 modelVersionId 不一致");
        }
        return base != null ? base : legacy;
    }

    private void validateModelDatasetMatch(String modelVersionId, String datasetVersionId) {
        requireText(modelVersionId, "modelVersionId 不能为空");
        requireText(datasetVersionId, "datasetVersionId 不能为空");
        String modelType = resolveModelTaskType(modelVersionId.trim());
        String datasetType = resolveDatasetTaskType(datasetVersionId.trim());
        if (!modelType.equals(datasetType)) {
            throw new IllegalArgumentException(
                    "模型类型与数据集类型不匹配：模型为 " + modelType + "，数据集为 " + datasetType);
        }
    }

    private String resolveModelTaskType(String modelVersionId) {
        ModelVersion version = modelVersionRepo.findByIdAndDeletedFalse(modelVersionId)
                .orElseThrow(() -> new IllegalArgumentException("模型版本不存在: " + modelVersionId));
        ModelAsset asset = modelAssetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("模型资产不存在: " + version.getAssetId()));
        Integer ownerUserId = version.getOwnerUserId() != null ? version.getOwnerUserId() : asset.getOwnerUserId();
        authContext.requireOwnerAccess(ownerUserId, "model version not found or no permission");
        ModelArtifactAttestationService.AttestedArtifact attested =
                modelArtifactAttestationService.attestReady(version.getId());
        applyAttestation(version, attested);
        return TaskType.normalize(asset.getType());
    }

    private void applyAttestation(
            ModelVersion version,
            ModelArtifactAttestationService.AttestedArtifact attested
    ) {
        version.setArtifactSha256(attested.sha256());
        version.setArtifactAttestedSha256(attested.sha256());
        version.setArtifactAttestedAt(attested.version().getArtifactAttestedAt());
    }

    private String resolveDatasetTaskType(String datasetVersionId) {
        DatasetVersion version = datasetVersionRepo.findByIdAndDeletedFalse(datasetVersionId)
                .orElseThrow(() -> new IllegalArgumentException("数据集版本不存在: " + datasetVersionId));
        DatasetAsset asset = datasetAssetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("数据集资产不存在: " + version.getAssetId()));
        Integer ownerUserId = version.getOwnerUserId() != null ? version.getOwnerUserId() : asset.getOwnerUserId();
        authContext.requireOwnerAccess(ownerUserId, "dataset version not found or no permission");
        if (!"READY".equals(version.getStatus())) {
            throw new IllegalArgumentException("dataset version must be READY for training");
        }
        if (version.getStoragePath() == null || version.getStoragePath().isBlank()) {
            throw new IllegalArgumentException("dataset version storage path is required for training");
        }
        return TaskType.normalize(asset.getType());
    }

    private boolean canAccessExperiment(TrainingExperimentVersion version) {
        return authContext.canAccessOwner(version.getOwnerUserId());
    }

    private void requireExperimentAccess(TrainingExperimentVersion version) {
        authContext.requireOwnerAccess(version.getOwnerUserId(), "experiment not found or no permission");
    }
}
