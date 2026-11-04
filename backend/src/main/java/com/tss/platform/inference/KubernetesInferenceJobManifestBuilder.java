package com.tss.platform.inference;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.InferenceScriptVersion;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.dto.InferenceResourceProfileDto;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.modelcache.ModelCacheVolumeNaming;
import com.tss.platform.service.JobTtlPolicyService;
import com.tss.platform.service.ModelCachePolicyService;
import com.tss.platform.training.GpuDeviceClaimManifestBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class KubernetesInferenceJobManifestBuilder {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(KubernetesInferenceJobManifestBuilder.class);
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LINUX_ABSOLUTE_PATH = Pattern.compile("/[A-Za-z0-9._/-]+");

    private final TrainingKubernetesProperties properties;
    private final InferenceModelCacheProperties modelCacheProperties;
    private final GpuDeviceClaimManifestBuilder gpuClaimBuilder;
    private JobTtlPolicyService jobTtlPolicyService;
    private ModelCachePolicyService modelCachePolicyService;

    @Value("${inference.kubernetes.worker-image:tss-inference-worker:local}")
    private String workerImage;

    @Value("${inference.kubernetes.worker-image-pull-policy:IfNotPresent}")
    private String workerImagePullPolicy;

    @Value("${inference.kubernetes.gpu-worker-image}")
    private String gpuWorkerImage;

    private final InferenceResourceProfileService resourceProfileService;

    public KubernetesInferenceJobManifestBuilder(
            TrainingKubernetesProperties properties,
            InferenceModelCacheProperties modelCacheProperties,
            InferenceResourceProfileService resourceProfileService
    ) {
        this.properties = properties;
        this.modelCacheProperties = modelCacheProperties;
        this.resourceProfileService = resourceProfileService;
        this.gpuClaimBuilder = new GpuDeviceClaimManifestBuilder(properties);
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
            InferenceTask task,
            ModelVersion modelVersion,
            InferenceScriptVersion scriptVersion,
            DatasetVersion datasetVersion,
            String minioAccessKey,
            String minioSecretKey,
            String minioBucket
    ) {
        return buildJobYaml(
                task, modelVersion, scriptVersion, datasetVersion,
                minioAccessKey, minioSecretKey, minioBucket, null
        );
    }

    public String buildJobYaml(
            InferenceTask task,
            ModelVersion modelVersion,
            InferenceScriptVersion scriptVersion,
            DatasetVersion datasetVersion,
            String minioAccessKey,
            String minioSecretKey,
            String minioBucket,
            String targetNodeName
    ) {
        int attempt = currentAttempt(task);
        String jobName = KubernetesInferenceJobNaming.jobNameForInference(task.getId(), attempt);
        String inferenceLabel = KubernetesInferenceJobNaming.sanitizeLabelValue(task.getId());
        String callbackUrl = properties.getBackendServiceUrl()
                + "/api/internal/inference/result?id=" + task.getId()
                + "&attempt=" + attempt;
        ModelCacheSpec modelCache = modelCacheSpec(
                modelVersion,
                minioAccessKey,
                minioSecretKey,
                minioBucket,
                targetNodeName
        );
        InferenceResourceProfileDto resourceProfile =
                resourceProfileService.resolveForExecution(task.getResourceProfileId());
        boolean gpu = "NVIDIA_GPU".equals(resourceProfile.deviceType());
        if (gpu && (task.getGpuUuid() == null || task.getGpuUuid().isBlank())) {
            throw new IllegalStateException("GPU inference task has no selected GPU UUID");
        }
        if (!gpu && task.getGpuUuid() != null) {
            throw new IllegalStateException("CPU inference task cannot contain a GPU UUID");
        }
        if (gpu && targetNodeName != null && task.getGpuNodeName() != null
                && !targetNodeName.equals(task.getGpuNodeName())) {
            throw new IllegalStateException("scheduled node does not match the selected GPU node");
        }

        String jobYaml = """
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/name: tss-inference-job
                    app.kubernetes.io/part-of: tss-platform
                    tss.ai/inference-id: "%s"
                spec:
                  backoffLimit: 0
                %s
                  ttlSecondsAfterFinished: %d
                  template:
                    metadata:
                      labels:
                        app.kubernetes.io/name: tss-inference-job
                        tss.ai/inference-id: "%s"
                    spec:
                %s
                      serviceAccountName: %s
                      automountServiceAccountToken: false
                      restartPolicy: Never
                %s
                      securityContext:
                        runAsNonRoot: true
                        runAsUser: 10001
                        runAsGroup: 10001
                        fsGroup: 10001
                        seccompProfile:
                          type: RuntimeDefault
                      volumes:
                        - name: workspace
                          emptyDir: {}
                %s%s      containers:
                        - name: inference-worker
                %s
                          image: %s
                          imagePullPolicy: %s
                          workingDir: /workspace/job
                          volumeMounts:
                            - name: workspace
                              mountPath: /workspace/job
                %s          env:
                            - name: INFERENCE_TASK_ID
                              value: "%s"
                            - name: INFERENCE_ATTEMPT
                              value: "%d"
                            - name: MODEL_VERSION_ID
                              value: "%s"
                            - name: SCRIPT_VERSION_ID
                              value: "%s"
                            - name: INPUT_MODE
                              value: "%s"
                            - name: DATASET_VERSION_ID
                              value: "%s"
                            - name: MODEL_STORAGE_PATH
                              value: "%s"
                            - name: SCRIPT_STORAGE_PATH
                              value: "%s"
                            - name: DATASET_STORAGE_PATH
                              value: "%s"
                            - name: INPUT_OBJECT_NAME
                              value: "%s"
                            - name: SCRIPT_ENTRY_FILE
                              value: "%s"
                            - name: PARAMS_JSON
                              value: "%s"
                            - name: MINIO_ENDPOINT
                              value: "%s"
                            - name: MINIO_ACCESS_KEY
                              value: "%s"
                            - name: MINIO_SECRET_KEY
                              value: "%s"
                            - name: MINIO_BUCKET
                              value: "%s"
                            - name: BACKEND_CALLBACK_URL
                              value: "%s"
                            - name: INTERNAL_CALLBACK_TOKEN
                              value: "%s"
                            - name: OUTPUT_OBJECT_PREFIX
                              value: "%s"
                %s%s          resources:
                            requests:
                              cpu: "%s"
                              memory: "%s"
                              ephemeral-storage: "%s"
                            limits:
                              cpu: "%s"
                              memory: "%s"
                              ephemeral-storage: "%s"
                %s
                          securityContext:
                            allowPrivilegeEscalation: false
                            capabilities:
                              drop:
                                - ALL
                """.formatted(
                jobName,
                properties.getNamespace(),
                inferenceLabel,
                gpu ? "" : "  activeDeadlineSeconds: "
                        + properties.getJobActiveDeadlineSeconds(),
                effectiveJobTtlSecondsAfterFinished(),
                inferenceLabel,
                gpu ? gpuPodSpecYaml(task) : "",
                properties.getServiceAccount(),
                placementYaml(targetNodeName, modelCache, gpu),
                modelCache.volumeYaml(),
                modelCache.initContainerYaml(),
                gpu ? runtimeTimeoutCommandYaml(properties.getJobActiveDeadlineSeconds()) : "",
                gpu ? gpuWorkerImage : workerImage,
                workerImagePullPolicy,
                modelCache.mainVolumeMountYaml(),
                escapeYaml(task.getId()),
                attempt,
                escapeYaml(task.getModelVersionId()),
                escapeYaml(task.getScriptVersionId()),
                escapeYaml(task.getInputMode()),
                escapeYaml(task.getDatasetVersionId() == null ? "" : task.getDatasetVersionId()),
                escapeYaml(modelVersion.getStoragePath()),
                escapeYaml(scriptVersion.getStoragePath()),
                escapeYaml(datasetVersion == null ? "" : datasetVersion.getStoragePath()),
                escapeYaml(task.getInputObjectName() == null ? "" : task.getInputObjectName()),
                escapeYaml(scriptVersion.getEntryFile()),
                escapeYaml((task.getParamsJson() == null ? "{}" : task.getParamsJson()).replace("\n", " ").replace("\r", " ")),
                properties.getMinioServiceUrl(),
                escapeYaml(minioAccessKey),
                escapeYaml(minioSecretKey),
                escapeYaml(minioBucket),
                escapeYaml(callbackUrl),
                escapeYaml(properties.requireInternalCallbackToken()),
                escapeYaml(outputObjectPrefix(task)),
                modelCache.mainEnvYaml(),
                gpu ? gpuEnvYaml(task) : "",
                resourceProfile.cpuRequest(),
                resourceProfile.memoryRequest(),
                resourceProfile.ephemeralStorageRequest(),
                resourceProfile.cpuLimit(),
                resourceProfile.memoryLimit(),
                resourceProfile.ephemeralStorageLimit(),
                gpu ? gpuContainerClaimYaml() : ""
        );
        return gpu ? gpuClaimBuilder.prependClaimTemplate(jobYaml, task.getGpuUuid()) : jobYaml;
    }

    private String gpuPodSpecYaml(InferenceTask task) {
        return "      runtimeClassName: " + properties.getGpuRuntimeClassName() + "\n"
                + "      resourceClaims:\n"
                + "        - name: gpu\n"
                + "          resourceClaimTemplateName: "
                + gpuClaimBuilder.claimTemplateName(task.getGpuUuid());
    }

    private String gpuEnvYaml(InferenceTask task) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("            - name: TSS_SELECTED_GPU_UUID\n")
                .append("              value: \"").append(escapeYaml(task.getGpuUuid())).append("\"\n")
                .append("            - name: TSS_HOST_GPU_INDEX\n")
                .append("              value: \"").append(escapeYaml(task.getGpuHostIndex())).append("\"\n");
        if (task.getGpuMemoryLimitMiB() != null) {
            yaml.append("            - name: TSS_GPU_MEMORY_LIMIT_MIB\n")
                    .append("              value: \"").append(task.getGpuMemoryLimitMiB()).append("\"\n");
        }
        return yaml.toString();
    }

    private String gpuContainerClaimYaml() {
        return "            claims:\n              - name: gpu";
    }

    private String runtimeTimeoutCommandYaml(int deadlineSeconds) {
        return "          command:\n"
                + "            - /usr/bin/timeout\n"
                + "            - --signal=TERM\n"
                + "            - --kill-after=30s\n"
                + "            - " + deadlineSeconds + "s\n"
                + "            - python\n"
                + "            - /app/infer_worker.py";
    }

    private int effectiveJobTtlSecondsAfterFinished() {
        return jobTtlPolicyService == null
                ? properties.getJobTtlSecondsAfterFinished()
                : jobTtlPolicyService.currentJobTtlSecondsAfterFinished();
    }

    private ModelCacheSpec modelCacheSpec(
            ModelVersion modelVersion,
            String minioAccessKey,
            String minioSecretKey,
            String minioBucket,
            String targetNodeName
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

        String digest = modelVersion == null || modelVersion.getArtifactAttestedSha256() == null
                ? ""
                : modelVersion.getArtifactAttestedSha256().trim().toLowerCase(Locale.ROOT);
        Long sizeBytes = modelVersion == null ? null : modelVersion.getSizeBytes();
        if (!SHA256_PATTERN.matcher(digest).matches() || sizeBytes == null || sizeBytes <= 0) {
            LOGGER.warn(
                    "Bypass model cache because model version {} has no attested SHA-256 or positive size",
                    modelVersion == null ? null : modelVersion.getId()
            );
            return ModelCacheSpec.disabled();
        }
        if (sizeBytes > policy.maxBytes()) {
            LOGGER.warn(
                    "Bypass model cache for model version {} because artifact size {} exceeds cache limit {}",
                    modelVersion.getId(),
                    sizeBytes,
                    policy.maxBytes()
            );
            return ModelCacheSpec.disabled();
        }
        String claimName = ModelCacheVolumeNaming.claimNameForNode(targetNodeName);

        String entrySubPath = "entries/" + digest + "/data";
        String lockSubPath = "locks/" + digest + ".lock";
        String volumeYaml = String.join(
                "\n",
                "        - name: model-cache",
                "          persistentVolumeClaim:",
                "            claimName: \"" + escapeYaml(claimName) + "\""
        ) + "\n";
        String initContainerYaml = String.join(
                "\n",
                "      initContainers:",
                "        - name: model-cache-initializer",
                "          image: " + workerImage,
                "          imagePullPolicy: " + workerImagePullPolicy,
                "          workingDir: /workspace/job",
                "          volumeMounts:",
                "            - name: model-cache",
                "              mountPath: \"" + escapeYaml(mountPath) + "\"",
                "          env:",
                "            - name: INFERENCE_WORKER_MODE",
                "              value: \"prepare-model-cache\"",
                "            - name: MODEL_CACHE_ROOT",
                "              value: \"" + escapeYaml(mountPath) + "\"",
                "            - name: MODEL_CACHE_KEY",
                "              value: \"" + digest + "\"",
                "            - name: MODEL_EXPECTED_SHA256",
                "              value: \"" + digest + "\"",
                "            - name: MODEL_EXPECTED_SIZE_BYTES",
                "              value: \"" + sizeBytes + "\"",
                "            - name: MODEL_CACHE_MAX_BYTES",
                "              value: \"" + policy.maxBytes() + "\"",
                "            - name: MODEL_CACHE_MIN_FREE_BYTES",
                "              value: \"" + policy.minFreeBytes() + "\"",
                "            - name: MODEL_STORAGE_PATH",
                "              value: \"" + escapeYaml(modelVersion.getStoragePath()) + "\"",
                "            - name: MINIO_ENDPOINT",
                "              value: \"" + escapeYaml(properties.getMinioServiceUrl()) + "\"",
                "            - name: MINIO_ACCESS_KEY",
                "              value: \"" + escapeYaml(minioAccessKey) + "\"",
                "            - name: MINIO_SECRET_KEY",
                "              value: \"" + escapeYaml(minioSecretKey) + "\"",
                "            - name: MINIO_BUCKET",
                "              value: \"" + escapeYaml(minioBucket) + "\"",
                "          securityContext:",
                "            allowPrivilegeEscalation: false",
                "            capabilities:",
                "              drop:",
                "                - ALL"
        ) + "\n";
        String mainVolumeMountYaml = String.join(
                "\n",
                "            - name: model-cache",
                "              mountPath: /workspace/job/model",
                "              subPath: \"" + entrySubPath + "\"",
                "              readOnly: true",
                "            - name: model-cache",
                "              mountPath: /var/run/tss-model-cache/model.lock",
                "              subPath: \"" + lockSubPath + "\"",
                "              readOnly: true"
        ) + "\n";
        String mainEnvYaml = String.join(
                "\n",
                "            - name: MODEL_CACHE_ENABLED",
                "              value: \"true\"",
                "            - name: MODEL_CACHE_LOCK_PATH",
                "              value: \"/var/run/tss-model-cache/model.lock\""
        ) + "\n";
        return new ModelCacheSpec(
                volumeYaml,
                initContainerYaml,
                mainVolumeMountYaml,
                mainEnvYaml
        );
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

    private String placementYaml(String targetNodeName, ModelCacheSpec modelCache, boolean exactGpu) {
        // DRA 依赖调度器分配声明，精确选卡不能用 nodeName 绕过调度器。
        if (exactGpu) {
            StringBuilder yaml = new StringBuilder();
            if (targetNodeName != null && !targetNodeName.isBlank()) {
                yaml.append("      nodeSelector:\n")
                        .append("        kubernetes.io/hostname: \"")
                        .append(escapeYaml(targetNodeName.trim()))
                        .append("\"\n");
            }
            yaml.append("      tolerations:\n")
                    .append("        - key: node-role.kubernetes.io/control-plane\n")
                    .append("          operator: Exists\n")
                    .append("          effect: NoSchedule\n")
                    .append("        - key: nvidia.com/gpu\n")
                    .append("          operator: Exists\n")
                    .append("          effect: NoSchedule\n");
            return yaml.toString();
        }
        if (targetNodeName != null && !targetNodeName.isBlank()) {
            return "      nodeName: \"" + escapeYaml(targetNodeName.trim()) + "\"\n";
        }
        StringBuilder yaml = new StringBuilder("      nodeSelector:\n")
                .append("        tss.ai/node-pool: cpu\n");
        if (!modelCache.volumeYaml().isEmpty()) {
            yaml.append("        tss.ai/model-cache-ready: \"true\"\n");
        }
        return yaml.toString();
    }

    private record ModelCacheSpec(
            String volumeYaml,
            String initContainerYaml,
            String mainVolumeMountYaml,
            String mainEnvYaml
    ) {

        private static ModelCacheSpec disabled() {
            return new ModelCacheSpec("", "", "", "");
        }
    }

    private String outputObjectPrefix(InferenceTask task) {
        return "users/" + task.getOwnerUserId()
                + "/inference-results/" + task.getId()
                + "/attempt-" + currentAttempt(task);
    }

    private int currentAttempt(InferenceTask task) {
        return task.getCurrentAttempt() == null ? 1 : Math.max(task.getCurrentAttempt(), 1);
    }

    private String escapeYaml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
