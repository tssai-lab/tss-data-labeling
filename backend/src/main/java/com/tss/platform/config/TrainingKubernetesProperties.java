package com.tss.platform.config;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "training.kubernetes")
public class TrainingKubernetesProperties {

    public enum ClientMode {
        KUBECTL,
        FABRIC8
    }

    public static final String PUBLIC_DEVELOPMENT_INTERNAL_CALLBACK_TOKEN =
            "tss-internal-callback-dev";

    /** 是否启用 K8s 训练调度；使用训练方案的任务不可静默回退本地执行。 */
    private boolean enabled = true;

    /** 训练 Job 控制通道；同一进程只会装配一种实现。 */
    private ClientMode clientMode = ClientMode.KUBECTL;

    /** 启动时环境不可用时是否自动创建 kind 集群 */
    private boolean autoCreate = true;

    /** 启动时是否执行连通性 verify Job */
    private boolean verifyOnStartup = true;

    /** 历史兼容配置；TrainingExecutorRouter 对方案任务仍会拒绝回退，不应作为容灾开关。 */
    private boolean fallbackToLocal = true;

    private String clusterName = "tss-training";
    private String namespace = "tss-training";
    private String serviceAccount = "tss-training-worker";

    /** 仅为申请 NVIDIA GPU 的 Pod 指定 RuntimeClass，CPU 任务不依赖 NVIDIA 运行时。 */
    @Pattern(regexp = "^[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?$", message = "must be a valid RuntimeClass name")
    private String gpuRuntimeClassName = "nvidia";

    /** 开启后，GPU 任务必须按 UUID 通过 Kubernetes DRA 申请一张物理卡。 */
    private boolean exactGpuSelectionEnabled = false;

    /** NVIDIA DRA 驱动注册的设备类。 */
    @Pattern(regexp = "^[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?(?:\\.[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?)+$",
            message = "must be a qualified Kubernetes device class name")
    private String gpuDeviceClassName = "gpu.nvidia.com";

    /** 项目根目录相对路径，用于定位脚本与 kubeconfig */
    private String projectRoot = "..";

    private String kubeconfig = "k8s/.kube/config";
    private String kindPath = ".tools/bin/kind";
    private String kubectlPath = ".tools/bin/kubectl";
    private String bootstrapScript = "backend/scripts/k8s/bootstrap-local-kind.sh";
    private String verifyScript = "backend/scripts/k8s/verify-local-kind.sh";

    private String workerImage = "tss-training-worker:local";
    private String workerImagePullPolicy = "IfNotPresent";

    /**
     * Whether a code package's approved requirements.txt may produce a derived
     * Worker image.  This stays opt-in because the backend host must have Docker
     * access and a registry that every Kubernetes node can pull from.
     */
    private boolean runtimeImageBuildEnabled = false;

    /** Repository without a tag, for example registry.example.com/tss/training-worker. */
    private String runtimeImageRepository = "";
    private String runtimeImageDockerPath = "docker";
    private String runtimeImageBuildDirectory = "/tmp/tss-runtime-image-builds";
    private int runtimeImageBuildTimeoutSeconds = 1800;
    private String runtimeImagePipIndexUrl = "";
    private int runtimeImagePipTimeoutSeconds = 120;
    private int runtimeImagePipRetries = 5;

    /** Worker 容器内访问宿主机服务的 K8s Service 名称 */
    private String backendServiceUrl = "http://tss-backend:8080";
    private String minioServiceUrl = "http://tss-minio:9000";
    private String mlflowServiceUrl = "http://tss-mlflow:5000";

    /** K8s Worker 写入 MLflow 的 experiment 名称（可通过 env 覆盖） */
    private String mlflowExperimentName = "TSSAI-K8s-Training";

    private String cpuRequest = "500m";
    private String cpuLimit = "2";
    private String memoryRequest = "512Mi";
    private String memoryLimit = "4Gi";

    /** Job 最长运行时间（秒） */
    private int jobActiveDeadlineSeconds = 3600;

    /** Job 完成后保留时间（秒） */
    private int jobTtlSecondsAfterFinished = 3600;

    /** Worker 回调内部 token（通过环境变量 TRAINING_K8S_INTERNAL_CALLBACK_TOKEN 覆盖） */
    private String internalCallbackToken = "";

    /** Job 状态轮询间隔（毫秒） */
    private long monitorIntervalMs = 30000;

    /** Pod 持续处于不可恢复的启动错误多久后，将业务任务同步为失败。 */
    @Min(0)
    @Max(3600)
    private int podStartupFailureGraceSeconds = 120;

    /** 训练 Job 客户端单次请求超时（秒）。 */
    @Min(1)
    @Max(3600)
    private int clientRequestTimeoutSeconds = 120;

    /** 是否在 K8s Job/Pod 被 TTL 清理前归档脱敏失败现场。 */
    private boolean failureDiagnosticsEnabled = true;

    /** Worker 先回调 failed、Pod 后退出时允许补采失败现场的时间窗口。 */
    private int failureDiagnosticsRetryWindowSeconds = 900;

    /** 失败诊断对象保留天数；简要 errorMessage 不受此配置影响。 */
    private int failureDiagnosticsRetentionDays = 30;

    /** 单份失败诊断最大字节数。 */
    private int failureDiagnosticsMaxBytes = 1_048_576;

    /** 环境初始化超时（秒） */
    private int bootstrapTimeoutSeconds = 600;

    public String requireInternalCallbackToken() {
        String token = normalizedInternalCallbackToken();
        if (token == null) {
            throw new IllegalStateException(
                    "training.kubernetes.internal-callback-token must be configured"
            );
        }
        return token;
    }

    public boolean hasPublicDevelopmentInternalCallbackToken() {
        return PUBLIC_DEVELOPMENT_INTERNAL_CALLBACK_TOKEN.equals(
                normalizedInternalCallbackToken()
        );
    }

    public boolean matchesInternalCallbackToken(String candidate) {
        String configured = normalizedInternalCallbackToken();
        if (configured == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String normalizedInternalCallbackToken() {
        if (internalCallbackToken == null || internalCallbackToken.isBlank()) {
            return null;
        }
        return internalCallbackToken.trim();
    }
}
