package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TrainingEnvironmentService {

    private static final Logger LOG = LoggerFactory.getLogger(TrainingEnvironmentService.class);

    private final TrainingKubernetesProperties properties;
    private final ShellCommandRunner shellCommandRunner;
    private final AtomicReference<TrainingEnvironmentStatus> statusRef = new AtomicReference<>();

    public TrainingEnvironmentService(
            TrainingKubernetesProperties properties,
            ShellCommandRunner shellCommandRunner
    ) {
        this.properties = properties;
        this.shellCommandRunner = shellCommandRunner;
    }

    public TrainingEnvironmentStatus getStatus() {
        TrainingEnvironmentStatus current = statusRef.get();
        if (current == null) {
            return TrainingEnvironmentStatus.disabled("训练环境尚未初始化");
        }
        return current;
    }

    public boolean isKubernetesReady() {
        TrainingEnvironmentStatus status = statusRef.get();
        return status != null && status.isKubernetesReady();
    }

    public Path resolveProjectRoot() {
        Path backendDir = Paths.get("").toAbsolutePath();
        Path configured = Paths.get(properties.getProjectRoot());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        Path fromBackend = backendDir.resolve(configured).normalize();
        if (Files.isDirectory(fromBackend.resolve("backend"))) {
            return fromBackend;
        }
        Path parent = backendDir.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("backend"))) {
            return parent.normalize();
        }
        return fromBackend;
    }

    public Path resolveKubeconfig() {
        Path root = resolveProjectRoot();
        Path configured = Paths.get(properties.getKubeconfig());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return root.resolve(configured).normalize();
    }

    public Path resolveKubectl() {
        return resolveToolPath(properties.getKubectlPath());
    }

    public Path resolveKind() {
        return resolveToolPath(properties.getKindPath());
    }

    public Path resolveBootstrapScript() {
        return resolveProjectRoot().resolve(Paths.get(properties.getBootstrapScript())).normalize();
    }

    public Path resolveVerifyScript() {
        return resolveProjectRoot().resolve(Paths.get(properties.getVerifyScript())).normalize();
    }

    private Path resolveToolPath(String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return resolveProjectRoot().resolve(path).normalize();
    }

    public TrainingEnvironmentStatus initializeOnStartup() {
        TrainingEnvironmentStatus status = new TrainingEnvironmentStatus();
        status.setKubernetesEnabled(properties.isEnabled());
        status.setFallbackToLocal(properties.isFallbackToLocal());
        status.setClusterName(properties.getClusterName());
        status.setNamespace(properties.getNamespace());
        status.setWorkerImage(properties.getWorkerImage());
        status.setKubeconfig(resolveKubeconfig().toString());
        status.setCheckedAt(Instant.now());

        if (!properties.isEnabled()) {
            status.setState(TrainingEnvironmentStatus.State.DISABLED);
            status.setKubernetesReady(false);
            status.setMessage("K8s 训练调度已禁用，将使用本地训练");
            statusRef.set(status);
            return status;
        }

        status.setState(TrainingEnvironmentStatus.State.INITIALIZING);
        statusRef.set(status);

        try {
            if (isClusterHealthy()) {
                LOG.info("K8s 训练环境已就绪: cluster={}, namespace={}", properties.getClusterName(), properties.getNamespace());
                markReady(status, "K8s 训练环境已就绪");
                return status;
            }

            if (!properties.isAutoCreate()) {
                markFailed(status, "K8s 训练环境不可用且 auto-create=false");
                return status;
            }

            LOG.info("K8s 训练环境不可用，开始 bootstrap kind 集群...");
            ShellCommandRunner.CommandResult bootstrap = shellCommandRunner.runScript(
                    resolveBootstrapScript(),
                    resolveProjectRoot(),
                    properties.getBootstrapTimeoutSeconds(),
                    "KUBECONFIG", resolveKubeconfig().toString(),
                    "KIND", resolveKind().toString(),
                    "KUBECTL", resolveKubectl().toString(),
                    "CLUSTER_NAME", properties.getClusterName()
            );
            if (!bootstrap.success()) {
                markFailed(status, "bootstrap 失败: " + bootstrap.errorMessage() + "\n" + bootstrap.output());
                return status;
            }

            if (!isClusterHealthy()) {
                markFailed(status, "bootstrap 后集群仍不可用");
                return status;
            }

            if (properties.isVerifyOnStartup()) {
                LOG.info("执行 K8s 训练依赖连通性验证...");
                ShellCommandRunner.CommandResult verify = shellCommandRunner.runScript(
                        resolveVerifyScript(),
                        resolveProjectRoot(),
                        300,
                        "KUBECONFIG", resolveKubeconfig().toString(),
                        "KUBECTL", resolveKubectl().toString()
                );
                if (!verify.success()) {
                    markDegraded(status, "连通性验证失败: " + verify.errorMessage());
                    return status;
                }
            }

            markReady(status, "K8s 训练环境初始化完成");
            return status;
        } catch (Exception e) {
            LOG.error("K8s 训练环境初始化异常", e);
            markFailed(status, e.getMessage());
            return status;
        }
    }

    public boolean isClusterHealthy() {
        Path kubectl = resolveKubectl();
        Path kubeconfig = resolveKubeconfig();
        List<String> nodeCmd = kubectlCommand(kubeconfig, "wait", "--for=condition=Ready", "node", "--all", "--timeout=30s");
        ShellCommandRunner.CommandResult nodeResult = shellCommandRunner.run(nodeCmd, resolveProjectRoot(), 60);
        if (!nodeResult.success()) {
            LOG.debug("节点未就绪: {}", nodeResult.errorMessage());
            return false;
        }
        List<String> nsCmd = kubectlCommand(kubeconfig, "get", "namespace", properties.getNamespace());
        ShellCommandRunner.CommandResult nsResult = shellCommandRunner.run(nsCmd, resolveProjectRoot(), 30);
        return nsResult.success();
    }

    public List<String> kubectlCommand(Path kubeconfig, String... args) {
        List<String> command = new ArrayList<>();
        command.add(resolveKubectl().toString());
        command.add("--kubeconfig");
        command.add(kubeconfig.toString());
        command.addAll(List.of(args));
        return command;
    }

    private void markReady(TrainingEnvironmentStatus status, String message) {
        status.setState(TrainingEnvironmentStatus.State.READY);
        status.setKubernetesReady(true);
        status.setMessage(message);
        status.setLastError(null);
        status.setCheckedAt(Instant.now());
        statusRef.set(status);
    }

    private void markDegraded(TrainingEnvironmentStatus status, String error) {
        status.setState(TrainingEnvironmentStatus.State.DEGRADED);
        status.setKubernetesReady(true);
        status.setMessage("K8s 环境部分可用");
        status.setLastError(error);
        status.setCheckedAt(Instant.now());
        statusRef.set(status);
        LOG.warn("K8s 训练环境降级: {}", error);
    }

    private void markFailed(TrainingEnvironmentStatus status, String error) {
        status.setState(TrainingEnvironmentStatus.State.FAILED);
        status.setKubernetesReady(false);
        status.setMessage("K8s 训练环境不可用");
        status.setLastError(error);
        status.setCheckedAt(Instant.now());
        statusRef.set(status);
        LOG.error("K8s 训练环境初始化失败: {}", error);
    }
}
