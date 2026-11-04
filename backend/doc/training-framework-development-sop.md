# TSS Platform 通用训练框架开发 SOP

> 文档版本：v1.0  
> 编制日期：2026-07-21  
> 适用仓库：`/opt/tss-platform`（backend、frontend、k8s、测试资产）  
> 第一阶段目标：保留 `image_text_consistency_fusion_logreg`，新增 `yolo_object_detection`，完成真实训练、模型发布和推理闭环。  
> 当前结论：**允许开始框架设计和单元开发；在 P0 阻塞项关闭前，不允许标记为“可正式提测”。**

---

## 1. SOP目标

本SOP用于避免以下局部修补方式：

- 在前端增加一个固定下拉选项；
- 在Java后端再增加一组 `if/else`；
- 在Python Worker里再复制一套固定命令；
- 任务仅在数据库中变为 `success`，实际没有使用上传的模型、数据和代码；
- YOLO训练成功，但产物无法发布或推理仍使用内置模型；
- 为解决单个方案而破坏现有审批、状态、权限和训练推理衔接。

本次改造的核心原则是：

> 平台固定“安全执行协议”，训练方案以受控模板的方式扩展；算法来自用户选择且经过审批的代码版本，不能由Worker内置演示程序替代。

---

## 2. 开工前真实基线

### 2.1 审计时间与代码基线

审计时间：2026-07-21。

| 仓库 | 当前分支 | HEAD | 状态 |
| --- | --- | --- | --- |
| 后端根仓库 | `backend` | `2a338b5f380e9a1ab07b053cf87416646b10ad75` | 有未提交修改和未跟踪文件 |
| 前端子仓库 | `frontend-dev` | `5a562d18346811203e090b8f734164d929b783af` | 有未提交修改和未跟踪文件 |

后端现有未提交修改主要集中在推理脚本、推理任务服务及相关测试；前端未提交修改主要集中在推理工作台、训练详情及服务类型。开始通用训练框架前，必须先确认这些修改的归属并单独提交，禁止混入训练框架提交。

禁止为清理工作区执行：

```bash
git reset --hard
git checkout -- .
git clean -fd
```

### 2.2 环境健康状态

执行：

```bash
cd /opt/tss-platform
bash backend/scripts/env-health-check.sh
```

2026-07-21实测结果：

| 项目 | 结果 | 说明 |
| --- | --- | --- |
| PostgreSQL | PASS | 容器运行且可接受连接 |
| MinIO | PASS | 健康接口返回200 |
| MLflow | PASS | HTTP返回200 |
| Backend | PASS | 8080响应，systemd为active |
| kind/K8s | PASS | 1个CPU节点Ready，训练Namespace和3个Service存在 |

结论：平台基础服务可以支持框架开发和CPU级冒烟测试。

### 2.3 计算资源现状

second服务器当前资源：

| 资源 | 当前值 | 影响 |
| --- | --- | --- |
| CPU | 4 vCPU（2核4线程） | 只能做小数据、少epoch的CPU冒烟，不适合正式YOLO训练和双任务性能验收 |
| 内存 | 16GB | 可支持开发级CPU训练，但需避免同时构建多个大镜像 |
| GPU | 无 | 不能验证GPU调度、显存信息和双GPU并发 |
| 根盘可用空间 | 约21GB | 构建新的PyTorch/CUDA镜像存在磁盘耗尽风险 |
| kind节点 | 1个CPU控制平面节点 | 当前没有 `nvidia.com/gpu` 可调度资源 |

当前K8s训练配额：

```text
requests.cpu: 3
requests.memory: 8Gi
limits.cpu: 4
limits.memory: 12Gi
pods: 10
jobs: 50
```

结论：

- 可以在second上完成通用框架、接口、Worker协议和小型CPU闭环开发；
- GPU相关功能可以编写单元测试和Job YAML测试；
- 正式GPU训练、GPU资源展示和两个GPU任务并发必须在后续GPU计算节点上实测。

### 2.4 构建与测试基线

后端完整测试：

```bash
cd /opt/tss-platform/backend
./mvnw test
```

2026-07-21实测：**通过**，包括Flyway从空库迁移至V31的集成测试。

前端类型检查：

```bash
cd /opt/tss-platform/frontend
npm run tsc
```

2026-07-21实测：**失败，共12个TypeScript错误**，涉及：

- `src/constants/mockData.ts`
- `src/pages/task/detail/[id].tsx`
- `src/pages/task/list/index.tsx`
- `src/pages/task/trainingCode/list/index.tsx`
- `src/services/model.ts`
- `src/services/resourceMonitor.ts`

这些错误必须先修复，或者登记为明确的历史基线并保证本次开发不新增错误。正式提测要求 `npm run tsc` 为0错误。

### 2.5 数据库基线

- 现有数据库：PostgreSQL `tss`
- 当前Flyway版本：V31
- Hibernate：`ddl-auto=validate`
- 新增实体字段前必须先增加V32及后续Flyway迁移；禁止修改已经执行的V1～V31文件。

### 2.6 Worker与镜像基线

| 镜像 | 当前标识 | 状态 |
| --- | --- | --- |
| 训练Worker | `tss-training-worker:local` | 只支持固定Logreg Profile |
| 推理Worker | `tss-inference-worker:local` | 已存在，但使用本地可变标签 |
| YOLO CPU镜像 | `tss/yolo-trainer-cpu:latest` | 不可用，导入Ultralytics时报缺少 `libGL.so.1` |

额外问题：

- 上述镜像均无可追溯的仓库RepoDigest；
- `tss/yolo-trainer-cpu:latest` 的可复现Dockerfile不在当前仓库；
- YOLO镜像入口写死为 `/app/train.py`，不符合执行已选择训练代码的要求；
- 现有训练Job固定选择CPU节点，未支持 `nvidia.com/gpu`；
- 现有训练Worker使用固定命令和固定产物文件名。

### 2.7 标准测试资产基线

当前机器可找到：

- `/opt/inference_java2/model/yolov11n/yolo11n.pt`
- `/opt/inference_java2/samples/yolo-bus.jpg`
- `/opt/inference_java2/samples/yolo-zidane.jpg`

但这些文件尚未形成平台内可追溯、带版本号和SHA-256的标准测试资产。当前仓库中也没有合规的固定YOLO训练数据包和标准训练代码包。

现有 `local-cpu-closed-loop-smoke.sh` 使用内置运行时模型 `yolov8n`，不能作为本次“训练产物进入推理”的验收依据。

---

## 3. 开工门槛（Go/No-Go）

### 3.1 可以立即开展的工作

- 训练方案Schema与接口契约设计；
- 后端训练方案加载器和查询接口；
- 任务执行快照数据结构与Flyway迁移；
- 通用Worker单元测试；
- K8s CPU/GPU Job YAML生成测试；
- Logreg兼容模板迁移；
- YOLO标准资产准备和CPU小数据冒烟。

### 3.2 P0阻塞项

以下事项关闭前，不得进行正式YOLO提测：

- [ ] 现有后端和前端未提交改动已经确认归属、提交并记录Commit；
- [ ] 前端 `npm run tsc` 为0错误；
- [ ] 通用训练方案契约评审完成并冻结v1；
- [ ] YOLO Worker镜像可从仓库Dockerfile重复构建；
- [ ] 修复YOLO镜像缺少 `libGL.so.1`；
- [ ] 镜像使用不可变版本或镜像摘要，不以 `latest` 作为提测依据；
- [ ] 标准基础模型、数据集、训练代码、两张推理图片均有固定版本和SHA-256；
- [ ] CPU闭环至少成功1次；
- [ ] GPU正式测试前，GPU节点、NVIDIA驱动、Container Toolkit和Device Plugin均验证通过；
- [ ] 用于构建镜像的服务器磁盘空间满足要求，或改为在专用构建机/CI构建后推送镜像仓库。

总体判定：

```text
通用框架开发：GO（完成代码基线隔离后开始）
YOLO CPU开发冒烟：CONDITIONAL GO
YOLO正式提测：NO-GO
GPU并发验收：NO-GO
```

---

## 4. 全局设计约束

任何局部实现都必须满足以下约束。

### 4.1 真实性

1. 基础模型、数据集和训练代码必须来自任务选择的版本。
2. 文件缺失或SHA-256不一致时必须失败，禁止回退到内置模型、缓存模型或在线下载模型。
3. 超参数必须真正传入训练程序并记录最终生效值。
4. 成功状态只能由真实进程退出码、必需产物校验和上传结果共同决定。
5. 模型发布必须使用训练产物，不得复制基础模型冒充新版本。

### 4.2 安全性

1. 平台不接受用户自由填写Shell命令。
2. 入口文件必须是代码包内的相对路径，并通过路径穿越校验。
3. Worker使用参数数组启动进程，不使用 `/bin/sh -c`。
4. 训练方案和运行镜像由平台管理，用户不能指定任意镜像、HostPath、特权模式或ServiceAccount。
5. 代码准入必须绑定代码包SHA-256、校验记录、管理员审批和方案版本。
6. 代码或方案发生变化后，旧审批不得继续使用。
7. 理想架构中，执行用户代码的容器不持有MinIO密钥和内部回调令牌；下载、上传和回调由可信容器完成。

### 4.3 可追溯与可复现

每个训练任务必须固化以下快照：

- trainingPlanId和trainingPlanVersion；
- 基础模型版本、对象路径和SHA-256；
- 数据集版本、对象路径和SHA-256；
- 代码版本、入口文件和SHA-256；
- 最终超参数；
- 资源规格；
- Worker镜像和镜像摘要；
- 输出契约版本。

后续修改训练方案不能改变历史任务的含义。

### 4.4 状态一致性

- `pending`：待提交；
- `queued`：Job已提交，等待Pod启动或资源调度；
- `running`：训练进程已经启动；
- `success`：进程成功、产物校验成功、产物上传完成；
- `failed`：任何必需步骤失败；
- `stopped`：用户停止且对应Job已经终止。

Worker回调与K8s Monitor都要幂等。进度只能单调递增；失败和停止保留最后进度。

### 4.5 向后兼容

- 原 `image_text_consistency_fusion_logreg` 必须迁移为一个训练方案模板并继续可用；
- 已有训练任务详情和历史模型不可失效；
- 数据库迁移只做增量，不重写历史迁移；
- 新框架应有功能开关，发生问题时可以切回旧Logreg执行路径。

---

## 5. 目标架构

### 5.1 训练方案定义

第一阶段建议由后端加载版本控制下的YAML：

```text
backend/src/main/resources/training-plans/
├── image_text_consistency_fusion_logreg-v1.yaml
└── yolo_object_detection-v1.yaml
```

训练方案至少声明：

```yaml
id: yolo_object_detection
version: v1
displayName: YOLO目标检测训练
enabled: true

runtime:
  runtimeId: ultralytics-cpu-v1
  workerImage: registry/tss-yolo-worker@sha256:REPLACE_ME

code:
  entryFile: train.py

model:
  required: true
  inputFormats: [YOLO_PT]
  outputFormat: YOLO_PT

dataset:
  taskType: OBJECT_DETECTION
  annotationFormat: YOLO
  requiredFiles: [data.yaml]

parameters:
  epochs: { type: integer, default: 3, minimum: 1, maximum: 300 }
  batch: { type: integer, default: 4, minimum: 1 }
  imgsz: { type: integer, default: 640, minimum: 64 }
  device: { type: string, allowedValues: [cpu, "0"] }

resources:
  supportedProfiles: [cpu-small, gpu-16g-1, gpu-24g-1]

outputs:
  metrics: output/metrics.json
  log: output/train.log
  requiredArtifacts:
    - output/best.pt
    - output/last.pt
```

YAML是后端管理的可信配置，不从用户上传包直接覆盖K8s安全字段。

### 5.2 查询接口

至少提供：

```http
GET /api/training-plans
GET /api/training-plans/{planId}
```

接口返回：

- 方案ID、版本、展示名称和说明；
- 模型格式和数据集格式要求；
- 参数Schema及默认值；
- 可选资源规格；
- 当前是否可用及不可用原因。

前端只能通过接口展示训练方案，不再维护方案常量。

### 5.3 数据库增量

不新增数据库服务。建议从V32开始，为训练任务增加：

- `training_plan_version`
- `run_spec_snapshot_json`
- `resource_profile`
- `worker_image`
- `worker_image_digest`
- `effective_params_json`

如现有模型血缘字段不足，再给产出模型增加：

- 来源训练任务；
- 来源基础模型；
- 来源数据集；
- 来源代码版本；
- 产物格式和内容SHA-256。

迁移必须同时提供：

- 空库迁移测试；
- V31升级测试；
- Hibernate `validate` 测试；
- 历史任务字段为空时的兼容逻辑。

### 5.4 统一工作目录

```text
/workspace/job/
├── model/
│   └── base.pt
├── data/
│   └── data.yaml
├── code/
│   └── train.py
├── config/
│   └── params.json
└── output/
    ├── best.pt
    ├── last.pt
    ├── metrics.json
    └── train.log
```

输入目录在训练阶段应只读；训练代码只允许写入 `output/`。

### 5.5 通用Worker职责

通用Worker不实现具体算法，只负责：

1. 下载模型、数据和代码；
2. 校验对象路径、大小和SHA-256；
3. 安全解压到统一目录；
4. 校验入口文件和输入契约；
5. 写入最终 `params.json`；
6. 流式启动训练进程；
7. 持续保存日志并节流上报进度；
8. 校验 `metrics.json` 和必需产物；
9. 上传产物和日志；
10. 回调成功或明确失败。

运行环境可以按方案使用不同镜像，但执行协议保持一致。新增方案时，不应修改通用Worker主流程。

### 5.6 可信I/O与训练容器隔离

推荐Pod结构：

```text
init-downloader（可信，持有只读下载凭证）
    ↓ 共享只读输入卷
trainer（执行审批代码，不持有平台密钥）
    ↓ 共享输出卷和结束标记
result-collector（可信，负责上传和内部回调）
```

如第一阶段暂时无法完成三容器隔离，只允许管理员审批后的标准代码运行，并把密钥隔离列为正式提测前的安全债务，不得宣称支持开放式任意用户代码。

### 5.7 模型发布与推理契约

训练结果必须生成 `tss.training.output/v1` 统一输出描述。该清单由可信
worker 根据 RunSpec、真实产物路径、文件大小和 SHA-256 生成；用户训练代码只负责
生成 YAML 中声明的模型、指标和日志，禁止自行伪造输出清单。完整字段和代码侧约定见
《自定义训练代码编写手册》。

后端只能在产物校验成功后发布模型版本。推理Worker必须下载该模型版本并重新校验SHA-256；缺失或不一致时失败，禁止回退到 `yolo11n.pt`、`yolov8n.pt` 或缓存模型。

---

## 6. 分阶段开发SOP

### S0：冻结开发基线

操作：

1. 查看前后端 `git status` 和 `git diff`；
2. 将当前推理相关改动单独提交；
3. 记录前后端Commit SHA；
4. 修复或登记前端现有类型错误；
5. 创建训练框架开发分支；
6. 保存本节基线检查结果。

门槛：

```bash
cd /opt/tss-platform/backend
./mvnw test

cd /opt/tss-platform/frontend
npm run tsc
```

验收：后端全测通过，前端0个新增类型错误；工作区只包含当前任务相关修改。

### S1：先冻结协议，不写业务分支

产出：

- TrainingPlan YAML Schema；
- 参数Schema；
- 输入目录契约；
- 输出描述Schema；
- 状态和错误码约定；
- 模型格式、数据格式、资源规格枚举；
- API请求/响应示例。

必须先评审以下问题：

- 方案ID和版本如何升级；
- Worker镜像由谁维护；
- CPU和GPU运行时是否分镜像；
- PEFT Adapter与完整模型如何表达；
- 训练代码依赖如何提供，是否允许运行时联网安装；
- 旧Logreg任务如何兼容。

门槛：同一份方案定义可以同时驱动后端校验、前端表单、Job生成和Worker产物校验。

### S2：训练方案注册与查询

后端工作：

- 新增强类型 `TrainingPlanDefinition`；
- 启动时加载YAML并做Schema校验；
- 拒绝重复ID/版本、未知字段、非法路径和非法镜像；
- 实现查询接口；
- 增加接口权限和OpenAPI说明；
- 增加单元测试。

禁止：查询接口直接把内部密钥、Registry凭证或完整安全策略暴露给前端。

门槛：增加一个合法YAML后接口自动出现新方案；删除/禁用方案后不能创建新任务，但历史任务仍可查询。

### S3：任务快照与兼容性校验

后端工作：

- 增加V32迁移；
- 创建任务时解析方案和最终参数；
- 校验模型格式、数据集任务类型、标注格式、代码入口和资源规格；
- 固化 `runSpecSnapshotJson`；
- 审批证据绑定代码包摘要和方案版本；
- 版本续训使用新的快照，不读取变化后的方案定义。

门槛：任务落库后修改YAML，旧任务快照保持不变。

### S4：通用Worker与统一目录

Worker工作：

- 将固定 `PROFILES` 分支移除或降级为兼容适配器；
- 按统一目录下载和解压；
- 入口从任务快照读取；
- 参数写入文件，不拼接Shell；
- 使用流式子进程读取日志；
- 产物按输出契约校验；
- 回调重试且幂等；
- 失败保留日志和最后进度。

单测必须覆盖：

- 路径穿越；
- 缺入口文件；
- 非法参数；
- 进程非0退出；
- 超时；
- 必需产物缺失；
- 回调暂时失败后恢复；
- 重复回调。

### S5：K8s Job模板与资源规格

资源规格示例：

```text
cpu-small
gpu-16g-1
gpu-24g-1
```

GPU任务必须生成：

```yaml
resources:
  limits:
    nvidia.com/gpu: 1
```

同时校验：

- GPU节点标签；
- RuntimeClass（如使用）；
- CPU、内存和临时存储；
- 最长运行时间；
- 非root、禁止提权、Drop ALL capabilities；
- 不自动挂载ServiceAccount Token；
- 不允许HostPath和privileged。

门槛：单元测试同时验证CPU和GPU Job YAML；second只执行CPU smoke，GPU实测在GPU节点完成。

### S6：迁移原Logreg方案

将现有 `image_text_consistency_fusion_logreg` 转为方案YAML和统一输出契约。

必须保证：

- 旧创建流程继续可用；
- 原产物仍可下载；
- 原训练模型仍可发布和推理；
- 历史详情页不因新增字段为空而报错。

门槛：原有Logreg闭环回归通过，并证明核心代码中不再存在多处方案命令副本。

### S7：实现YOLO训练模板

YOLO运行镜像：

- Dockerfile必须进入仓库；
- 固定Python、PyTorch、Ultralytics版本；
- 安装OpenCV所需系统库或使用适合的headless依赖；
- 不使用 `latest` 作为交付版本；
- 镜像入口为通用Runner，不内置替代用户代码的训练算法；
- 构建后记录镜像ID和仓库Digest。

标准 `train.py` 必须：

- 从 `/workspace/job/model/base.pt` 加载基础权重；
- 从 `/workspace/job/data/data.yaml` 加载数据；
- 从 `/workspace/job/config/params.json` 读取参数；
- 禁止自动下载或回退内置模型；
- 输出 `best.pt`、`last.pt`、`metrics.json`、`train.log`；
- 在日志中打印模型、数据、代码和参数摘要；
- 训练失败时返回非0退出码。

门槛：second上使用小数据和少量epoch完成一次真实CPU训练。

### S8：模型发布与YOLO推理

后端工作：

- 校验 `best.pt` 存在且SHA-256正确；
- 自动创建新的模型版本；
- 保存完整来源血缘；
- 发布逻辑幂等，重复回调不能创建多个模型版本。

推理Worker：

- 必须读取任务选择的新模型版本；
- 下载后重新计算SHA-256；
- 不允许使用名称触发在线下载；
- 支持单图片和数据集批量推理；
- 输出类别、置信度和检测框；
- 生成 `result.json`、明细和可视化图片。

门槛：推理日志中的模型SHA-256与训练产物SHA-256相同。

### S9：前端动态方案与可观测性

前端工作：

- 从 `/api/training-plans` 获取方案；
- 按参数Schema生成表单；
- 根据方案过滤兼容模型、数据集和代码；
- 显示最终生效参数和资源规格；
- 显示真实状态、进度、日志、指标和产物；
- 终态停止轮询；
- 失败显示具体原因和最后进度；
- 不允许接口失败时静默展示Mock数据。

门槛：`npm run tsc`、`npm run build`通过；浏览器刷新和重新登录后仍可查看任务结果。

### S10：恢复、停止和异常闭环

必须验证：

- 删除运行中Job后任务变为stopped；
- Worker异常退出后Monitor能兜底为failed；
- 后端重启后能够恢复Job状态；
- 回调短暂失败能够重试；
- MinIO失败、镜像不存在、资源不足和超时均有明确错误；
- 任务不会长期卡在queued/running；
- 产物上传失败不能将训练标记为success。

### S11：正式E2E与提测材料

同一固定资产闭环连续运行3次：

```text
基础模型版本
→ YOLO数据集版本
→ 已审批训练代码版本
→ 训练任务
→ best.pt
→ 新模型版本
→ 单图片推理
→ 批量推理
```

额外并发验收：

- 两张GPU时提交两个 `nvidia.com/gpu: 1` 的训练任务；
- 两个任务必须分别处于running并落到可用GPU；
- 额外任务在资源不足时保持queued，获得资源后再运行；
- 不允许两个独立任务在未经声明的情况下争用同一张GPU。

---

## 7. 标准YOLO测试资产SOP

### 7.1 资产组成

第一阶段必须准备：

```text
yolo-test-assets/v1/
├── manifest.yaml
├── checksums.sha256
├── base-model/
│   └── yolo11n.pt
├── dataset/
│   ├── data.yaml
│   ├── images/train/
│   ├── images/val/
│   ├── images/test/
│   ├── labels/train/
│   ├── labels/val/
│   └── labels/test/
├── code/
│   └── train.py
└── inference-inputs/
    ├── image-01.jpg
    └── image-02.jpg
```

大文件不建议直接提交Git。应提交：

- 固定对象存储路径；
- 资产版本；
- SHA-256清单；
- 来源和许可证说明；
- 幂等的导入/种子脚本；
- 预期结果规则。

禁止测试期间临时在线下载模型或由测试人员自行寻找数据。

### 7.2 数据集要求

- 必须同时有train、val和test；
- `data.yaml`中的路径在解压后可解析；
- 每张标注图片应有对应标签；
- 类别ID范围合法；
- 固定随机种子；
- CPU smoke可使用小型数据集和1～3个epoch；
- 正式测试使用独立的固定数据版本。

### 7.3 预期结果

不建议用像素级完全相同的检测框作为通过条件。建议验证：

- 结果结构合法；
- 至少出现指定类别或满足预设检测数量范围；
- 置信度在合理范围；
- 检测框坐标在允许误差内；
- 推理使用的模型SHA-256与训练产物一致。

`best.pt`与基础模型SHA-256不同只能证明文件内容发生变化，不能单独证明训练有效；还必须结合训练步数、loss/mAP和实际推理结果。

---

## 8. 测试与回归矩阵

| 变更范围 | 最低回归 |
| --- | --- |
| TrainingPlan Schema/加载器 | Schema正反例、重复ID、版本兼容、查询接口 |
| 数据库实体 | 空库迁移、V31升级、Hibernate validate、历史空字段 |
| 创建训练任务 | 模型/数据/代码/参数/资源兼容性和权限测试 |
| Worker下载解压 | SHA、路径穿越、压缩炸弹、缺文件、MinIO失败 |
| 子进程执行 | 参数生效、日志流、退出码、超时、停止 |
| K8s Job | CPU/GPU资源、安全上下文、标签、deadline、镜像摘要 |
| 回调/Monitor | Token、幂等、单调进度、丢回调兜底、后端重启恢复 |
| 模型发布 | 产物缺失、SHA错误、重复发布、来源血缘 |
| 推理 | 新模型SHA、单文件、批量、错误输入、停止 |
| 前端 | 动态方案、动态参数、轮询、失败展示、无静默Mock |

每次提交至少执行：

```bash
cd /opt/tss-platform/backend
./mvnw test

cd /opt/tss-platform/frontend
npm run tsc
npm run build

cd /opt/tss-platform
python3 -m py_compile k8s/training-worker/train.py
python3 -m py_compile k8s/inference-worker/infer_worker.py
```

涉及Worker时还必须：

```bash
docker build <对应Worker目录>
kind load docker-image <固定版本镜像>
kubectl apply --dry-run=server -f <生成的Job YAML>
```

不得只凭编译通过判断训练功能完成。

---

## 9. 提交与协作规则

当前前端是独立Git仓库，后端根仓库和前端必须分别提交、分别记录SHA。

建议按以下粒度提交：

1. 文档和Schema；
2. Flyway与后端领域模型；
3. 训练方案查询接口；
4. 通用Worker；
5. K8s Job与资源规格；
6. Logreg兼容迁移；
7. YOLO训练模板和镜像；
8. 模型发布/推理；
9. 前端动态表单；
10. 自动化测试与文档。

每个提交应满足：

- 只包含当前任务相关文件；
- 提交信息说明影响范围；
- 同步增加或更新测试；
- 不夹带日志、数据、构建产物和密钥；
- 不修改已执行Flyway迁移；
- 不覆盖其他人的未提交修改。

---

## 10. 回滚策略

1. 使用功能开关控制新旧执行路径，例如 `training.plan-framework.enabled`。
2. 原Logreg执行路径保留到新框架完成连续回归后再移除。
3. 数据库迁移采用可空新增字段，不删除旧字段。
4. Worker镜像使用明确版本，可将配置回退到上一镜像。
5. 模型发布采用幂等键，回调重试不能产生重复版本。
6. Job创建失败只影响当前任务，不应自动修改模型、数据和代码资产。
7. 发现跨模块回归时先关闭新方案，不通过修改历史数据绕过问题。

---

## 11. 正式提测门槛

以下条件全部满足后，才能把状态从“开发中”改为“允许正式测试”：

- [ ] 后端完整测试通过；
- [ ] 前端类型检查和构建通过；
- [ ] 训练方案通过后端接口动态展示；
- [ ] Logreg兼容回归通过；
- [ ] YOLO基础模型确实从选择的模型版本加载；
- [ ] YOLO数据集确实来自选择的数据集版本；
- [ ] 实际执行选择并审批过的训练代码；
- [ ] epochs、batch、imgsz、device真实生效；
- [ ] 真实生成best.pt、last.pt、metrics.json和train.log；
- [ ] best.pt自动发布为新的模型版本；
- [ ] 推理加载刚生成的best.pt且SHA-256一致；
- [ ] 单图片和批量推理完成；
- [ ] 完整闭环连续成功3次；
- [ ] 异常任务不会长期卡住；
- [ ] 无Mock或内置模型替代；
- [ ] 提测材料、操作文档和已知限制齐全；
- [ ] 如验收包含并发，两项独立训练任务已经在目标计算资源上同时运行成功。

---

## 12. 开发完成后的证据包

每次正式提测必须提供：

- 前端Commit SHA；
- 后端Commit SHA；
- Worker镜像名称、版本和Digest；
- TrainingPlan ID和版本；
- 标准资产清单与SHA-256；
- 训练任务ID；
- 基础模型SHA-256；
- best.pt SHA-256；
- 新模型版本ID；
- 推理任务ID；
- 训练和推理完整日志；
- 训练指标和推理结果；
- K8s Job/Pod信息；
- 自动化测试输出；
- 连续3次闭环结果；
- 当前未实现和受环境限制的项目。

没有日志、产物、摘要和任务ID的成功截图，不作为通过证据。

---

## 13. 下一步执行顺序

建议按下面顺序正式开始：

1. 处理当前前后端未提交推理改动，固定基线Commit；
2. 清理前端现有TypeScript错误；
3. 评审并冻结TrainingPlan v1、输入目录和输出描述Schema；
4. 新增V32任务执行快照迁移；
5. 实现训练方案加载器和查询接口；
6. 将现有Logreg迁移为第一个模板；
7. 实现通用Worker和动态Job生成；
8. 重建可复现的YOLO CPU Worker镜像并完成CPU smoke；
9. 打通YOLO模型发布和推理闭环；
10. 接入GPU计算节点，完成GPU资源与双任务并发验收；
11. 补齐异常测试、连续3次E2E和提测证据。

在第3步协议冻结前，不建议直接编写YOLO业务分支；否则很容易再次形成第二套硬编码实现。
