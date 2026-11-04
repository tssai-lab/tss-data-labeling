package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.service.MinioDeleteTaskService;
import com.tss.platform.service.MinioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collects a bounded, redacted view of a failed training Job. It deliberately
 * never reads Pod specs or environment variables because those currently
 * contain MinIO credentials and the internal callback token.
 */
@Service
public class TrainingFailureDiagnosticService {

    static final int LOG_LIMIT_BYTES = 65_536;
    static final int LOG_TAIL_LINES = 200;
    static final int HARD_MAX_BYTES = 1_048_576;
    static final int MAX_PODS = 10;
    static final int MAX_CONTAINERS_PER_POD = 10;
    static final int COLLECTION_MAX_CHARS = HARD_MAX_BYTES * 2;
    static final String DIAGNOSTIC_PATH_SEGMENT = "/training-failure-diagnostics/";
    static final String DIAGNOSTIC_PATH_PATTERN = "%/training-failure-diagnostics/%";

    private static final Logger LOG = LoggerFactory.getLogger(TrainingFailureDiagnosticService.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?im)(authorization\\s*[:=]\\s*)[^\\r\\n]+"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?(?:authorization|password|passwd|secret(?:[_-]?key)?|access(?:[_-]?key)?|"
                    + "token|api[_-]?key)[\\\"']?\\s*[:=]\\s*)"
                    + "(?:\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|(?:bearer\\s+)?[^\\s,;]+)"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]+)?\\b");
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)(https?://[^\\s/:@]+:)[^\\s/@]+(@)");
    private static final Pattern SIGNED_URL_SECRET = Pattern.compile(
            "(?i)([?&](?:x-amz-signature|x-amz-credential|x-amz-security-token|token|"
                    + "access[_-]?key|secret[_-]?key)=)[^\\s&]+"
    );
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"
    );
    private static final Pattern JOB_FAILED_COUNT = Pattern.compile("(?m)^JOB_FAILED=[1-9][0-9]*\\s*$");
    private static final Pattern JOB_ACTIVE_COUNT = Pattern.compile("(?m)^JOB_ACTIVE=[1-9][0-9]*\\s*$");
    private static final Pattern POD_ACTIVE_PHASE = Pattern.compile("(?m)^POD_PHASE=(?:Pending|Running)\\s*$");
    private static final Pattern JOB_FAILED_CONDITION = Pattern.compile(
            "(?m)^JOB_CONDITION_TYPE=Failed\\RJOB_CONDITION_STATUS=True\\s*$"
    );
    private static final Pattern POD_FAILURE_WAITING = Pattern.compile(
            "(?m)^(?:INIT_)?WAITING_REASON=(?:CrashLoopBackOff|ImagePullBackOff|ErrImagePull|"
                    + "CreateContainerConfigError|CreateContainerError|RunContainerError|InvalidImageName)\\s*$"
    );
    private static final Pattern POD_FAILURE_TERMINATED = Pattern.compile(
            "(?m)^(?:INIT_)?TERMINATED_REASON=(?!Completed\\s*$)\\S.*$"
    );
    private static final Pattern POD_NONZERO_EXIT = Pattern.compile(
            "(?m)^(?:INIT_)?EXIT_CODE=[1-9][0-9]*\\s*$"
    );

    private final TrainingKubernetesProperties properties;
    private final TrainingEnvironmentService environmentService;
    private final ShellCommandRunner shellCommandRunner;
    private final MinioService minioService;
    private final TrainingExperimentVersionRepository repository;
    private final MinioDeleteTaskService minioDeleteTaskService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public TrainingFailureDiagnosticService(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            ShellCommandRunner shellCommandRunner,
            MinioService minioService,
            TrainingExperimentVersionRepository repository,
            MinioDeleteTaskService minioDeleteTaskService,
            PlatformTransactionManager transactionManager
    ) {
        this(
                properties,
                environmentService,
                shellCommandRunner,
                minioService,
                repository,
                minioDeleteTaskService,
                new TransactionTemplate(transactionManager)
        );
    }

    TrainingFailureDiagnosticService(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService,
            ShellCommandRunner shellCommandRunner,
            MinioService minioService,
            TrainingExperimentVersionRepository repository,
            MinioDeleteTaskService minioDeleteTaskService,
            TransactionTemplate transactionTemplate
    ) {
        this.properties = properties;
        this.environmentService = environmentService;
        this.shellCommandRunner = shellCommandRunner;
        this.minioService = minioService;
        this.repository = repository;
        this.minioDeleteTaskService = minioDeleteTaskService;
        this.transactionTemplate = transactionTemplate;
    }

    public CaptureResult archive(TrainingExperimentVersion task, String jobName) {
        return archive(task, jobName, true);
    }

    /** 停止前保存现场，此时任务仍在运行，不能要求已有失败标记。 */
    public CaptureResult archiveBeforeStop(TrainingExperimentVersion task, String jobName) {
        return archive(task, jobName, false);
    }

    private CaptureResult archive(
            TrainingExperimentVersion task,
            String jobName,
            boolean requireFailureEvidence
    ) {
        if (!properties.isFailureDiagnosticsEnabled()) {
            return CaptureResult.notArchived();
        }
        if (task == null || task.getOwnerUserId() == null || task.getOwnerUserId() <= 0) {
            LOG.warn("Skip K8s failure diagnostics without an owner: trainingId={}", task == null ? null : task.getId());
            return CaptureResult.notArchived();
        }
        if (!isSafeId(task.getId()) || !isSafeId(jobName)) {
            LOG.warn("Skip K8s failure diagnostics with an unsafe identifier: trainingId={}, job={}",
                    task.getId(), jobName);
            return CaptureResult.notArchived();
        }
        if (task.getLogPath() != null && !task.getLogPath().isBlank()) {
            return CaptureResult.notArchived();
        }

        try {
            DiagnosticReport report = collect(task, jobName);
            boolean databaseFailure = "failed".equals(task.getStatus())
                    && task.getErrorMessage() != null
                    && !task.getErrorMessage().isBlank()
                    && !report.hasActiveWorkload();
            if (requireFailureEvidence && !report.hasFailureEvidence() && !databaseFailure) {
                return CaptureResult.notArchived();
            }
            int maxBytes = Math.min(
                    HARD_MAX_BYTES,
                    Math.max(4_096, properties.getFailureDiagnosticsMaxBytes())
            );
            byte[] bytes = truncateUtf8(redact(report.text()), maxBytes);
            String objectName = diagnosticObjectName(task);
            minioService.uploadStream(
                    objectName,
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    MediaType.TEXT_PLAIN_VALUE
            );
            String logPath = "minio://" + objectName;
            LOG.info("K8s failure diagnostics archived: trainingId={}, objectName={}, bytes={}",
                    task.getId(), objectName, bytes.length);
            return new CaptureResult(true, logPath);
        } catch (Exception exception) {
            LOG.warn("K8s failure diagnostics archive failed: trainingId={}, error={}",
                    task.getId(), exception.getMessage());
            return CaptureResult.notArchived();
        }
    }

    /** Queue immediate cleanup when the owning training experiment is deleted. */
    public void enqueueDeletion(TrainingExperimentVersion task) {
        enqueueDeletion(task, task == null ? null : task.getLogPath());
    }

    /** Queue a captured object that could not be attached because the task changed concurrently. */
    public void enqueueDeletion(TrainingExperimentVersion task, String logPath) {
        if (task == null || task.getOwnerUserId() == null || task.getOwnerUserId() <= 0
                || !isSafeId(task.getId()) || !isDiagnosticLogPath(logPath)
                || !("minio://" + diagnosticObjectName(task)).equals(logPath)) {
            return;
        }
        minioDeleteTaskService.enqueueDefaultBucketDelete(
                objectName(logPath),
                MinioDeleteTaskService.SOURCE_TRAINING_FAILURE_DIAGNOSTIC,
                task.getId(),
                task.getOwnerUserId()
        );
    }

    /**
     * Retention cleanup is intentionally independent of capture enablement so
     * disabling new archives does not leave existing diagnostics forever.
     */
    @Scheduled(
            fixedDelayString = "${training.kubernetes.failure-diagnostics-cleanup-interval-ms:3600000}",
            initialDelayString = "${training.kubernetes.failure-diagnostics-cleanup-initial-delay-ms:60000}"
    )
    public void cleanupExpiredDiagnostics() {
        int retentionDays = Math.max(1, properties.getFailureDiagnosticsRetentionDays());
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        List<TrainingExperimentVersion> expired = repository.findExpiredFailureDiagnostics(
                cutoff,
                DIAGNOSTIC_PATH_PATTERN,
                PageRequest.of(0, 100)
        );
        for (TrainingExperimentVersion candidate : expired) {
            try {
                transactionTemplate.executeWithoutResult(status -> repository.findById(candidate.getId())
                        .filter(current -> current.getFinishedAt() != null && current.getFinishedAt().isBefore(cutoff))
                        .filter(current -> ("minio://" + diagnosticObjectName(current))
                                .equals(current.getLogPath()))
                        .ifPresent(current -> {
                            enqueueDeletion(current);
                            current.setLogPath(null);
                            current.setUpdatedAt(Instant.now());
                            repository.save(current);
                        }));
            } catch (Exception exception) {
                LOG.warn("Failed to enqueue expired training diagnostics: trainingId={}, error={}",
                        candidate.getId(), exception.getMessage());
            }
        }
    }

    boolean isDiagnosticLogPath(String logPath) {
        if (logPath == null || !logPath.startsWith("minio://users/") || !logPath.contains(DIAGNOSTIC_PATH_SEGMENT)) {
            return false;
        }
        String objectName = objectName(logPath);
        String[] parts = objectName.split("/", -1);
        return parts.length == 5
                && "users".equals(parts[0])
                && parts[1].matches("[0-9]+")
                && "training-failure-diagnostics".equals(parts[2])
                && SAFE_ID.matcher(parts[3]).matches()
                && "failure.log".equals(parts[4]);
    }

    static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "?");
        clean = PRIVATE_KEY.matcher(clean).replaceAll("[REDACTED_PRIVATE_KEY]");
        clean = URI_USER_INFO.matcher(clean).replaceAll("$1[REDACTED]$2");
        clean = SIGNED_URL_SECRET.matcher(clean).replaceAll("$1[REDACTED]");
        clean = AUTHORIZATION_HEADER.matcher(clean).replaceAll("$1[REDACTED]");
        clean = SENSITIVE_ASSIGNMENT.matcher(clean).replaceAll("$1[REDACTED]");
        clean = BEARER_TOKEN.matcher(clean).replaceAll("$1[REDACTED]");
        return JWT.matcher(clean).replaceAll("[REDACTED_JWT]");
    }

    private DiagnosticReport collect(TrainingExperimentVersion task, String jobName) {
        StringBuilder report = new StringBuilder();
        appendLine(report, "TSS training failure diagnostics v1");
        appendLine(report, "capturedAt=" + Instant.now());
        appendLine(report, "trainingId=" + task.getId());
        appendLine(report, "jobName=" + jobName);
        appendLine(report, "databaseStatus=" + text(task.getStatus()));
        appendLine(report, "databaseError=" + text(task.getErrorMessage()));
        appendLine(report, "assignedNode=" + text(task.getServerIp()));
        appendLine(report, "runtimeImage=" + text(task.getRuntimeImage()));
        appendLine(report, "runtimeImageDigest=" + text(task.getRuntimeImageDigest()));

        boolean failureEvidence = false;
        boolean activeWorkload = false;
        ShellCommandRunner.CommandResult jobStatus = kubectl(
                15,
                "get", "job", jobName,
                "-n", properties.getNamespace(),
                "-o", "jsonpath=JOB_NAME={.metadata.name}{\"\\n\"}JOB_ACTIVE={.status.active}{\"\\n\"}"
                        + "JOB_SUCCEEDED={.status.succeeded}{\"\\n\"}JOB_FAILED={.status.failed}{\"\\n\"}"
                        + "JOB_START_TIME={.status.startTime}{\"\\n\"}JOB_COMPLETION_TIME={.status.completionTime}{\"\\n\"}"
                        + "{range .status.conditions[*]}JOB_CONDITION_TYPE={.type}{\"\\n\"}"
                        + "JOB_CONDITION_STATUS={.status}{\"\\n\"}JOB_CONDITION_REASON={.reason}{\"\\n\"}"
                        + "JOB_CONDITION_MESSAGE={.message}{\"\\n\"}{end}"
        );
        appendResult(report, "job-status", jobStatus);
        failureEvidence |= hasJobFailure(jobStatus);
        activeWorkload |= hasJobActive(jobStatus);
        ShellCommandRunner.CommandResult jobEvents = warningEvents("Job", jobName);
        appendResult(report, "job-warning-events", jobEvents);

        ShellCommandRunner.CommandResult podNamesResult = kubectl(
                15,
                "get", "pods",
                "-n", properties.getNamespace(),
                "-l", "job-name=" + jobName,
                "-o", "jsonpath={range .items[*]}{.metadata.name}{\"\\n\"}{end}"
        );
        List<String> podNames = safeNames(podNamesResult);
        if (!podNamesResult.success()) {
            appendResult(report, "pod-list", podNamesResult);
        }

        for (String podName : podNames) {
            if (report.length() >= COLLECTION_MAX_CHARS) {
                break;
            }
            ShellCommandRunner.CommandResult podStatus = kubectl(
                    15,
                    "get", "pod", podName,
                    "-n", properties.getNamespace(),
                    "-o", podStatusJsonPath()
            );
            appendResult(report, "pod-status " + podName, podStatus);
            failureEvidence |= hasPodFailure(podStatus);
            activeWorkload |= hasPodActive(podStatus);
            ShellCommandRunner.CommandResult podEvents = warningEvents("Pod", podName);
            appendResult(report, "pod-warning-events " + podName, podEvents);
            for (String container : containerNames(podStatus.output())) {
                if (report.length() >= COLLECTION_MAX_CHARS) {
                    break;
                }
                ShellCommandRunner.CommandResult current = kubectl(
                        20,
                        "logs", podName,
                        "-n", properties.getNamespace(),
                        "-c", container,
                        "--timestamps=true",
                        "--tail=" + LOG_TAIL_LINES,
                        "--limit-bytes=" + LOG_LIMIT_BYTES
                );
                appendResult(report, "container-log " + podName + "/" + container, current);
                ShellCommandRunner.CommandResult previous = kubectl(
                        20,
                        "logs", podName,
                        "-n", properties.getNamespace(),
                        "-c", container,
                        "--previous=true",
                        "--timestamps=true",
                        "--tail=" + LOG_TAIL_LINES,
                        "--limit-bytes=" + LOG_LIMIT_BYTES
                );
                if (previous.success() && previous.output() != null && !previous.output().isBlank()) {
                    appendResult(report, "previous-container-log " + podName + "/" + container, previous);
                }
            }
        }
        return new DiagnosticReport(report.toString(), failureEvidence, activeWorkload);
    }

    private ShellCommandRunner.CommandResult warningEvents(String kind, String name) {
        return kubectl(
                15,
                "get", "events",
                "-n", properties.getNamespace(),
                "--field-selector=involvedObject.kind=" + kind + ",involvedObject.name=" + name + ",type=Warning",
                "--sort-by=.lastTimestamp",
                "-o", "jsonpath={range .items[*]}EVENT_REASON={.reason}{\"\\n\"}EVENT_COUNT={.count}{\"\\n\"}"
                        + "EVENT_FIRST={.firstTimestamp}{\"\\n\"}EVENT_LAST={.lastTimestamp}{\"\\n\"}"
                        + "EVENT_MESSAGE={.message}{\"\\n\"}---{\"\\n\"}{end}"
        );
    }

    private ShellCommandRunner.CommandResult kubectl(int timeoutSeconds, String... args) {
        Path kubeconfig = environmentService.resolveKubeconfig();
        String[] boundedArgs = new String[args.length + 1];
        boundedArgs[0] = "--request-timeout=10s";
        System.arraycopy(args, 0, boundedArgs, 1, args.length);
        return shellCommandRunner.run(
                environmentService.kubectlCommand(kubeconfig, boundedArgs),
                environmentService.resolveProjectRoot(),
                timeoutSeconds
        );
    }

    private String podStatusJsonPath() {
        return "jsonpath=POD_NAME={.metadata.name}{\"\\n\"}POD_UID={.metadata.uid}{\"\\n\"}"
                + "POD_NODE={.spec.nodeName}{\"\\n\"}POD_PHASE={.status.phase}{\"\\n\"}"
                + "POD_REASON={.status.reason}{\"\\n\"}POD_MESSAGE={.status.message}{\"\\n\"}"
                + "POD_START_TIME={.status.startTime}{\"\\n\"}"
                + "{range .status.conditions[*]}POD_CONDITION_TYPE={.type}{\"\\n\"}"
                + "POD_CONDITION_STATUS={.status}{\"\\n\"}POD_CONDITION_REASON={.reason}{\"\\n\"}"
                + "POD_CONDITION_MESSAGE={.message}{\"\\n\"}{end}"
                + "{range .status.initContainerStatuses[*]}INIT_CONTAINER_NAME={.name}{\"\\n\"}"
                + "INIT_IMAGE={.image}{\"\\n\"}INIT_IMAGE_ID={.imageID}{\"\\n\"}"
                + "INIT_RESTART_COUNT={.restartCount}{\"\\n\"}INIT_WAITING_REASON={.state.waiting.reason}{\"\\n\"}"
                + "INIT_WAITING_MESSAGE={.state.waiting.message}{\"\\n\"}"
                + "INIT_TERMINATED_REASON={.state.terminated.reason}{\"\\n\"}"
                + "INIT_TERMINATED_MESSAGE={.state.terminated.message}{\"\\n\"}"
                + "INIT_EXIT_CODE={.state.terminated.exitCode}{\"\\n\"}{end}"
                + "{range .status.containerStatuses[*]}CONTAINER_NAME={.name}{\"\\n\"}"
                + "IMAGE={.image}{\"\\n\"}IMAGE_ID={.imageID}{\"\\n\"}RESTART_COUNT={.restartCount}{\"\\n\"}"
                + "WAITING_REASON={.state.waiting.reason}{\"\\n\"}WAITING_MESSAGE={.state.waiting.message}{\"\\n\"}"
                + "TERMINATED_REASON={.state.terminated.reason}{\"\\n\"}"
                + "TERMINATED_MESSAGE={.state.terminated.message}{\"\\n\"}"
                + "EXIT_CODE={.state.terminated.exitCode}{\"\\n\"}SIGNAL={.state.terminated.signal}{\"\\n\"}{end}";
    }

    private boolean hasJobFailure(ShellCommandRunner.CommandResult result) {
        if (!hasOutput(result)) {
            return false;
        }
        String output = result.output();
        return JOB_FAILED_COUNT.matcher(output).find()
                || JOB_FAILED_CONDITION.matcher(output).find();
    }

    private boolean hasJobActive(ShellCommandRunner.CommandResult result) {
        return hasOutput(result) && JOB_ACTIVE_COUNT.matcher(result.output()).find();
    }

    private boolean hasPodFailure(ShellCommandRunner.CommandResult result) {
        if (!hasOutput(result)) {
            return false;
        }
        String output = result.output();
        return output.contains("POD_PHASE=Failed")
                || POD_FAILURE_WAITING.matcher(output).find()
                || POD_FAILURE_TERMINATED.matcher(output).find()
                || POD_NONZERO_EXIT.matcher(output).find();
    }

    private boolean hasPodActive(ShellCommandRunner.CommandResult result) {
        return hasOutput(result) && POD_ACTIVE_PHASE.matcher(result.output()).find();
    }

    private boolean hasOutput(ShellCommandRunner.CommandResult result) {
        return result != null
                && result.success()
                && result.output() != null
                && !result.output().isBlank();
    }

    private void appendResult(StringBuilder report, String title, ShellCommandRunner.CommandResult result) {
        if (report.length() >= COLLECTION_MAX_CHARS) {
            return;
        }
        appendLine(report, "");
        appendLine(report, "## " + title);
        if (result == null || !result.success()) {
            appendLine(report, "unavailable=" + text(result == null ? null : result.errorMessage()));
            return;
        }
        String output = result.output() == null ? "" : result.output().trim();
        if (output.isEmpty()) {
            appendLine(report, "(empty)");
            return;
        }
        int remaining = Math.max(0, COLLECTION_MAX_CHARS - report.length());
        appendLine(report, output.length() <= remaining ? output : output.substring(0, remaining));
    }

    private List<String> safeNames(ShellCommandRunner.CommandResult result) {
        if (result == null || !result.success() || result.output() == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String value : result.output().split("\\R")) {
            String name = value.trim();
            if (isSafeId(name)) {
                names.add(name);
                if (names.size() >= MAX_PODS) {
                    break;
                }
            }
        }
        return names.stream().limit(MAX_PODS).toList();
    }

    private List<String> containerNames(String podStatus) {
        if (podStatus == null || podStatus.isBlank()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String line : podStatus.split("\\R")) {
            if (line.startsWith("CONTAINER_NAME=") || line.startsWith("INIT_CONTAINER_NAME=")) {
                String name = line.substring(line.indexOf('=') + 1).trim();
                if (isSafeId(name)) {
                    names.add(name);
                    if (names.size() >= MAX_CONTAINERS_PER_POD) {
                        break;
                    }
                }
            }
        }
        return names.stream().limit(MAX_CONTAINERS_PER_POD).toList();
    }

    private String diagnosticObjectName(TrainingExperimentVersion task) {
        return "users/" + task.getOwnerUserId()
                + "/training-failure-diagnostics/" + task.getId() + "/failure.log";
    }

    private String objectName(String logPath) {
        return logPath.substring("minio://".length());
    }

    private boolean isSafeId(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private static void appendLine(StringBuilder report, String line) {
        report.append(line).append('\n');
    }

    static byte[] truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return bytes;
        }
        int end = Math.max(0, maxBytes);
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end -= 1;
        }
        byte[] marker = "\n[TRUNCATED]\n".getBytes(StandardCharsets.UTF_8);
        int contentEnd = Math.max(0, end - marker.length);
        while (contentEnd > 0 && (bytes[contentEnd] & 0xC0) == 0x80) {
            contentEnd -= 1;
        }
        byte[] result = Arrays.copyOf(bytes, contentEnd + marker.length);
        System.arraycopy(marker, 0, result, contentEnd, marker.length);
        return result;
    }

    public record CaptureResult(boolean archived, String logPath) {
        static CaptureResult notArchived() {
            return new CaptureResult(false, null);
        }
    }

    private record DiagnosticReport(
            String text,
            boolean hasFailureEvidence,
            boolean hasActiveWorkload
    ) {
    }
}
