package com.tss.platform.service;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.dto.InferenceResourceProfileDto;
import com.tss.platform.inference.InferenceResourceProfileService;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.training.TrainingExecutorRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 自定义训练/推理任务调度器。
 * 从匹配 nodeSelector 的在线节点中选出剩余资源最多的，决定跑在哪个节点上。
 * 资源不足时将任务标记为 queued，由定时任务自动重试分配。
 */
@Service
public class JobScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(JobScheduler.class);
    static final String PLATFORM_SCHEDULABLE_LABEL = ComputeServerSchedulingPolicy.PLATFORM_SCHEDULABLE_LABEL;
    static final String PLATFORM_MAX_ACTIVE_TASKS_LABEL = ComputeServerSchedulingPolicy.PLATFORM_MAX_ACTIVE_TASKS_LABEL;
    static final String GPU_SCHEDULABLE_LABEL = ComputeServerSchedulingPolicy.GPU_SCHEDULABLE_LABEL;

    private final ComputeServerRepository computeServerRepo;
    private final TrainingExperimentVersionRepository trainingRepo;
    private final InferenceTaskRepository inferenceRepo;
    private final TrainingKubernetesProperties k8sProperties;
    private final TrainingExecutorRouter executorRouter;
    private final TransactionTemplate transactionTemplate;
    private InferenceModelCacheProperties modelCacheProperties = new InferenceModelCacheProperties();
    private InferenceResourceProfileService inferenceResourceProfileService;


    public JobScheduler(
            ComputeServerRepository computeServerRepo,
            TrainingExperimentVersionRepository trainingRepo,
            InferenceTaskRepository inferenceRepo,
            TrainingKubernetesProperties k8sProperties,
            @Lazy TrainingExecutorRouter executorRouter,
            TransactionTemplate transactionTemplate) {
        this.computeServerRepo = computeServerRepo;
        this.trainingRepo = trainingRepo;
        this.inferenceRepo = inferenceRepo;
        this.k8sProperties = k8sProperties;
        this.executorRouter = executorRouter;
        this.transactionTemplate = transactionTemplate;
    }

    @Autowired
    void setModelCacheProperties(InferenceModelCacheProperties modelCacheProperties) {
        this.modelCacheProperties = modelCacheProperties;
    }

    @Autowired
    void setInferenceResourceProfileService(InferenceResourceProfileService service) {
        this.inferenceResourceProfileService = service;
    }

    /**
     * 为训练任务分配节点。从 task.runSpecJson 读取资源需求，匹配 nodeSelector 的在线节点中选剩余最多的。
     * @return 分配的 nodeName，资源不足返回 null
     */
    public String assignNodeForTraining(TrainingExperimentVersion task, Map<String, String> nodeSelector) {
        double[] req = resolveResourceRequest(task);
        Integer gpuReq = req[2] > 0 ? (int) req[2] : null;
        return assignNode(nodeSelector, req[0], req[1], gpuReq, task.getInputModelSha256());
    }

    public String assignNodeForInference(InferenceTask task, String modelDigest) {
        double[] req = resolveResourceRequest(task);
        boolean gpu = task.getGpuUuid() != null && !task.getGpuUuid().isBlank();
        Map<String, String> selector = gpu
                ? Map.of("kubernetes.io/hostname", task.getGpuNodeName())
                : Map.of();
        return assignNode(selector, req[0], req[1], gpu ? 1 : null, modelDigest);
    }



    /**
     * 原子绑定任务到节点（状态变为 scheduled，已分配，等待启动）。
     * 通过条件更新（server_ip IS NULL 且 status in pending/queued）保证只有一条路径能绑定成功，
     * 防止 afterCommit 与调度循环并发时重复绑定 / 重复提交，从而避免 serverIp 被并发写覆盖丢失。
     *
     * @return true=本次调用绑定成功；false=已被其他线程抢先绑定或任务已不在可绑定状态
     */
    public boolean bindTask(String taskId, String nodeName) {
        int updated = trainingRepo.atomicBindNode(taskId, nodeName, java.time.Instant.now());
        if (updated > 0) {
            LOG.info("任务已绑定节点: taskId={}, node={}, status=scheduled", taskId, nodeName);
            return true;
        }
        LOG.info("任务绑定被其他线程抢占: taskId={}, node={}", taskId, nodeName);
        return false;
    }

    /**
     * 标记任务排队。
     */
    @Transactional
    public void enqueueTask(TrainingExperimentVersion task) {
        task.setStatus("queued");
        task.setUpdatedAt(java.time.Instant.now());
        trainingRepo.save(task);
        LOG.info("任务排队等待: taskId={}", task.getId());
    }

    /**
     * 任务结束/失败时释放资源。当前通过 server_ip 查询即可，无需额外账本。
     */
    public void releaseResources(String trainingId, String nodeName) {
        LOG.info("释放节点资源: taskId={}, node={}", trainingId, nodeName);
    }

    /**
     * 定时调度：每 10 秒执行两阶段调度。
     * Phase 1 — pending/queued（无节点）→ scheduled（已分配节点）：事务内悲观锁 + 原子绑定。
     * Phase 2 — scheduled → 提交 K8s 执行（崩溃兜底重提）。
     * 绑定必须在事务提交后再启动异步提交，否则异步线程会读到未提交的绑定导致 serverIp 丢失。
     */
    @Scheduled(fixedDelay = 10_000)
    public void dispatchQueuedTasks() {
        // Phase 1：悲观锁查询未分配节点（pending/queued）的任务，分配节点并原子绑定。
        // 绑定成功的任务先收集 id，等事务提交（serverIp 落库）后再启动异步提交。
        List<String> newlyBound = transactionTemplate.execute(status -> {
            List<String> bound = new ArrayList<>();
            List<TrainingExperimentVersion> unassigned = trainingRepo.findAllPendingWithLock();
            for (TrainingExperimentVersion task : unassigned) {
                if (task.getTrainingPlanId() == null || task.getTrainingPlanId().isBlank()) {
                    continue;
                }
                try {
                    Map<String, String> nodeSelector = resolveNodeSelector(task);
                    double[] req = resolveResourceRequest(task);
                    Integer gpuReq = req[2] > 0 ? (int) req[2] : null;
                    String node = assignNode(nodeSelector, req[0], req[1], gpuReq, task.getInputModelSha256());
                    if (node != null) {
                        if (bindTask(task.getId(), node)) {
                            bound.add(task.getId());
                        }
                    } else {
                        // 当前任务本轮分配不到节点（通常是所需资源池不足）。
                        // 不 break：不同 nodeSelector（CPU/GPU 等不同池）的任务互不竞争，
                        // 前面的任务占不到，不代表后面的任务也占不到，否则会造成队首跨池任务阻塞整轮。
                        LOG.debug("任务本轮未分配到节点，跳过尝试后续任务: taskId={}", task.getId());
                    }
                } catch (Exception e) {
                    LOG.warn("调度排队任务失败: taskId={}, error={}", task.getId(), e.getMessage());
                }
            }
            return bound;
        });

        // 事务已提交、绑定已落库后再启动，异步提交线程读到的才是已提交的 serverIp
        for (String trainingId : newlyBound) {
            executorRouter.start(trainingId);
        }

        // Phase 2：scheduled → 提交 K8s 执行（崩溃兜底重提；跳过刚绑定的，避免同一 tick 重复提交）
        List<TrainingExperimentVersion> scheduled = trainingRepo.findByStatus("scheduled");
        scheduled.sort(Comparator.comparingInt((TrainingExperimentVersion t) ->
                "高".equals(t.getPriority()) ? 0 : ("中".equals(t.getPriority()) ? 1 : 2))
                .thenComparing(t -> t.getCreatedAt() != null ? t.getCreatedAt() : java.time.Instant.EPOCH));

        for (TrainingExperimentVersion task : scheduled) {
            if (newlyBound.contains(task.getId())) {
                continue;
            }
            try {
                LOG.info("提交 scheduled 任务: taskId={}, node={}", task.getId(), task.getServerIp());
                executorRouter.start(task.getId());
            } catch (Exception e) {
                LOG.warn("提交 scheduled 任务失败: taskId={}, error={}", task.getId(), e.getMessage());
            }
        }
    }

    // ── 内部逻辑 ──

    /**
     * 核心分配算法：
     * 1. 筛选匹配 nodeSelector 的 online 节点
     * 2. 计算每个节点已分配资源（status=running 的任务）
     * 3. 算剩余容量 = 总容量 - 已分配
     * 4. 选剩余最多的，且能满足本次请求的
     */
    private String assignNode(
            Map<String, String> nodeSelector,
            double cpuReq,
            double memReq,
            Integer gpuReq,
            String affinityKey
    ) {
        List<ComputeServer> candidates = computeServerRepo.findByDeletedFalse().stream()
                .filter(n -> "online".equals(n.getStatus()) && Boolean.TRUE.equals(n.getEnabled()))
                .filter(this::isPlatformSchedulable)
                .collect(Collectors.toList());
        if (modelCacheProperties.isEnabled()) {
            candidates = candidates.stream()
                    .filter(this::isCacheReady)
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) return null;

        // 按 nodeSelector 筛选
        if (nodeSelector != null && !nodeSelector.isEmpty()) {
            candidates = candidates.stream()
                    .filter(n -> matchesNodeSelector(n, nodeSelector))
                    .collect(Collectors.toList());
        }
        if (gpuReq != null && gpuReq > 0) {
            candidates = candidates.stream()
                    .filter(this::isGpuSchedulable)
                    .collect(Collectors.toList());
        }

        // 统计每个节点的已分配资源（从每个 running 任务的 runSpecJson 读取）
        Map<String, Double> allocatedCpu = new LinkedHashMap<>();
        Map<String, Double> allocatedMemGib = new LinkedHashMap<>();
        Map<String, Integer> allocatedGpu = new LinkedHashMap<>();
        Map<String, Integer> allocatedTasks = new LinkedHashMap<>();
        for (TrainingExperimentVersion t : trainingRepo.findByStatus("running")) {
            String ip = t.getServerIp();
            if (ip != null) {
                double[] req = resolveResourceRequest(t);
                allocatedCpu.merge(ip, req[0], Double::sum);
                allocatedMemGib.merge(ip, req[1], Double::sum);
                if (req[2] > 0) allocatedGpu.merge(ip, (int) req[2], Integer::sum);
                allocatedTasks.merge(ip, 1, Integer::sum);
            }
        }
        for (InferenceTask t : inferenceRepo.findByStatus("running")) {
            String ip = t.getServerIp();
            if (ip != null) {
                double[] req = resolveResourceRequest(t);
                allocatedCpu.merge(ip, req[0], Double::sum);
                allocatedMemGib.merge(ip, req[1], Double::sum);
                if (req[2] > 0) allocatedGpu.merge(ip, (int) req[2], Integer::sum);
                allocatedTasks.merge(ip, 1, Integer::sum);
            }
        }
        // scheduled 状态的任务已经预留资源，也要计入已分配
        for (TrainingExperimentVersion t : trainingRepo.findByStatus("scheduled")) {
            String ip = t.getServerIp();
            if (ip != null) {
                double[] req = resolveResourceRequest(t);
                allocatedCpu.merge(ip, req[0], Double::sum);
                allocatedMemGib.merge(ip, req[1], Double::sum);
                if (req[2] > 0) allocatedGpu.merge(ip, (int) req[2], Integer::sum);
                allocatedTasks.merge(ip, 1, Integer::sum);
            }
        }
        for (InferenceTask t : inferenceRepo.findByStatus("scheduled")) {
            String ip = t.getServerIp();
            if (ip != null) {
                double[] req = resolveResourceRequest(t);
                allocatedCpu.merge(ip, req[0], Double::sum);
                allocatedMemGib.merge(ip, req[1], Double::sum);
                if (req[2] > 0) allocatedGpu.merge(ip, (int) req[2], Integer::sum);
                allocatedTasks.merge(ip, 1, Integer::sum);
            }
        }

        // 选剩余最多的
        ComputeServer best = null;
        double bestRemaining = -1;
        long bestAffinity = 0;
        for (ComputeServer node : candidates) {
            Integer maxActiveTasks = maxActiveTasks(node);
            if (maxActiveTasks != null
                    && allocatedTasks.getOrDefault(node.getServerIp(), 0) >= maxActiveTasks) {
                continue;
            }
            double usedCpu = allocatedCpu.getOrDefault(node.getServerIp(), 0.0);
            double usedMem = allocatedMemGib.getOrDefault(node.getServerIp(), 0.0);
            int usedGpu = allocatedGpu.getOrDefault(node.getServerIp(), 0);
            double totalCpu = node.getCpuCores() != null ? node.getCpuCores() : 0;
            double totalMem = node.getMemoryGib() != null ? node.getMemoryGib() : 0;
            int totalGpu = node.getGpuCount() != null ? node.getGpuCount() : 0;

            double remCpu = totalCpu - usedCpu;
            double remMem = totalMem - usedMem;
            int remGpu = totalGpu - usedGpu;
            double remScore = Math.min(remCpu / Math.max(cpuReq, 0.001), remMem / Math.max(memReq, 0.001));

            boolean gpuOk = gpuReq == null || remGpu >= gpuReq;
            if (remCpu < cpuReq || remMem < memReq || !gpuOk) {
                continue;
            }
            long affinity = affinityScore(affinityKey, node.getServerIp());
            boolean affinityEnabled = modelCacheProperties.isEnabled()
                    && affinityKey != null && !affinityKey.isBlank();
            boolean preferred = best == null
                    || (affinityEnabled && Long.compareUnsigned(affinity, bestAffinity) > 0)
                    || (!affinityEnabled && remScore > bestRemaining);
            if (preferred) {
                bestRemaining = remScore;
                bestAffinity = affinity;
                best = node;
            }
        }

        return best != null ? best.getServerIp() : null;
    }

    public Map<String, String> resolveNodeSelector(TrainingExperimentVersion task) {
        // 从 RunSpec 或 trainingProfile 获取 nodeSelector
        // 如果有 runSpecJson，解析获取；否则按 trainingProfile 的默认值
        try {
            if (task.getRunSpecJson() != null && !task.getRunSpecJson().isBlank()) {
                JsonNode runSpec = new ObjectMapper().readTree(task.getRunSpecJson());
                JsonNode ns = runSpec.path("resources").path("nodeSelector");
                if (ns.isObject()) {
                    Map<String, String> map = new LinkedHashMap<>();
                    ns.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
                    return map;
                }
            }
        } catch (Exception ignored) {}
        // 默认：CPU 节点池
        return Map.of("tss.ai/node-pool", "cpu");
    }

    /** 从 task.runSpecJson 读取资源需求，读不到则 fallback 到 application.yml 全局配置 */
    double[] resolveResourceRequest(TrainingExperimentVersion task) {
        if (task.getRunSpecJson() != null && !task.getRunSpecJson().isBlank()) {
            double[] req = parseResourcesFromRunSpecJson(task.getRunSpecJson());
            if (req != null) return req;
        }
        return new double[]{
                parseCpuToCores(k8sProperties.getCpuRequest()),
                parseMemToGib(k8sProperties.getMemoryRequest()), 0
        };
    }

    double[] resolveResourceRequest(InferenceTask task) {
        if (inferenceResourceProfileService != null) {
            InferenceResourceProfileDto profile = inferenceResourceProfileService
                    .resolveForExecution(task.getResourceProfileId());
            return new double[]{
                    parseCpuToCores(profile.cpuRequest()),
                    parseMemToGib(profile.memoryRequest()),
                    profile.gpuCount() == null ? 0 : profile.gpuCount()
            };
        }
        return new double[]{
                parseCpuToCores(k8sProperties.getCpuRequest()),
                parseMemToGib(k8sProperties.getMemoryRequest()), 0
        };
    }

    private double[] parseResourcesFromRunSpecJson(String json) {
        try {
            JsonNode runSpec = new ObjectMapper().readTree(json);
            JsonNode resources = runSpec.path("resources");
            double cpu = parseCpuToCores(resources.path("cpuRequest").asText("500m"));
            double mem = parseMemToGib(resources.path("memoryRequest").asText("512Mi"));
            double gpu = resources.path("gpuCount").asDouble(0);
            return new double[]{cpu, mem, gpu};
        } catch (Exception e) {
            return null;
        }
    }

    private boolean matchesNodeSelector(ComputeServer node, Map<String, String> selector) {
        return ComputeServerSchedulingPolicy.matchesNodeSelector(node, selector);
    }

    // ── 工具方法 ──

    boolean isCacheReady(ComputeServer node) {
        return ComputeServerSchedulingPolicy.isCacheReady(node);
    }

    /**
     * Exclude nodes that Kubernetes has explicitly made unavailable to ordinary
     * platform workloads. Missing or malformed historical label data keeps the
     * previous behavior so upgrading an existing CPU cluster cannot stall all
     * work before the metrics collector has refreshed once.
     */
    boolean isPlatformSchedulable(ComputeServer node) {
        return ComputeServerSchedulingPolicy.isPlatformSchedulable(node);
    }

    private boolean isGpuSchedulable(ComputeServer node) {
        return ComputeServerSchedulingPolicy.isGpuSchedulable(node);
    }

    private Integer maxActiveTasks(ComputeServer node) {
        return ComputeServerSchedulingPolicy.maxActiveTasks(node);
    }

    static long affinityScore(String affinityKey, String nodeId) {
        if (affinityKey == null || affinityKey.isBlank()) {
            return 0;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (affinityKey.trim().toLowerCase(Locale.ROOT) + "\\0" + nodeId)
                            .getBytes(StandardCharsets.UTF_8)
            );
            long score = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                score = (score << 8) | (digest[index] & 0xffL);
            }
            return score;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static double parseCpuToCores(String s) {
        if (s == null || s.isEmpty()) return 0.5;
        s = s.trim();
        if (s.endsWith("m")) return Double.parseDouble(s.replace("m", "")) / 1000.0;
        return Double.parseDouble(s);
    }

    static double parseMemToGib(String s) {
        if (s == null || s.isEmpty()) return 0.5;
        s = s.trim();
        if (s.endsWith("Mi")) return Double.parseDouble(s.replace("Mi", "")) / 1024.0;
        if (s.endsWith("Gi")) return Double.parseDouble(s.replace("Gi", ""));
        if (s.endsWith("Ki")) return Double.parseDouble(s.replace("Ki", "")) / (1024.0 * 1024.0);
        return Double.parseDouble(s) / (1024.0 * 1024.0 * 1024.0);
    }
}
