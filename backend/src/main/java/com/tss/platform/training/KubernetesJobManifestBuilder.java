package com.tss.platform.training;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.modelcache.ModelCacheVolumeNaming;
import com.tss.platform.service.JobTtlPolicyService;
import com.tss.platform.service.ModelCachePolicyService;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingRunSpec;
import com.tss.platform.training.plan.TrainingRunSpecCodec;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/** Renders a task-specific Job from the immutable RunSpec, never from a hard-coded training profile. */
@Component
public class KubernetesJobManifestBuilder {

    private final TrainingKubernetesProperties properties;
    private final TrainingRunSpecCodec runSpecCodec;
    private final TrainingRuntimeImageService runtimeImageService;
    private final GpuDeviceClaimManifestBuilder gpuClaimBuilder;
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesJobManifestBuilder.class);
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LINUX_ABSOLUTE_PATH = Pattern.compile("/[A-Za-z0-9._/-]+");
    private static final Pattern RUNTIME_CLASS_NAME = Pattern.compile("[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?");

    private InferenceModelCacheProperties modelCacheProperties = new InferenceModelCacheProperties();
    private JobTtlPolicyService jobTtlPolicyService;
    private ModelCachePolicyService modelCachePolicyService;

    @Value("${inference.kubernetes.worker-image:tss-inference-worker:local}")
    private String cacheWorkerImage = "tss-inference-worker:local";

    @Value("${inference.kubernetes.worker-image-pull-policy:IfNotPresent}")
    private String cacheWorkerImagePullPolicy = "IfNotPresent";


    public KubernetesJobManifestBuilder(
            TrainingKubernetesProperties properties,
            TrainingRunSpecCodec runSpecCodec,
            TrainingRuntimeImageService runtimeImageService
    ) {
        this.properties = properties;
        this.runSpecCodec = runSpecCodec;
        this.runtimeImageService = runtimeImageService;
        this.gpuClaimBuilder = new GpuDeviceClaimManifestBuilder(properties);
    }
    @Autowired
    void setModelCacheProperties(InferenceModelCacheProperties modelCacheProperties) {
        this.modelCacheProperties = modelCacheProperties;
    }

    @Autowired
    void setJobTtlPolicyService(JobTtlPolicyService jobTtlPolicyService) {
        this.jobTtlPolicyService = jobTtlPolicyService;
    }

    @Autowired
    void setModelCachePolicyService(ModelCachePolicyService modelCachePolicyService) {
        this.modelCachePolicyService = modelCachePolicyService;
    }


    public String buildJobYaml(
            TrainingExperimentVersion task,
            String minioAccessKey,
            String minioSecretKey,
            String minioBucket,
            String targetNodeName
    ) {
        TrainingRunSpec runSpec = runSpecCodec.decode(task);
        boolean gpuRuntimeRequired = validateDeviceResources(runSpec);
        boolean exactGpu = gpuRuntimeRequired
                && runSpec.resources().gpuUuid() != null
                && !runSpec.resources().gpuUuid().isBlank();
        if (exactGpu && targetNodeName != null
                && runSpec.resources().gpuNodeName() != null
                && !targetNodeName.equals(runSpec.resources().gpuNodeName())) {
            throw new IllegalStateException("scheduled node does not match the selected GPU node");
        }
        String jobName = KubernetesJobNaming.jobNameForTraining(task.getId());
        String trainingLabel = KubernetesJobNaming.sanitizeLabelValue(task.getId());
        long deadlineSeconds = Math.min(properties.getJobActiveDeadlineSeconds(), runSpec.security().maxRuntimeSeconds());
        String image = runtimeImageService.resolveImage(runSpec);
        String callbackUrl = properties.getBackendServiceUrl() + "/api/internal/training/result?id=" + task.getId();
        ModelCacheSpec modelCache = modelCacheSpec(
                runSpec.inputs() == null
                        ? null
                        : runSpec.inputs().model(),
                minioAccessKey,
                minioSecretKey,
                minioBucket
        );
        if (modelCache.enabled() && (targetNodeName == null || targetNodeName.isBlank())) {
            throw new IllegalStateException("model cache requires an assigned cache-ready node");
        }


        StringBuilder yaml = new StringBuilder(8192 + task.getRunSpecJson().length());
        line(yaml, 0, "apiVersion: batch/v1");
        line(yaml, 0, "kind: Job");
        line(yaml, 0, "metadata:");
        line(yaml, 2, "name: " + jobName);
        line(yaml, 2, "namespace: " + properties.getNamespace());
        line(yaml, 2, "labels:");
        line(yaml, 4, "app.kubernetes.io/name: tss-training-job");
        line(yaml, 4, "app.kubernetes.io/part-of: tss-platform");
        line(yaml, 4, "tss.ai/training-id: " + quote(trainingLabel));
        line(yaml, 0, "spec:");
        line(yaml, 2, "backoffLimit: 0");
        // 精确选卡可能长时间等卡；运行时限从容器真正启动后由 timeout 计算。
        if (!exactGpu) {
            line(yaml, 2, "activeDeadlineSeconds: " + deadlineSeconds);
        }
        line(yaml, 2, "ttlSecondsAfterFinished: " + effectiveJobTtlSecondsAfterFinished());
        line(yaml, 2, "template:");
        line(yaml, 4, "metadata:");
        line(yaml, 6, "labels:");
        line(yaml, 8, "app.kubernetes.io/name: tss-training-job");
        line(yaml, 8, "tss.ai/training-id: " + quote(trainingLabel));
        line(yaml, 4, "spec:");
        if (gpuRuntimeRequired) {
            line(yaml, 6, "runtimeClassName: " + requireGpuRuntimeClassName());
        }
        line(yaml, 6, "serviceAccountName: " + properties.getServiceAccount());
        line(yaml, 6, "automountServiceAccountToken: false");
        line(yaml, 6, "restartPolicy: Never");
        // DRA 依赖调度器分配声明，精确选卡不能用 nodeName 绕过调度器。
        if (exactGpu && targetNodeName != null && !targetNodeName.isBlank()) {
            line(yaml, 6, "nodeSelector:");
            line(yaml, 8, "kubernetes.io/hostname: " + quote(targetNodeName));
        } else if (targetNodeName != null && !targetNodeName.isBlank()) {
            line(yaml, 6, "nodeName: " + targetNodeName);
        } else {
            appendNodeSelector(yaml, runSpec.resources().nodeSelector());
        }
        if (exactGpu) {
            appendExactGpuTolerations(yaml);
            line(yaml, 6, "resourceClaims:");
            line(yaml, 8, "- name: gpu");
            line(yaml, 10, "resourceClaimTemplateName: "
                    + gpuClaimBuilder.claimTemplateName(runSpec.resources().gpuUuid()));
        }
        line(yaml, 6, "securityContext:");
        line(yaml, 8, "runAsNonRoot: " + runSpec.security().runAsNonRoot());
        line(yaml, 8, "runAsUser: 10001");
        line(yaml, 8, "runAsGroup: 10001");
        line(yaml, 8, "fsGroup: 10001");
        line(yaml, 8, "seccompProfile:");
        line(yaml, 10, "type: RuntimeDefault");
        line(yaml, 6, "volumes:");
        line(yaml, 8, "- name: workspace");
        line(yaml, 10, "emptyDir:");
        line(yaml, 12, "sizeLimit: " + quote(runSpec.resources().ephemeralStorageLimit()));
        if (modelCache.enabled()) {
            appendCacheVolume(yaml, targetNodeName);
            appendCacheInitializer(yaml, modelCache, minioAccessKey, minioSecretKey, minioBucket);
        }
        line(yaml, 6, "containers:");
        line(yaml, 8, "- name: training-worker");
        if (exactGpu) {
            appendRuntimeTimeoutCommand(yaml, deadlineSeconds, "/app/train.py");
        }
        line(yaml, 10, "image: " + quote(image));
        line(yaml, 10, "imagePullPolicy: " + runSpec.runtime().imagePullPolicy());
        // The container runtime creates a missing workingDir before the worker
        // process starts. Pointing it at /workspace/job/code therefore creates
        // that directory as root inside the EmptyDir, while the worker runs as
        // UID 10001 and cannot extract the uploaded code. The worker itself
        // creates the code directory and later starts user code with CODE_DIR
        // as its subprocess cwd.
        line(yaml, 10, "workingDir: /workspace/job");
        line(yaml, 10, "volumeMounts:");
        line(yaml, 12, "- name: workspace");
        line(yaml, 14, "mountPath: /workspace/job");
        if (modelCache.enabled()) {
            appendCacheMainMounts(yaml, modelCache);
        }
        line(yaml, 10, "env:");
        appendEnv(yaml, "TRAINING_ID", task.getId());
        appendEnv(yaml, "RUN_SPEC_JSON", task.getRunSpecJson());
        appendEnv(yaml, "RUN_SPEC_SHA256", task.getRunSpecSha256());
        appendEnv(yaml, "MINIO_ENDPOINT", properties.getMinioServiceUrl());
        appendEnv(yaml, "MINIO_ACCESS_KEY", minioAccessKey);
        appendEnv(yaml, "MINIO_SECRET_KEY", minioSecretKey);
        appendEnv(yaml, "MINIO_BUCKET", minioBucket);
        appendEnv(yaml, "MLFLOW_TRACKING_URI", properties.getMlflowServiceUrl());
        appendEnv(yaml, "MLFLOW_EXPERIMENT_NAME", properties.getMlflowExperimentName());
        appendEnv(yaml, "BACKEND_CALLBACK_URL", callbackUrl);
        appendEnv(yaml, "INTERNAL_CALLBACK_TOKEN", properties.requireInternalCallbackToken());
        if (runSpec.resources().gpuMemoryLimitMiB() != null) {
            appendEnv(yaml, "TSS_GPU_MEMORY_LIMIT_MIB", runSpec.resources().gpuMemoryLimitMiB().toString());
        }
        if (exactGpu) {
            appendEnv(yaml, "TSS_SELECTED_GPU_UUID", runSpec.resources().gpuUuid());
            appendEnv(yaml, "TSS_HOST_GPU_INDEX", runSpec.resources().gpuHostIndex());
        }
        if (modelCache.enabled()) {
            appendEnv(yaml, "MODEL_CACHE_ENABLED", "true");
            appendEnv(yaml, "MODEL_CACHE_LOCK_PATH", "/var/run/tss-model-cache/model.lock");
        }
        line(yaml, 10, "resources:");
        line(yaml, 12, "requests:");
        line(yaml, 14, "cpu: " + quote(runSpec.resources().cpuRequest()));
        line(yaml, 14, "memory: " + quote(runSpec.resources().memoryRequest()));
        line(yaml, 12, "limits:");
        line(yaml, 14, "cpu: " + quote(runSpec.resources().cpuLimit()));
        line(yaml, 14, "memory: " + quote(runSpec.resources().memoryLimit()));
        line(yaml, 14, "ephemeral-storage: " + quote(runSpec.resources().ephemeralStorageLimit()));
        if (gpuRuntimeRequired && !exactGpu) {
            line(yaml, 14, "nvidia.com/gpu: " + quote(runSpec.resources().gpuCount().toString()));
        }
        if (exactGpu) {
            line(yaml, 12, "claims:");
            line(yaml, 14, "- name: gpu");
        }
        line(yaml, 10, "securityContext:");
        line(yaml, 12, "allowPrivilegeEscalation: " + runSpec.security().allowPrivilegeEscalation());
        line(yaml, 12, "capabilities:");
        line(yaml, 14, "drop:");
        line(yaml, 16, "- ALL");
        String jobYaml = yaml.toString();
        return exactGpu
                ? gpuClaimBuilder.prependClaimTemplate(jobYaml, runSpec.resources().gpuUuid())
                : jobYaml;
    }

    private void appendRuntimeTimeoutCommand(StringBuilder yaml, long deadlineSeconds, String workerPath) {
        line(yaml, 10, "command:");
        line(yaml, 12, "- /usr/bin/timeout");
        line(yaml, 12, "- --signal=TERM");
        line(yaml, 12, "- --kill-after=30s");
        line(yaml, 12, "- " + deadlineSeconds + "s");
        line(yaml, 12, "- python");
        line(yaml, 12, "- " + workerPath);
    }

    private boolean validateDeviceResources(TrainingRunSpec runSpec) {
        if (runSpec.runtime() == null || runSpec.runtime().deviceType() == null) {
            throw new IllegalStateException("RunSpec runtime deviceType is required");
        }
        if (runSpec.resources() == null || runSpec.resources().gpuCount() == null) {
            throw new IllegalStateException("RunSpec resources gpuCount is required");
        }
        int gpuCount = runSpec.resources().gpuCount();
        if (gpuCount < 0) {
            throw new IllegalStateException("RunSpec gpuCount cannot be negative");
        }
        if (runSpec.runtime().deviceType() == TrainingPlanDefinition.DeviceType.NVIDIA_GPU) {
            if (gpuCount != 1) {
                throw new IllegalStateException("NVIDIA_GPU runtime must request exactly one GPU");
            }
            return true;
        }
        if (gpuCount != 0) {
            throw new IllegalStateException("CPU runtime cannot request a GPU");
        }
        return false;
    }

    private String requireGpuRuntimeClassName() {
        String value = properties.getGpuRuntimeClassName();
        String normalized = value == null ? "" : value.trim();
        if (!RUNTIME_CLASS_NAME.matcher(normalized).matches()) {
            throw new IllegalStateException("training.kubernetes.gpu-runtime-class-name is invalid");
        }
        return normalized;
    }

    private int effectiveJobTtlSecondsAfterFinished() {
        return jobTtlPolicyService == null
                ? properties.getJobTtlSecondsAfterFinished()
                : jobTtlPolicyService.currentJobTtlSecondsAfterFinished();
    }

    private ModelCacheSpec modelCacheSpec(
            TrainingRunSpec.InputArtifact model,
            String minioAccessKey,
            String minioSecretKey,
            String minioBucket
    ) {
        if (!modelCacheProperties.isEnabled()) {
            return ModelCacheSpec.disabled();
        }
        requireCachePath(
                "inference.kubernetes.model-cache.node-path",
                modelCacheProperties.getNodePath()
        );
        String mountPath = requireCachePath(
                "inference.kubernetes.model-cache.mount-path",
                modelCacheProperties.getMountPath()
        );
        ModelCachePolicy policy = effectiveModelCachePolicy();
        if (policy.maxBytes() <= 0) {
            throw new IllegalStateException("inference.kubernetes.model-cache.max-bytes must be positive");
        }
        if (policy.minFreeBytes() < 0) {
            throw new IllegalStateException("inference.kubernetes.model-cache.min-free-bytes must not be negative");
        }
        if (cacheWorkerImage == null || cacheWorkerImage.isBlank()) {
            throw new IllegalStateException("inference.kubernetes.worker-image must not be blank");
        }

        String digest = model == null || model.sha256() == null
                ? ""
                : model.sha256().trim().toLowerCase(Locale.ROOT);
        Long sizeBytes = model == null ? null : model.sizeBytes();
        String objectName = model == null || model.objectName() == null ? "" : model.objectName().trim();
        if (!SHA256_PATTERN.matcher(digest).matches()
                || sizeBytes == null || sizeBytes <= 0 || objectName.isBlank()) {
            LOGGER.warn("Bypass training model cache because RunSpec model metadata is incomplete");
            return ModelCacheSpec.disabled();
        }
        if (sizeBytes > policy.maxBytes()) {
            LOGGER.warn("Bypass training model cache because artifact size {} exceeds cache limit {}",
                    sizeBytes, policy.maxBytes());
            return ModelCacheSpec.disabled();
        }

        boolean archive = Boolean.TRUE.equals(model.archive());
        boolean zipObject = objectName.toLowerCase(Locale.ROOT).endsWith(".zip");
        String objectFileName = objectName.replace('\\', '/');
        objectFileName = objectFileName.substring(objectFileName.lastIndexOf('/') + 1);
        if (archive != zipObject
                || (!archive && (model.fileName() == null || !model.fileName().equals(objectFileName)))) {
            LOGGER.warn("Bypass training model cache because cached layout would differ from RunSpec materialization");
            return ModelCacheSpec.disabled();
        }

        return new ModelCacheSpec(
                true,
                mountPath,
                digest,
                sizeBytes,
                objectName,
                "entries/" + digest + "/data",
                "locks/" + digest + ".lock",
                policy.maxBytes(),
                policy.minFreeBytes()
        );
    }

    private void appendCacheVolume(StringBuilder yaml, String targetNodeName) {
        line(yaml, 8, "- name: model-cache");
        line(yaml, 10, "persistentVolumeClaim:");
        line(yaml, 12, "claimName: "
                + quote(ModelCacheVolumeNaming.claimNameForNode(targetNodeName)));
    }

    private void appendCacheInitializer(
            StringBuilder yaml,
            ModelCacheSpec cache,
            String minioAccessKey,
            String minioSecretKey,
            String minioBucket
    ) {
        line(yaml, 6, "initContainers:");
        line(yaml, 8, "- name: model-cache-initializer");
        line(yaml, 10, "image: " + quote(cacheWorkerImage));
        line(yaml, 10, "imagePullPolicy: " + cacheWorkerImagePullPolicy);
        line(yaml, 10, "volumeMounts:");
        line(yaml, 12, "- name: model-cache");
        line(yaml, 14, "mountPath: " + quote(cache.mountPath()));
        line(yaml, 10, "env:");
        appendInitEnv(yaml, "INFERENCE_WORKER_MODE", "prepare-model-cache");
        appendInitEnv(yaml, "MODEL_CACHE_ROOT", cache.mountPath());
        appendInitEnv(yaml, "MODEL_CACHE_KEY", cache.digest());
        appendInitEnv(yaml, "MODEL_EXPECTED_SHA256", cache.digest());
        appendInitEnv(yaml, "MODEL_EXPECTED_SIZE_BYTES", String.valueOf(cache.sizeBytes()));
        appendInitEnv(yaml, "MODEL_CACHE_MAX_BYTES", String.valueOf(cache.maxBytes()));
        appendInitEnv(yaml, "MODEL_CACHE_MIN_FREE_BYTES", String.valueOf(cache.minFreeBytes()));
        appendInitEnv(yaml, "MODEL_STORAGE_PATH", cache.objectName());
        appendInitEnv(yaml, "MINIO_ENDPOINT", properties.getMinioServiceUrl());
        appendInitEnv(yaml, "MINIO_ACCESS_KEY", minioAccessKey);
        appendInitEnv(yaml, "MINIO_SECRET_KEY", minioSecretKey);
        appendInitEnv(yaml, "MINIO_BUCKET", minioBucket);
        line(yaml, 10, "securityContext:");
        line(yaml, 12, "allowPrivilegeEscalation: false");
        line(yaml, 12, "capabilities:");
        line(yaml, 14, "drop:");
        line(yaml, 16, "- ALL");
    }

    private void appendInitEnv(StringBuilder yaml, String name, String value) {
        line(yaml, 12, "- name: " + name);
        line(yaml, 14, "value: " + quote(value));
    }

    private void appendCacheMainMounts(StringBuilder yaml, ModelCacheSpec cache) {
        line(yaml, 12, "- name: model-cache");
        line(yaml, 14, "mountPath: /workspace/job/model");
        line(yaml, 14, "subPath: " + quote(cache.entrySubPath()));
        line(yaml, 14, "readOnly: true");
        line(yaml, 12, "- name: model-cache");
        line(yaml, 14, "mountPath: /var/run/tss-model-cache/model.lock");
        line(yaml, 14, "subPath: " + quote(cache.lockSubPath()));
        line(yaml, 14, "readOnly: true");
    }

    private String requireCachePath(String propertyName, String rawValue) {
        String path = rawValue == null ? "" : rawValue.trim();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!LINUX_ABSOLUTE_PATH.matcher(path).matches() || path.contains("//")) {
            throw new IllegalStateException(propertyName + " must be a safe absolute Linux path");
        }
        for (String segment : path.substring(1).split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalStateException(propertyName + " must not contain dot segments");
            }
        }
        return path;
    }

    private ModelCachePolicy effectiveModelCachePolicy() {
        if (modelCachePolicyService != null) {
            return modelCachePolicyService.currentPolicy();
        }
        return new ModelCachePolicy(
                modelCacheProperties.getMaxBytes(),
                modelCacheProperties.getMinFreeBytes(),
                modelCacheProperties.getRuntimeReserveBytes(),
                null
        );
    }

    private record ModelCacheSpec(
            boolean enabled,
            String mountPath,
            String digest,
            long sizeBytes,
            String objectName,
            String entrySubPath,
            String lockSubPath,
            long maxBytes,
            long minFreeBytes
    ) {
        private static ModelCacheSpec disabled() {
            return new ModelCacheSpec(false, "", "", 0, "", "", "", 0, 0);
        }
    }

    private void appendNodeSelector(StringBuilder yaml, Map<String, String> nodeSelector) {
        if (nodeSelector == null || nodeSelector.isEmpty()) {
            return;
        }
        line(yaml, 6, "nodeSelector:");
        nodeSelector.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> line(yaml, 8, quote(entry.getKey()) + ": " + quote(entry.getValue())));
    }

    private void appendExactGpuTolerations(StringBuilder yaml) {
        line(yaml, 6, "tolerations:");
        line(yaml, 8, "- key: node-role.kubernetes.io/control-plane");
        line(yaml, 10, "operator: Exists");
        line(yaml, 10, "effect: NoSchedule");
        line(yaml, 8, "- key: nvidia.com/gpu");
        line(yaml, 10, "operator: Exists");
        line(yaml, 10, "effect: NoSchedule");
    }

    private void appendEnv(StringBuilder yaml, String name, String value) {
        line(yaml, 12, "- name: " + name);
        line(yaml, 14, "value: " + quote(value));
    }

    private void line(StringBuilder yaml, int spaces, String value) {
        yaml.append(" ".repeat(spaces)).append(value).append('\n');
    }

    private String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }
}
