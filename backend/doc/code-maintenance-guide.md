# 后端代码维护入口与边界

本说明供接收源码后继续开发的工程师使用。它是代码导航，不替代 API 文档、数据库迁移或部署手册。
以 `src/main/resources/application.yml`、实际启用的配置和测试为准，历史方案不能直接当作当前运行状态。

## 1. 现有结构，不引入新架构

当前后端是 Spring Boot 单体服务，Java 17；资产与训练主要使用 JPA，用户模块保留 MyBatis-Plus。
平台依赖 PostgreSQL、Redis、MinIO、MLflow 与 Kubernetes。容器中的 Python 训练/推理执行代码位于仓库 `k8s/` 和 `examples/`，不是 Java 服务自身的线程任务。

| 包/目录 | 主要职责与阅读入口 |
| --- | --- |
| `controller`、`controller/v2` | HTTP 契约、参数校验、错误映射；新旧 API 暂时并存 |
| `dto`、`model` | 请求、返回与领域取值；不能随意改字段名和状态码 |
| `entity`、`repository` | 实体映射、条件更新、唯一约束和加锁查询 |
| `service` | 资产上传、发布、业务编排；先按具体功能定位服务，不新增万能工具类 |
| `module1`、`security` | 用户、角色、Sa-Token 身份、所有权和 API 策略；前端按钮不是权限边界 |
| `training/plan` | 训练方案解析、输入匹配、资源规格和运行参数生成 |
| `training` | 环境探测、Job 清单、Fabric8/kubectl 训练控制与执行 |
| `inference` | 推理任务、attempt、提交/停止与失败恢复；仍有 kubectl 执行路径 |
| `modelcache` | 节点模型缓存策略与生命周期，不替代用户资产的权威存储 |
| `config` | 可注入配置；环境名、路径和凭据通过部署配置决定 |
| `src/main/resources/db/migration` | Flyway 迁移历史；已有文件保持不变，新变更追加新版本 |
| `src/test` | 单元、契约与 PostgreSQL 容器测试，按功能与源码对应 |

## 2. 三条必须保留的行为链

### 上传与版本发布

`Controller → 上传会话/所有权检查 → 分片与对象存储 → 校验/扫描 → 版本发布 → 列表和训练可选状态`。

重点阅读 `DatasetUploadService`、`ModelUploadService` 及对应测试。
数据库事务不能回滚 MinIO 对象操作。不要将外部存储操作塞入长事务来“解决一致性”；沿用短事务占位、外部 I/O、短事务确认、失败补偿的已有模式。
不要仅因 HTTP 超时就新建一次上传；必须核对会话和版本记录。

### 训练与资源

`方案/资产/硬件检测 → 服务端校验 → 固化本次运行参数 → Job → 日志/指标/结果模型`。

重点阅读 `TrainingResourceRequestResolver`、`KubernetesJobManifestBuilder` 和训练执行器。
界面不能决定服务端上限。CPU/内存配额、GPU 整卡资源声明、PyTorch 显存软预算是三种不同机制，不要混称为显存硬隔离。
保留两种训练客户端，但同一时刻只装配一种；Fabric8 请求超时后不能自动再用 kubectl 提交。

### 推理与恢复

`持久化任务 → 领取 attempt → 提交对应 Job → 回写该次结果 → 成功/失败/停止`。

重点阅读 `InferenceExecutorRouter` 与测试。
过期回调、恢复线程、用户重试可能同时发生，状态更新必须核对 attempt；旧请求不能覆盖新的重试结果。
Job/Pod 的 TTL 只管理 Kubernetes 临时资源，不应顺带抹掉业务记录与失败日志。

## 3. 新功能与重构的最小做法

1. 先查看类似 Controller/Service/测试，明确是新增行为还是修复现有行为。
2. 新接口维持相应版本的返回值规范；不要为“统一风格”批量重命名旧字段或删掉旧接口。
3. 权限、重复请求、并发、超时、部分失败各选真实边界补测试；新增状态要核对数据库约束与轮询/清理线程。
4. 无状态的转换、校验可抽成小类；涉及事务、所有权和恢复状态机的大服务，先建立行为测试，再分阶段拆分。文件长不是单独的重构理由。
5. 中文注释解释为什么采用该事务、如何幂等、何时失败与补偿。保持注释与实际行为一致，不翻译第三方或生成文件。
6. 配置默认值应可解释、可覆盖，不嵌入真实节点 IP、GPU 型号或实验室路径。内部防误部署校验是环境边界，不能简单删除以支持新客户。

## 4. 验证与交付

```bash
cd backend
./mvnw --batch-mode --no-transfer-progress clean verify
```

PostgreSQL 集成测试需要可运行容器的环境；没有 Docker 时部分测试会跳过，必须记录，不能把构建成功写成全量通过。
上线前核验源码提交、镜像摘要、配置和部署目标是否一致。交付源码不包含个人凭据、运行时数据库、缓存或别人的未提交文件。

第一批整理不改变数据库、权限、Job 提交协议和训练算法。阶段状态与证据见 [交付 SOP](delivery-sop.md)。
