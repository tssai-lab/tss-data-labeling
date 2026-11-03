package com.tss.platform.service;

import com.tss.platform.config.ComputeProperties;
import com.tss.platform.dto.resource.*;
import com.tss.platform.dto.resource.KubernetesDiagnosticsDto.KubernetesNodeHealth;
import com.tss.platform.entity.*;
import com.tss.platform.inference.InferenceExecutorRouter;
import com.tss.platform.repository.*;
import com.tss.platform.training.TrainingExecutorRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ResourceMonitorService {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceMonitorService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final Set<String> PRIORITY_VALUES = Set.of("高", "中", "低");
    private static final Map<String, Integer> PRIORITY_ORDER = Map.of("高", 3, "中", 2, "低", 1);

    private final ComputeServerRepository serverRepo;
    private final ServerMetricSnapshotRepository snapshotRepo;
    private final ServerMetricHistoryRepository historyRepo;
    private final TrainingExperimentVersionRepository trainingRepo;
    private final InferenceTaskRepository inferenceRepo;
    private final JobScheduler jobScheduler;
    private final KubernetesResourceDiagnosticsService kubernetesDiagnosticsService;
    private final ComputeProperties computeProperties;
    private final TrainingExecutorRouter trainingExecutorRouter;
    private final InferenceExecutorRouter inferenceExecutorRouter;
    private GpuDeviceObservationStore gpuObservationStore;

    public ResourceMonitorService(
            ComputeServerRepository serverRepo,
            ServerMetricSnapshotRepository snapshotRepo,
            ServerMetricHistoryRepository historyRepo,
            TrainingExperimentVersionRepository trainingRepo,
            InferenceTaskRepository inferenceRepo,
            JobScheduler jobScheduler,
            KubernetesResourceDiagnosticsService kubernetesDiagnosticsService,
            ComputeProperties computeProperties,
            @Lazy TrainingExecutorRouter trainingExecutorRouter,
            @Lazy InferenceExecutorRouter inferenceExecutorRouter) {
        this.serverRepo = serverRepo;
        this.snapshotRepo = snapshotRepo;
        this.historyRepo = historyRepo;
        this.trainingRepo = trainingRepo;
        this.inferenceRepo = inferenceRepo;
        this.jobScheduler = jobScheduler;
        this.kubernetesDiagnosticsService = kubernetesDiagnosticsService;
        this.computeProperties = computeProperties;
        this.trainingExecutorRouter = trainingExecutorRouter;
        this.inferenceExecutorRouter = inferenceExecutorRouter;
    }

    @Autowired
    void setGpuObservationStore(GpuDeviceObservationStore gpuObservationStore) {
        this.gpuObservationStore = gpuObservationStore;
    }

    // ────────── 5.1 SUMMARY ──────────

    public SummaryDto getSummary() {
        List<ComputeServer> servers = serverRepo.findByDeletedFalse();
        List<ServerMetricSnapshot> snapshots = snapshotRepo.findAll();
        KubernetesResourceDiagnosticsService.NodeHealthCollection nodeHealth =
                kubernetesDiagnosticsService.collectNodeHealth();

        Map<String, ServerMetricSnapshot> snapMap = snapshots.stream()
                .collect(Collectors.toMap(ServerMetricSnapshot::getServerIp, s -> s));

        int online = 0;
        double totalGpuRate = 0;
        int gpuCount = 0;
        int runningTasks = 0;
        int queuedTasks = 0;

        for (ComputeServer s : servers) {
            ServerMetricSnapshot snap = snapMap.get(s.getServerIp());
            KubernetesNodeHealth node = nodeFor(s, nodeHealth);
            // 告警(warning)只代表资源使用率高，节点仍在线，应计入在线数
            if (isServerHealthy(snap, nodeHealth, node)) {
                online++;
            }
            if ("fresh".equals(metricsState(snap).status())
                    && snap != null && snap.getGpuRate() != null) {
                totalGpuRate += snap.getGpuRate();
                gpuCount++;
            }
        }

        // 汇总所有服务器上的任务数（服务器维度：排队数=已调度待启动 scheduled，不含全局 queued）
        List<TrainingExperimentVersion> trainingTasks = trainingRepo
                .findByServerIpNotNullAndStatusIn(List.of("running", "scheduled"));
        List<InferenceTask> inferenceTasks = inferenceRepo
                .findByServerIpNotNullAndStatusIn(List.of("running", "scheduled"));

        for (TrainingExperimentVersion t : trainingTasks) {
            if ("running".equals(t.getStatus())) runningTasks++;
            else queuedTasks++;  // scheduled 待启动
        }
        for (InferenceTask t : inferenceTasks) {
            if ("running".equals(t.getStatus())) runningTasks++;
            else queuedTasks++;
        }

        SummaryDto dto = new SummaryDto();
        dto.setTotal(servers.size());
        dto.setOnline(online);
        dto.setRunningTasks(runningTasks);
        dto.setQueuedTasks(queuedTasks);
        dto.setAvgGpu(gpuCount > 0 ? String.format("%.1f", totalGpuRate / gpuCount) : null);
        return dto;
    }

    // ────────── 5.2 SERVERS LIST ──────────

    public List<ServerItem> listServers(String keyword, String status) {
        List<ComputeServer> servers = serverRepo.findByDeletedFalse();
        List<ServerMetricSnapshot> snapshots = snapshotRepo.findAll();
        KubernetesResourceDiagnosticsService.NodeHealthCollection nodeHealth =
                kubernetesDiagnosticsService.collectNodeHealth();
        Map<String, ServerMetricSnapshot> snapMap = snapshots.stream()
                .collect(Collectors.toMap(ServerMetricSnapshot::getServerIp, s -> s));

        return servers.stream()
                .filter(s -> keyword == null || keyword.isEmpty()
                        || s.getHostname().contains(keyword) || s.getServerIp().contains(keyword))
                .map(s -> toServerItem(s, snapMap.get(s.getServerIp()), true, nodeHealth))
                .filter(item -> status == null || "all".equals(status) || item.getStatus().equals(status))
                .collect(Collectors.toList());
    }

    public KubernetesDiagnosticsDto getKubernetesDiagnostics() {
        return kubernetesDiagnosticsService.collectDiagnostics();
    }

    // ────────── 5.3 ADD SERVER ──────────

    @Transactional
    public ServerItem addServer(AddServerRequest req) {
        String ip = req.getServerIp();
        if (ip == null || ip.isEmpty()) {
            throw new IllegalArgumentException("请输入合法的 IP 地址");
        }
        if (!isValidIp(ip)) {
            throw new IllegalArgumentException("请输入合法的 IP 地址");
        }
        if (serverRepo.existsByServerIpAndDeletedFalse(ip)) {
            throw new IllegalArgumentException("该 IP 已存在");
        }

        ComputeServer server = new ComputeServer();
        server.setServerIp(ip);
        server.setHostname(req.getHostname() != null ? req.getHostname() : ip);
        server.setStatus("online");
        server.setDeleted(false);
        server.setCreatedAt(Instant.now());
        server.setUpdatedAt(Instant.now());

        if (req.getSpecs() != null) {
            server.setSpecCpu(req.getSpecs().getCpu());
            server.setSpecMemory(req.getSpecs().getMemory());
            server.setSpecGpu(req.getSpecs().getGpu());
            server.setSpecOs(req.getSpecs().getOs() != null ? req.getSpecs().getOs() : "Ubuntu 22.04");
        }

        serverRepo.save(server);
        LOG.info("服务器已添加: {} ({})", ip, server.getHostname());
        return toServerItem(server, null, false, null);
    }

    // ────────── 5.4 SERVER DETAIL ──────────

    public ServerItem getServerDetail(String serverIp) {
        ComputeServer server = serverRepo.findByServerIpAndDeletedFalse(serverIp)
                .orElseThrow(() -> new IllegalArgumentException("服务器不存在: " + serverIp));
        ServerMetricSnapshot snapshot = snapshotRepo.findByServerIp(serverIp).orElse(null);
        KubernetesResourceDiagnosticsService.NodeHealthCollection nodeHealth =
                kubernetesDiagnosticsService.collectNodeHealth();
        return toServerItem(server, snapshot, true, nodeHealth);
    }

    // ────────── 5.5 DELETE SERVER ──────────

    @Transactional
    public void deleteServer(String serverIp) {
        ComputeServer server = serverRepo.findByServerIpAndDeletedFalse(serverIp)
                .orElse(null);
        if (server == null) {
            throw new IllegalArgumentException("服务器不存在: " + serverIp);
        }

        // 检查是否有运行中或已分配任务
        List<TrainingExperimentVersion> runningTraining = trainingRepo
                .findByServerIpAndStatus(serverIp, "running");
        List<InferenceTask> runningInference = inferenceRepo
                .findByServerIpAndStatus(serverIp, "running");
        List<TrainingExperimentVersion> scheduledTraining = trainingRepo
                .findByServerIpAndStatus(serverIp, "scheduled");
        List<InferenceTask> scheduledInference = inferenceRepo
                .findByServerIpAndStatus(serverIp, "scheduled");

        if (!runningTraining.isEmpty() || !runningInference.isEmpty()
                || !scheduledTraining.isEmpty() || !scheduledInference.isEmpty()) {
            throw new IllegalArgumentException("该服务器仍有运行中或已分配任务，无法删除");
        }

        // 清空该节点排队任务
        cancelQueuedTasksOnServer(serverIp);

        // 软删除
        server.setDeleted(true);
        server.setUpdatedAt(Instant.now());
        serverRepo.save(server);
        LOG.info("服务器已删除: {}", serverIp);
    }

    // ────────── 5.5b UPDATE SERVER ENABLED ──────────

    @Transactional
    public ServerItem updateServerEnabled(String serverIp, UpdateServerEnabledRequest req) {
        ComputeServer server = serverRepo.findByServerIpAndDeletedFalse(serverIp)
                .orElseThrow(() -> new IllegalArgumentException("服务器不存在: " + serverIp));
        if (req == null || req.getEnabled() == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        server.setEnabled(req.getEnabled());
        server.setUpdatedAt(Instant.now());
        serverRepo.save(server);
        LOG.info("服务器启用状态已更新: {} enabled={}", serverIp, req.getEnabled());
        return getServerDetail(serverIp);
    }

    // ────────── 5.6 METRICS ──────────

    public MetricsResponse getMetrics(String serverIp, String interval) {
        if (interval == null || interval.isEmpty()) {
            interval = "1hour";
        }

        // 计算时间范围
        Duration duration;
        String spanLabel;
        int maxPoints;
        switch (interval) {
            case "1min":
                duration = Duration.ofHours(1);
                spanLabel = "近 1 小时（按分钟）";
                maxPoints = 60;
                break;
            case "10min":
                duration = Duration.ofHours(12);
                spanLabel = "近 12 小时（按10分钟）";
                maxPoints = 72;
                break;
            case "1hour":
                duration = Duration.ofHours(24);
                spanLabel = "近 24 小时（按小时）";
                maxPoints = 24;
                break;
            case "1day":
                duration = Duration.ofDays(7);
                spanLabel = "近 7 天（按天）";
                maxPoints = 7;
                break;
            default:
                duration = Duration.ofHours(24);
                spanLabel = "近 24 小时（按小时）";
                maxPoints = 24;
        }

        Instant to = Instant.now();
        Instant from = to.minus(duration);
        // 采集频率约 30s/条，窗口内记录数远超 maxPoints*10；
        // 之前用 PageRequest 分页取前 N 条（升序）会截掉最新数据，导致趋势图右侧全 0。
        // 这里一次取全窗口数据，窗口最大 7 天 ≈ 2 万条，量级可接受。
        List<ServerMetricHistory> history = historyRepo
                .findByServerIpAndCollectedAtBetweenOrderByCollectedAtAsc(
                        serverIp, from, to, Pageable.unpaged());

        // 按时间桶聚合
        long bucketMs = duration.toMillis() / maxPoints;
        List<MetricPoint> points = new ArrayList<>();

        for (int i = 0; i < maxPoints; i++) {
            long bucketStart = to.toEpochMilli() - (maxPoints - i) * bucketMs;
            long bucketEnd = to.toEpochMilli() - (maxPoints - i - 1) * bucketMs;

            List<ServerMetricHistory> bucket = history.stream()
                    .filter(h -> {
                        long ts = h.getCollectedAt().toEpochMilli();
                        return ts >= bucketStart && ts < bucketEnd;
                    }).toList();

            Instant tickTime = Instant.ofEpochMilli((bucketStart + bucketEnd) / 2);
            MetricPoint cpuPoint = makePoint(i, tickTime, "CPU",
                    averageAvailable(bucket.stream().map(ServerMetricHistory::getCpuRate).toList()));
            MetricPoint memPoint = makePoint(i, tickTime, "内存",
                    averageAvailable(bucket.stream().map(ServerMetricHistory::getMemRate).toList()));
            MetricPoint gpuPoint = makePoint(i, tickTime, "GPU",
                    averageAvailable(bucket.stream().map(ServerMetricHistory::getGpuRate).toList()));

            points.add(cpuPoint);
            points.add(memPoint);
            points.add(gpuPoint);
        }

        MetricsResponse resp = new MetricsResponse();
        resp.setInterval(interval);
        resp.setSpanLabel(spanLabel);
        resp.setPoints(points);
        applyMetricsState(resp, metricsState(snapshotRepo.findByServerIp(serverIp).orElse(null)));
        return resp;
    }

    // ────────── 5.7 QUEUE REORDER ──────────

    @Transactional
    public ServerItem reorderQueue(String serverIp, ReorderRequest req) {
        List<QueuedTaskEntry> entries = loadQueuedEntries(serverIp);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("没有可操作的排队任务");
        }

        int idx = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).taskId().equals(req.getTaskId())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException("排队任务不存在");
        }

        if ("up".equals(req.getDirection()) && idx > 0) {
            Collections.swap(entries, idx, idx - 1);
        } else if ("down".equals(req.getDirection()) && idx < entries.size() - 1) {
            Collections.swap(entries, idx, idx + 1);
        }

        // 重新分配 queueSortIndex: 1-based
        for (int i = 0; i < entries.size(); i++) {
            updateQueueSortIndex(entries.get(i).taskId(), i + 1);
        }

        return getServerDetail(serverIp);
    }

    // ────────── 5.8 UPDATE PRIORITY ──────────

    @Transactional
    public ServerItem updatePriority(String serverIp, PriorityRequest req) {
        if (!PRIORITY_VALUES.contains(req.getPriority())) {
            throw new IllegalArgumentException("优先级必须为 高/中/低");
        }

        // 更新优先级，queueSortIndex 设为 0（让自动规则重排）
        updateTaskPriority(req.getTaskId(), req.getPriority());

        // 自动重排（保持已有 queueSortIndex != 0 的，剩余的按优先级+FIFO）
        autoReorderQueued(serverIp);

        return getServerDetail(serverIp);
    }

    // ────────── 5.9 CANCEL QUEUE ──────────

    @Transactional
    public ServerItem cancelQueueTask(String serverIp, String taskId) {
        // 已调度待启动（scheduled）的任务可能已提交 K8s Job：只改数据库状态会导致 Job 成孤儿继续占用底层资源，
        // 取消前先删除对应 Job，避免资源泄漏。
        trainingRepo.findById(taskId).ifPresent(t -> {
            if ("scheduled".equals(t.getStatus())) {
                trainingExecutorRouter.stop(taskId);
            }
        });
        inferenceRepo.findById(taskId).ifPresent(t -> {
            if ("scheduled".equals(t.getStatus())) {
                inferenceExecutorRouter.stop(taskId);
            }
        });
        updateTaskStatus(taskId, "cancelled", null);
        autoReorderQueued(serverIp);
        return getServerDetail(serverIp);
    }

    // ────────── 5.10 GLOBAL QUEUE（跨服务器全局排队）──────────

    /**
     * 全局排队列表：所有未分配节点（serverIp 为 null）的 queued/pending 训练任务，
     * 按所需资源池（nodePool）分组展示。组内顺序决定谁先获得该池的空闲资源，
     * 跨池任务互不竞争，池间顺序无调度意义。
     */
    public List<GlobalQueuedTask> listGlobalQueued() {
        List<TrainingExperimentVersion> tasks = trainingRepo.findUnassignedQueuedOrdered();
        Map<String, List<TrainingExperimentVersion>> byPool = new LinkedHashMap<>();
        for (TrainingExperimentVersion t : tasks) {
            byPool.computeIfAbsent(resolvePoolKey(t), k -> new ArrayList<>()).add(t);
        }
        List<GlobalQueuedTask> result = new ArrayList<>();
        for (Map.Entry<String, List<TrainingExperimentVersion>> e : byPool.entrySet()) {
            List<TrainingExperimentVersion> pool = e.getValue();
            for (int i = 0; i < pool.size(); i++) {
                result.add(toGlobalQueuedTask(pool.get(i), e.getKey(), i + 1));
            }
        }
        return result;
    }

    /** 全局排队同池内上移/下移：只调整目标任务所在资源池内的顺序，跨池位置不变 */
    @Transactional
    public List<GlobalQueuedTask> reorderGlobalQueue(ReorderRequest req) {
        if (req == null || req.getTaskId() == null || req.getTaskId().isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        String direction = req.getDirection();
        if (!"up".equals(direction) && !"down".equals(direction)) {
            throw new IllegalArgumentException("direction 必须为 up / down");
        }

        List<GlobalQueuedTask> all = listGlobalQueued();
        String targetPool = null;
        boolean found = false;
        for (GlobalQueuedTask t : all) {
            if (t.getId().equals(req.getTaskId())) {
                targetPool = t.getNodePool();
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("排队任务不存在");
        }

        // 只调整目标任务所在资源池内的顺序
        Map<String, List<GlobalQueuedTask>> byPool = new LinkedHashMap<>();
        for (GlobalQueuedTask t : all) {
            byPool.computeIfAbsent(t.getNodePool(), k -> new ArrayList<>()).add(t);
        }
        List<GlobalQueuedTask> pool = byPool.get(targetPool);
        int idx = -1;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).getId().equals(req.getTaskId())) {
                idx = i;
                break;
            }
        }
        int targetIdx = "up".equals(direction) ? idx - 1 : idx + 1;
        if (targetIdx < 0) {
            throw new IllegalArgumentException("已在队首，无法上移");
        }
        if (targetIdx >= pool.size()) {
            throw new IllegalArgumentException("已在队尾，无法下移");
        }
        Collections.swap(pool, idx, targetIdx);

        // 重排全部全局任务的 queueSortIndex（按池分组、组内顺序写回 1..N），
        // 使调度器 findAllPendingWithLock 的排序与展示顺序一致
        int seq = 0;
        for (Map.Entry<String, List<GlobalQueuedTask>> e : byPool.entrySet()) {
            for (GlobalQueuedTask t : e.getValue()) {
                seq++;
                updateQueueSortIndex(t.getId(), seq);
            }
        }
        return listGlobalQueued();
    }

    /** 取消全局排队任务 */
    @Transactional
    public List<GlobalQueuedTask> cancelGlobalQueueTask(String taskId) {
        updateTaskStatus(taskId, "cancelled", null);
        return listGlobalQueued();
    }

    // ────────── helpers ──────────

    private ServerItem toServerItem(
            ComputeServer server,
            ServerMetricSnapshot snap,
            boolean includeTasks,
            KubernetesResourceDiagnosticsService.NodeHealthCollection nodeHealth
    ) {
        ServerItem item = new ServerItem();
        item.setServerIp(server.getServerIp());
        item.setHostname(server.getHostname());
        item.setEnabled(server.getEnabled());
        if (gpuObservationStore != null) {
            gpuObservationStore.fresh(server.getServerIp(), Instant.now())
                    .or(() -> gpuObservationStore.fresh(server.getK8sNodeName(), Instant.now()))
                    .ifPresent(observation -> item.setGpuDevices(observation.devices().stream()
                            .map(device -> new GpuDeviceItem(
                                    device.hostGpuIndex(), device.uuid(), device.modelName(),
                                    device.totalMemoryMiB(), device.freeMemoryMiB(),
                                    device.utilizationRate(), device.temperatureCelsius()))
                            .toList()));
        }

        if (snap != null) {
            item.setCpuRate(snap.getCpuRate());
            item.setMemRate(snap.getMemRate());
            item.setGpuRate(snap.getGpuRate());
            item.setDiskRate(snap.getDiskRate());
            item.setGpuMemRate(snap.getGpuMemRate());
            item.setNetworkIn(snap.getNetworkIn());
            item.setNetworkOut(snap.getNetworkOut());
            item.setGpuTemp(snap.getGpuTemp());
        }

        MetricsState metricsState = metricsState(snap);
        applyMetricsState(item, metricsState);
        KubernetesNodeHealth node = nodeFor(server, nodeHealth);
        applyNodeHealth(item, nodeHealth, node);
        boolean healthy = "fresh".equals(metricsState.status())
                && snap != null
                && "online".equals(snap.getStatus())
                && (snap.getDiskRate() == null || snap.getDiskRate() < 85.0)
                && nodeHealth != null
                && nodeHealth.available()
                && node != null
                && "healthy".equals(node.getHealthStatus());
        item.setStatus(healthy ? "online" : "warning");

        ServerSpecs specs = new ServerSpecs();
        // spec_* 字段优先（手动添加的服务器），为空时从 K8s 自动采集的容量字段兜底
        specs.setCpu(server.getSpecCpu() != null && !server.getSpecCpu().isBlank()
                ? server.getSpecCpu()
                : (server.getCpuCores() != null && server.getCpuCores() > 0
                   ? String.format("%.1f 核", server.getCpuCores()) : null));
        specs.setMemory(server.getSpecMemory() != null && !server.getSpecMemory().isBlank()
                ? server.getSpecMemory()
                : (server.getMemoryGib() != null && server.getMemoryGib() > 0
                   ? String.format("%.0f GiB", server.getMemoryGib()) : null));
        specs.setGpu(server.getSpecGpu() != null && !server.getSpecGpu().isBlank()
                ? server.getSpecGpu()
                : (server.getGpuCount() != null && server.getGpuCount() > 0
                   ? server.getGpuCount() + " × GPU" : null));
        specs.setOs(server.getSpecOs() != null && !server.getSpecOs().isBlank()
                ? server.getSpecOs() : null);
        item.setSpecs(specs);

        if (includeTasks) {
            // 服务器详情只展示已分配到本节点的任务（running=运行中，scheduled=已调度待启动）。
            // queued 状态的任务尚未分配节点（serverIp 为 null），不属于任何服务器，由全局排队页展示。
            List<TrainingExperimentVersion> trainingTasks = trainingRepo
                    .findByServerIpAndStatusIn(server.getServerIp(), List.of("running", "scheduled"));
            List<InferenceTask> inferenceTasks = inferenceRepo
                    .findByServerIpAndStatusIn(server.getServerIp(), List.of("running", "scheduled"));

            List<RunningTask> running = new ArrayList<>();
            List<QueuedTask> queued = new ArrayList<>();

            for (TrainingExperimentVersion t : trainingTasks) {
                if ("running".equals(t.getStatus())) {
                    running.add(toRunningTask(t));
                } else {
                    queued.add(toQueuedTask(t));  // 仅 scheduled 显示在"已调度待启动"列表
                }
            }
            for (InferenceTask t : inferenceTasks) {
                if ("running".equals(t.getStatus())) {
                    running.add(toRunningTask(t));
                } else {
                    queued.add(toQueuedTask(t));
                }
            }

            // 排序
            queued.sort(compareQueued);

            item.setRunningTasks(running);
            item.setQueuedTasks(queued);
            item.setRunTask(running.size());
            item.setWaitTask(queued.size());
        }

        return item;
    }

    private RunningTask toRunningTask(TrainingExperimentVersion t) {
        RunningTask r = new RunningTask();
        r.setId(t.getId());
        r.setName(t.getName() != null ? t.getName() : t.getId());
        r.setModel(t.getModelVersionId());
        r.setDataset(t.getDatasetVersionId());
        r.setStartTime(t.getStartedAt() != null ? FMT.format(t.getStartedAt()) : "");
        r.setProgress(t.getProgress() != null ? t.getProgress() : 0);
        return r;
    }

    private RunningTask toRunningTask(InferenceTask t) {
        RunningTask r = new RunningTask();
        r.setId(t.getId());
        r.setName(t.getName() != null ? t.getName() : t.getId());
        r.setModel(t.getModelVersionId());
        r.setDataset(t.getDatasetVersionId());
        r.setStartTime(t.getStartedAt() != null ? FMT.format(t.getStartedAt()) : "");
        r.setProgress(t.getProgress() != null ? t.getProgress() : 0);
        return r;
    }

    private QueuedTask toQueuedTask(TrainingExperimentVersion t) {
        QueuedTask q = new QueuedTask();
        q.setId(t.getId());
        q.setName(t.getName() != null ? t.getName() : t.getId());
        q.setModel(t.getModelVersionId());
        q.setDataset(t.getDatasetVersionId());
        q.setSubmitTime(t.getCreatedAt() != null ? FMT.format(t.getCreatedAt()) : "");
        q.setPriority(t.getPriority() != null ? t.getPriority() : "中");
        q.setQueueSortIndex(t.getQueueSortIndex() != null ? t.getQueueSortIndex() : 0);
        return q;
    }

    private QueuedTask toQueuedTask(InferenceTask t) {
        QueuedTask q = new QueuedTask();
        q.setId(t.getId());
        q.setName(t.getName() != null ? t.getName() : t.getId());
        q.setModel(t.getModelVersionId());
        q.setDataset(t.getDatasetVersionId());
        q.setSubmitTime(t.getCreatedAt() != null ? FMT.format(t.getCreatedAt()) : "");
        q.setPriority(t.getPriority() != null ? t.getPriority() : "中");
        q.setQueueSortIndex(t.getQueueSortIndex() != null ? t.getQueueSortIndex() : 0);
        return q;
    }

    private GlobalQueuedTask toGlobalQueuedTask(TrainingExperimentVersion t, String pool, int position) {
        GlobalQueuedTask q = new GlobalQueuedTask();
        q.setId(t.getId());
        q.setName(t.getName() != null ? t.getName() : t.getId());
        q.setModel(t.getModelVersionId());
        q.setDataset(t.getDatasetVersionId());
        q.setSubmitTime(t.getCreatedAt() != null ? FMT.format(t.getCreatedAt()) : "");
        q.setPriority(t.getPriority() != null ? t.getPriority() : "中");
        q.setQueueSortIndex(t.getQueueSortIndex() != null ? t.getQueueSortIndex() : 0);
        q.setStatus(t.getStatus() != null ? t.getStatus() : "queued");
        q.setNodePool(pool);
        q.setPositionInPool(position);
        return q;
    }

    /**
     * 全局排队分组键：优先取任务 nodeSelector 中 tss.ai/node-pool 标签的值（cpu/gpu/h100…）。
     * 该值来自任务自身的 selector，新增节点池（新标签值）无需改代码即可自动出现新分组。
     */
    private String resolvePoolKey(TrainingExperimentVersion task) {
        Map<String, String> selector = jobScheduler.resolveNodeSelector(task);
        if (selector == null || selector.isEmpty()) {
            return "custom";
        }
        String pool = selector.get("tss.ai/node-pool");
        if (pool != null && !pool.isBlank()) {
            return pool;
        }
        // 无 node-pool 标签的多标签 selector：用标准化后的完整选择器作为分组键
        return selector.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private static final Comparator<QueuedTask> compareQueued = (a, b) -> {
        if (a.getQueueSortIndex() != 0 && b.getQueueSortIndex() != 0) {
            return Integer.compare(a.getQueueSortIndex(), b.getQueueSortIndex());
        }
        if (a.getQueueSortIndex() != 0) return -1;
        if (b.getQueueSortIndex() != 0) return 1;
        // auto: priority desc, then submitTime asc
        int pa = PRIORITY_ORDER.getOrDefault(a.getPriority(), 2);
        int pb = PRIORITY_ORDER.getOrDefault(b.getPriority(), 2);
        if (pa != pb) return Integer.compare(pb, pa);
        return a.getSubmitTime().compareTo(b.getSubmitTime());
    };

    private List<QueuedTaskEntry> loadQueuedEntries(String serverIp) {
        List<QueuedTaskEntry> entries = new ArrayList<>();
        for (TrainingExperimentVersion t : trainingRepo.findByServerIpAndStatus(serverIp, "queued")) {
            entries.add(new QueuedTaskEntry(t.getId(), t.getQueueSortIndex() != null ? t.getQueueSortIndex() : 0,
                    t.getPriority() != null ? t.getPriority() : "中",
                    t.getCreatedAt() != null ? t.getCreatedAt() : Instant.EPOCH));
        }
        for (TrainingExperimentVersion t : trainingRepo.findByServerIpAndStatus(serverIp, "scheduled")) {
            entries.add(new QueuedTaskEntry(t.getId(), t.getQueueSortIndex() != null ? t.getQueueSortIndex() : 0,
                    t.getPriority() != null ? t.getPriority() : "中",
                    t.getCreatedAt() != null ? t.getCreatedAt() : Instant.EPOCH));
        }
        for (InferenceTask t : inferenceRepo.findByServerIpAndStatus(serverIp, "queued")) {
            entries.add(new QueuedTaskEntry(t.getId(), t.getQueueSortIndex() != null ? t.getQueueSortIndex() : 0,
                    t.getPriority() != null ? t.getPriority() : "中",
                    t.getCreatedAt() != null ? t.getCreatedAt() : Instant.EPOCH));
        }
        for (InferenceTask t : inferenceRepo.findByServerIpAndStatus(serverIp, "scheduled")) {
            entries.add(new QueuedTaskEntry(t.getId(), t.getQueueSortIndex() != null ? t.getQueueSortIndex() : 0,
                    t.getPriority() != null ? t.getPriority() : "中",
                    t.getCreatedAt() != null ? t.getCreatedAt() : Instant.EPOCH));
        }
        // 按当前排序规则排序
        entries.sort((a, b) -> {
            if (a.sortIndex() != 0 && b.sortIndex() != 0) return Integer.compare(a.sortIndex(), b.sortIndex());
            if (a.sortIndex() != 0) return -1;
            if (b.sortIndex() != 0) return 1;
            int pa = PRIORITY_ORDER.getOrDefault(a.priority(), 2);
            int pb = PRIORITY_ORDER.getOrDefault(b.priority(), 2);
            if (pa != pb) return Integer.compare(pb, pa);
            return a.createdAt().compareTo(b.createdAt());
        });
        return entries;
    }

    private void updateQueueSortIndex(String taskId, int index) {
        trainingRepo.findById(taskId).ifPresent(t -> {
            t.setQueueSortIndex(index);
            trainingRepo.save(t);
        });
        inferenceRepo.findById(taskId).ifPresent(t -> {
            t.setQueueSortIndex(index);
            inferenceRepo.save(t);
        });
    }

    private void updateTaskPriority(String taskId, String priority) {
        trainingRepo.findById(taskId).ifPresent(t -> {
            t.setPriority(priority);
            t.setQueueSortIndex(0);
            trainingRepo.save(t);
        });
        inferenceRepo.findById(taskId).ifPresent(t -> {
            t.setPriority(priority);
            t.setQueueSortIndex(0);
            inferenceRepo.save(t);
        });
    }

    private void updateTaskStatus(String taskId, String status, String errorMessage) {
        trainingRepo.findById(taskId).ifPresent(t -> {
            if (t.getStatus() != null && java.util.Set.of("success", "failed", "stopped", "cancelled").contains(t.getStatus())) return;
            t.setStatus(status);
            t.setQueueSortIndex(0);
            if (errorMessage != null) t.setErrorMessage(errorMessage);
            trainingRepo.save(t);
        });
        inferenceRepo.findById(taskId).ifPresent(t -> {
            t.setStatus(status);
            t.setQueueSortIndex(0);
            if (errorMessage != null) t.setErrorMessage(errorMessage);
            inferenceRepo.save(t);
        });
    }

    private void cancelQueuedTasksOnServer(String serverIp) {
        for (String status : List.of("queued", "scheduled")) {
            for (TrainingExperimentVersion t : trainingRepo.findByServerIpAndStatus(serverIp, status)) {
                t.setStatus("cancelled");
                t.setQueueSortIndex(0);
                trainingRepo.save(t);
            }
            for (InferenceTask t : inferenceRepo.findByServerIpAndStatus(serverIp, status)) {
                t.setStatus("cancelled");
                t.setQueueSortIndex(0);
                inferenceRepo.save(t);
            }
        }
    }

    private void autoReorderQueued(String serverIp) {
        List<QueuedTaskEntry> entries = loadQueuedEntries(serverIp);
        for (int i = 0; i < entries.size(); i++) {
            QueuedTaskEntry e = entries.get(i);
            if (e.sortIndex() == 0) {
                updateQueueSortIndex(e.taskId(), 0); // keep auto
            }
        }
    }

    record QueuedTaskEntry(String taskId, int sortIndex, String priority, Instant createdAt) {}

    private boolean isServerHealthy(
            ServerMetricSnapshot snapshot,
            KubernetesResourceDiagnosticsService.NodeHealthCollection nodeHealth,
            KubernetesNodeHealth node
    ) {
        return snapshot != null
                && "fresh".equals(metricsState(snapshot).status())
                // 告警(warning)只代表资源使用率高，节点仍在线，应计入在线数
                && ("online".equals(snapshot.getStatus()) || "warning".equals(snapshot.getStatus()))
                && (snapshot.getDiskRate() == null || snapshot.getDiskRate() < 85.0)
                && nodeHealth != null
                && nodeHealth.available()
                && node != null
                && "healthy".equals(node.getHealthStatus());
    }

    private KubernetesNodeHealth nodeFor(
            ComputeServer server,
            KubernetesResourceDiagnosticsService.NodeHealthCollection nodeHealth
    ) {
        if (nodeHealth == null || !nodeHealth.available()) {
            return null;
        }
        String nodeName = server.getK8sNodeName() != null && !server.getK8sNodeName().isBlank()
                ? server.getK8sNodeName()
                : server.getServerIp();
        return nodeHealth.nodesByName().get(nodeName);
    }

    private void applyNodeHealth(
            ServerItem item,
            KubernetesResourceDiagnosticsService.NodeHealthCollection nodeHealth,
            KubernetesNodeHealth node
    ) {
        if (nodeHealth == null || !nodeHealth.available() || node == null) {
            item.setNodeHealthStatus("unavailable");
            return;
        }
        item.setNodeReady(node.getReady());
        item.setNodeUnschedulable(node.getUnschedulable());
        item.setNodeMemoryPressure(node.getMemoryPressure());
        item.setNodeDiskPressure(node.getDiskPressure());
        item.setNodePidPressure(node.getPidPressure());
        item.setNodeHealthStatus(node.getHealthStatus());
    }

    private MetricsState metricsState(ServerMetricSnapshot snapshot) {
        if (snapshot == null || snapshot.getLastHeartbeat() == null) {
            return new MetricsState(
                    "unavailable",
                    snapshot == null ? null : snapshot.getLastHeartbeat(),
                    snapshot == null ? null : snapshot.getUpdatedAt(),
                    "尚无成功的指标采样"
            );
        }

        Instant lastSuccess = snapshot.getLastHeartbeat();
        Instant lastAttempt = snapshot.getUpdatedAt();
        long staleAfterMs = Math.max(60_000L, computeProperties.getCollectIntervalMs() * 2L);
        if (lastSuccess.isBefore(Instant.now().minusMillis(staleAfterMs))) {
            return new MetricsState("stale", lastSuccess, lastAttempt, "指标已过期，当前显示最近一次成功值");
        }
        if (lastAttempt != null && lastAttempt.isAfter(lastSuccess)) {
            return new MetricsState(
                    "temporarily_unavailable",
                    lastSuccess,
                    lastAttempt,
                    "最近一次采集失败，当前显示最近一次成功值"
            );
        }
        return new MetricsState("fresh", lastSuccess, lastAttempt, "指标采集正常");
    }

    private void applyMetricsState(ServerItem item, MetricsState state) {
        item.setMetricsStatus(state.status());
        item.setMetricsLastSuccessAt(state.lastSuccessAt());
        item.setMetricsLastAttemptAt(state.lastAttemptAt());
        item.setMetricsMessage(state.message());
    }

    private void applyMetricsState(MetricsResponse response, MetricsState state) {
        response.setMetricsStatus(state.status());
        response.setMetricsLastSuccessAt(state.lastSuccessAt());
        response.setMetricsLastAttemptAt(state.lastAttemptAt());
        response.setMetricsMessage(state.message());
    }

    record MetricsState(
            String status,
            Instant lastSuccessAt,
            Instant lastAttemptAt,
            String message
    ) {
    }

    private MetricPoint makePoint(int tickIndex, Instant time, String type, Double value) {
        MetricPoint p = new MetricPoint();
        p.setTickIndex(tickIndex);
        p.setFullTime(FMT.format(time));
        p.setTime(FMT.format(time).substring(5)); // "MM-dd HH"
        p.setType(type);
        p.setValue(value == null ? null : Math.round(value * 10.0) / 10.0);
        return p;
    }

    private Double averageAvailable(List<Double> values) {
        DoubleSummaryStatistics summary = values.stream()
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();
        return summary.getCount() == 0 ? null : summary.getAverage();
    }

    private boolean isValidIp(String ip) {
        try {
            InetAddress.getAllByName(ip);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
