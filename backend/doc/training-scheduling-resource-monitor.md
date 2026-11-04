# TSS 平台训练调度与资源监控模块说明

> 交付文档：整合「训练调度」「GPU 节点接入」「资源监控」三个模块的实现细节，供部署、运维与二次开发参考。

## 目录

- [1. 模块概述](#1-模块概述)
- [2. 训练调度模块](#2-训练调度模块)
- [3. GPU 节点接入](#3-gpu-节点接入)
- [4. 资源监控模块](#4-资源监控模块)
- [5. 部署前提与集群现状](#5-部署前提与集群现状)
- [6. 相关文件索引](#6-相关文件索引)

---

## 1. 模块概述

| 模块 | 核心职责 | 主要实现 |
|---|---|---|
| **训练调度** | 训练任务的节点分配、排队、K8s Job 提交与状态回同步 | `JobScheduler`、`TrainingExecutorRouter`、`KubernetesTrainingJobMonitor`、`KubernetesJobManifestBuilder` |
| **资源监控** | K8s 节点自动发现、CPU/内存/GPU 指标采集、REST API 供前端展示 | `ResourceMonitorService`、`ServerMetricsCollector`、`ResourceMonitorController`、`ComputeProperties` |

两个模块共用同一套 K8s 集群信息（节点、容量、在线状态），`ServerMetricsCollector` 采集到的节点数据同时供资源监控展示和调度器分配节点使用。

---

## 2. 训练调度模块

### 2.1 训练任务状态机与流转

任务状态：`pending`（创建）→ `scheduled`（已分配节点）或 `queued`（排队等资源）→ `running`（训练中）→ `success` / `failed` / `stopped` / `cancelled`（终态）。

```
用户创建任务（status=pending, progress=0）
   │
   ▼ 事务提交后（afterCommit 独立线程）执行 scheduleOrStart()
   ├─ 有可用节点 ──→ bindTask() → status=scheduled
   │                    → TrainingExecutorRouter.start() → 生成并 kubectl apply K8s Job
   │                    → 服务器详情页"排队中"可见
   ├─ 无可用节点 ──→ enqueueTask() → status=queued
   │                    → 每 10 秒 dispatchQueuedTasks() 重试分配
   └─ 旧版/本地链路 ──→ 直接 executorRouter.start()
   │
   ▼ K8s Pod 启动后，KubernetesTrainingJobMonitor 每 30 秒轮询
   ├─ Job active → status=running（progress 至少 10）
   └─ Job succeeded / failed → status=success / failed
```

**状态与进度对应**（`TrainingExperimentService.progressOf`）：

| 状态 | progress |
|---|---|
| pending / queued | 0 |
| scheduled | 5 |
| running | 50（回调上报实时进度，只增不减） |
| success | 100 |
| failed / stopped / cancelled | 0 |

**停止训练**：仅 `queued` / `scheduled` / `running` 状态可停止，调用 `TrainingExecutorRouter.stop()`，任务置为 `stopped`。

**取消排队**：排队中的任务（`pending` / `queued`，未分配节点）可通过资源监控接口取消（`DELETE /servers/{serverIp}/queue/{taskId}` 或 `DELETE /queue/{taskId}`），任务置为 `cancelled` 并退出全局/服务器排队。`cancelled` 自 V55 起被数据库状态约束 `ck_training_experiment_status` 允许。

### 2.2 调度决策算法（`JobScheduler.assignNode`）

`scheduleOrStart` 通过 `assignNodeForTraining(task, nodeSelector)` 选择节点，核心逻辑（私有方法 `assignNode`）：

1. **筛选节点**：`status = online` 且 `enabled = true` 的节点；
2. **按 nodeSelector 匹配**：CPU 默认使用 `tss.ai/node-pool: cpu`；当前单卡 GPU 任务必须使用 `tss.ai/accelerator: nvidia`。节点标签缺失或损坏时按“不匹配”处理，不允许绕过选择器；
3. **统计已占用资源**：汇总该节点上 `running` + `scheduled` 任务申请的 CPU / 内存 / GPU；
4. **计算剩余容量**：`节点容量 − 已占用`；
5. **选节点**：优先满足任务需求（CPU、内存、GPU），在满足的节点中选**余量最多**的。

节点容量来自 `compute_server` 表的 `cpu_cores` / `memory_gib` / `gpu_count`，由资源监控模块自动采集写入。

### 2.3 节点绑定与排队

- **bindTask**：任务绑定节点后写 `server_ip`，状态置 `scheduled`，随后提交 K8s Job；
- **enqueueTask**：无可用节点时任务进入排队，写入 `queue_sort_index`、`priority`；
- **并发保护**：`bindTask` 失败说明已被其他线程抢先绑定，此时不再重复提交 Job，避免同一任务跑两份。

### 2.4 排队任务重调度

`JobScheduler.dispatchQueuedTasks()`：`@Scheduled(fixedDelay = 10_000)`，每 10 秒扫描排队任务，有资源时重新走分配流程（按优先级/入队顺序）。

### 2.5 K8s Job 状态回同步（`KubernetesTrainingJobMonitor`）

`@Scheduled(fixedDelayString = "${training.kubernetes.monitor-interval-ms:30000}")`，每 30 秒轮询非终态任务对应的 K8s Job：

| Job 状态 | 处理 |
|---|---|
| `status.succeeded > 0` | 任务置 `success`，progress=100，若未发布模型则自动触发模型发布 |
| `status.failed > 0` | 查询 Pod 失败原因（`fetchPodFailureReason`），任务置 `failed` 并记录原因 |
| `active > 0` 且任务非 running | 任务置 `running`，progress = `max(当前, 10)` |

### 2.6 训练调度相关配置

| 配置项 | 环境变量 | 默认值 |
|---|---|---|
| `training.kubernetes.monitor-interval-ms` | `TRAINING_K8S_MONITOR_INTERVAL_MS` | `30000` |
| `training.kubernetes.enabled` | `TRAINING_K8S_ENABLED` | `true` |
| 节点池标签 | `TRAINING_K8S_*` | 默认 CPU 节点 `tss.ai/node-pool: cpu` |

---

## 3. GPU 节点接入

### 3.1 机器加入集群

```bash
# GPU 服务器加入 K8s 集群
kubeadm join <master-ip>:6443 --token <token> ...
```

### 3.2 安装 NVIDIA 组件并验证

```bash
nvidia-smi        # 确认 GPU 可见
```

### 3.3 打节点标签（调度器匹配依据）

```bash
# GPU 能力使用附加标签，不能覆盖节点原有 CPU 池标签
kubectl label node <gpu-node-name> tss.ai/accelerator=nvidia
```

### 3.4 后端自动发现

`ServerMetricsCollector` 每 30 秒 `kubectl get nodes -o json` 自动扫描节点，解析节点名、IP、容量（CPU 核数 / 内存 / GPU 数量），自动写入 `compute_server` 表。**无需手动注册节点**，新 GPU 节点加入集群后自动被识别。

### 3.5 导入训练镜像

GPU 节点需要能拿到训练 worker 镜像：

```bash
docker save tss-cv-worker:local | ctr -n k8s.io images import -
# 或配置 imagePullSecret 后由 kubelet 自动拉取
```

完成以上步骤后，`assignNode` 计算资源时会自动把 GPU 任务分配到该节点。

---

## 4. 资源监控模块

### 4.1 REST API 一览

所有接口位于 `/api/resource-monitor`，与前端 `resourceMonitor.ts` 完全对应：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/resource-monitor/summary` | 登录用户 | 集群总览（节点数、任务数、平均 GPU 使用率） |
| GET | `/api/resource-monitor/servers?keyword=&status=` | 登录用户 | 服务器列表（含实时 CPU/内存/GPU 使用率） |
| POST | `/api/resource-monitor/servers` | 管理员 | 手动添加服务器 |
| GET | `/api/resource-monitor/servers/{serverIp}` | 登录用户 | 服务器详情（含运行中/排队任务列表） |
| DELETE | `/api/resource-monitor/servers/{serverIp}` | 管理员 | 删除服务器（软删除，有运行任务时拒绝） |
| PUT | `/api/resource-monitor/servers/{serverIp}/enabled` | 管理员 | 启用/禁用服务器（`enabled`：false=不再分配新任务，已跑任务继续） |
| GET | `/api/resource-monitor/servers/{serverIp}/metrics?interval=` | 登录用户 | 资源使用趋势（支持 1min/10min/1hour/1day） |
| PUT | `/api/resource-monitor/servers/{serverIp}/queue/reorder` | 管理员 | 排队任务上移/下移 |
| PUT | `/api/resource-monitor/servers/{serverIp}/queue/priority` | 管理员 | 修改排队任务优先级 |
| DELETE | `/api/resource-monitor/servers/{serverIp}/queue/{taskId}` | 管理员 | 取消排队任务 |
| GET | `/api/resource-monitor/queue` | 登录用户 | 全局排队列表（跨服务器，按资源池 `nodePool` 分组） |
| PUT | `/api/resource-monitor/queue/reorder` | 管理员 | 全局排队同资源池内上移/下移 |
| DELETE | `/api/resource-monitor/queue/{taskId}` | 管理员 | 取消全局排队任务 |

> **服务器详情 vs 全局排队**：服务器详情只展示已分配节点（`server_ip` 非空）的 `running` / `scheduled` 任务；`queued` / `pending`（`server_ip` 为空）的任务不属于任何服务器，统一在「全局排队」页按资源池展示与管理。全局排队按任务的 `nodeSelector`（`tss.ai/node-pool` 标签值）分组，新增节点池（新标签值）无需改代码自动出现新分组。

> **删除 vs 启用/禁用**：删除（`deleted=true`）为软删除。对仍存在于 K8s 集群的节点，`ServerMetricsCollector` 每 30 秒自动重新注册（`deleted=false`），删除会"失效"；**要让节点真正下线（不再分配新任务），应使用 `PUT /servers/{serverIp}/enabled` 禁用**（`enabled=false`），调度器 `assignNode` 会跳过禁用节点，已在其上运行的任务不受影响。

### 4.2 数据库设计（Flyway 迁移）

| 迁移 | 表 / 变更 | 作用 |
|---|---|---|
| V36 | `compute_server` | 服务器注册表（IP 主键、主机名、规格、k8s_node_name、状态） |
| V37 | `server_metric_snapshot` | 实时指标快照（每台服务器一行，定时更新） |
| V38 | `training_experiment_version` / `inference_task` | 加 `server_ip`、`queue_sort_index`、`priority`（默认"中"） |
| V39 | `server_metric_history` | 指标历史数据（趋势图数据源） |
| V40 | `compute_server` + `cpu_cores`、`memory_gib` | 节点容量字段 |
| V42 | `compute_server` + `enabled`、`k8s_labels_json` | 启用开关、节点标签快照 |
| V47 | `compute_server` + `gpu_count` | GPU 数量 |
| V55 | `training_experiment_version` | 状态约束 `ck_training_experiment_status` 增加 `cancelled`（取消排队） |

`compute_server` 表结构（V36 建表 + V40/42/47 加列后）：

| 列 | 类型 | 说明 |
|---|---|---|
| `server_ip` | VARCHAR(45) PK | 服务器 IP |
| `hostname` | VARCHAR(128) | 主机名 / K8s 节点名 |
| `status` | VARCHAR(16) | `online` / `warning` |
| `spec_cpu` / `spec_memory` / `spec_gpu` / `spec_os` | VARCHAR | 规格描述（OS 默认 Ubuntu 22.04） |
| `k8s_node_name` | VARCHAR(256) | K8s 节点名 |
| `enabled` | BOOLEAN | 是否参与调度 |
| `k8s_labels_json` | TEXT | 节点标签快照 |
| `cpu_cores` | DOUBLE | CPU 总核数（容量） |
| `memory_gib` | DOUBLE | 内存总量 GiB（容量） |
| `gpu_count` | INT | GPU 数量 |
| `deleted` | BOOLEAN | 软删除标记 |
| `created_at` / `updated_at` | TIMESTAMP | 时间戳 |

### 4.3 节点自动发现

应用启动时执行 `kubectl get nodes -o json`，解析每个节点：

- `metadata.name` → K8s 节点名；
- `status.addresses` → IP 地址；
- `status.capacity` → CPU 总核数、内存总量、GPU 数量（`nvidia.com/gpu`）；
- `status.conditions` → 在线状态。

新节点自动写入 `compute_server`，已有节点更新状态与容量。

### 4.4 指标定时采集（`ServerMetricsCollector`）

`@Scheduled(fixedDelayString = "${resource-monitor.metrics.collect-interval-ms:30000}")`，每 30 秒执行：

1. **获取节点容量**：`kubectl get nodes -o json`；
2. **获取实时用量**：调用 K8s Metrics API `/apis/metrics.k8s.io/v1beta1/nodes`，解析 CPU（支持 nanocores `n` 和 millicores `m` 两种格式）与内存用量；失败时回退 `kubectl top nodes`；
3. **计算百分比**：`CPU 用量 / CPU 总核数 × 100`，`内存用量 / 内存总量 × 100`；
4. **采集 GPU**（节点有 GPU 时）：通过 Kubernetes Node 的 `InternalIP` 查询 NVIDIA **DCGM Exporter** 的 Prometheus 端点（默认端口 9400），解析 `DCGM_FI_DEV_GPU_UTIL`、`DCGM_FI_DEV_FB_USED`、`DCGM_FI_DEV_FB_FREE`（兼容 `DCGM_FI_DEV_FB_TOTAL`）、`DCGM_FI_DEV_GPU_TEMP`；
5. **写快照**：更新 `server_metric_snapshot`；
6. **写历史**：追加 `server_metric_history`（用于趋势图）；
7. **状态判定**：任一指标使用率 ≥ 85% → `warning`，否则 `online`；
8. **清理过期数据**：定期删除超过保留期（默认 7 天）的历史指标。

### 4.5 资源监控配置项

| 配置项 | 环境变量 | 默认值 |
|---|---|---|
| `resource-monitor.metrics.collect-interval-ms` | `RESOURCE_MONITOR_COLLECT_INTERVAL_MS` | `30000` |
| `resource-monitor.metrics.dcgm-exporter-port` | `RESOURCE_MONITOR_DCGM_EXPORTER_PORT` | `9400` |
| `resource-monitor.metrics.gpu-node-label` | `RESOURCE_MONITOR_GPU_NODE_LABEL` | `tss.ai/node-pool=gpu` |
| `resource-monitor.metrics.metrics-retention-days` | `RESOURCE_MONITOR_METRICS_RETENTION_DAYS` | `7` |

---

## 5. 部署前提与集群现状

### 5.1 前提条件

1. **Metrics Server**：资源监控采集 CPU/内存必需。验证：`kubectl top nodes` 能正常输出；
2. **RBAC 权限**：后端 ServiceAccount 需要 `metrics.k8s.io` 读取权限；
3. **GPU 监控**（可选）：需部署 NVIDIA **DCGM Exporter**（DaemonSet）；纯 CPU 集群可跳过。标准内网部署使用 `deploy/tss-aiplatform-internal/platform/scripts/install-dcgm-exporter.sh`，不安装完整 GPU Operator。

### 5.2 集群现状

| 节点 | 角色 | 规格 | 状态 |
|---|---|---|---|
| `k8s-master` | control-plane | ~3 核 4G | Ready，v1.28.2，kubectl 有 admin 权限 |
| `k8s-node1` | worker | 4 核 8G | Ready，v1.28.2 |

- **Metrics Server**：已在 master 部署（镜像 `registry.aliyuncs.com/google_containers/metrics-server:v0.7.2`，加 `--kubelet-insecure-tls` 参数）；
- **GPU**：没有 DCGM Exporter 或某项指标缺失时，接口返回 `null`，前端显示“无数据”，不得用 `0` 代替；真实采样值为零时仍显示 `0%`；
- **后端部署**：Docker 容器运行在 `k8s-master`，GitHub Actions 自动构建推送。

---

## 6. 相关文件索引

### 训练调度

| 文件 | 作用 |
|---|---|
| `service/JobScheduler.java` | 节点分配（`assignNode`）、绑定、排队、10 秒重调度 |
| `service/TrainingExperimentService.java` | 任务创建、状态流转、`scheduleOrStart` |
| `training/TrainingExecutorRouter.java` | 选择本地 / K8s 执行器 |
| `training/KubernetesTrainingExecutor.java` | K8s Job 提交与停止 |
| `training/KubernetesTrainingJobMonitor.java` | 30 秒轮询 Job 状态并回同步 |
| `training/KubernetesJobManifestBuilder.java` | 生成 K8s Job manifest |

### 资源监控

| 文件 | 作用 |
|---|---|
| `service/ResourceMonitorService.java` | 核心业务逻辑（CRUD、排队管理、趋势查询） |
| `service/ServerMetricsCollector.java` | 定时采集（节点发现 + CPU/内存/GPU 指标） |
| `controller/ResourceMonitorController.java` | `/api/resource-monitor/*` REST 入口 |
| `config/ComputeProperties.java` | 资源监控配置类 |
| `entity/ComputeServer.java`、`ServerMetricSnapshot.java`、`ServerMetricHistory.java` | 实体 |
| `dto/resource/*.java` | 10 个请求/响应 DTO |

### 数据库

| 文件 | 作用 |
|---|---|
| `db/migration/V36__compute_server.sql` | compute_server 建表 |
| `db/migration/V37__server_metric_snapshot.sql` | 指标快照 |
| `db/migration/V38__task_queue_fields.sql` | 任务排队字段 |
| `db/migration/V39__server_metric_history.sql` | 指标历史 |
| `db/migration/V40__compute_server_capacity.sql` / `V42` / `V47` | 容量 / 标签 / GPU 字段 |
| `db/migration/V55__training_status_cancelled.sql` | 允许 `cancelled` 状态 |
