# 后端代码治理 SOP

## G7：代码级设计、数据结构和调用关系（2026-09-10，执行前登记）

- 延续用户架构梳理要求，读取实体/DTO、Controller/Service/Repository、执行器和 worker，说明真实的数据与函数关系。
- 保留 G6/G7 前端架构文档等现有成果；新增实体字段字典、代码级设计说明与静态导航。自动结果只反映显式声明和调用位置，关键业务顺序另读函数体核实。
- 验收：资产/版本/工作区/任务关系、状态、输入输出、事务/异步和前后端契约可追踪；文件及行号准确；不修改生产代码、SQL、配置和测试，不执行真实业务。

### G7 完成与核对

- 新增后端代码级设计、42 个实体目录文件的 638 个非 static 字段声明字典、请求/运行结构字典、630 个 Java 源文件的函数与依赖索引。复合键类和非持久化字段明确标注，不把文件数当表数。
- 人工核对训练创建/快照/调度/回调、推理重试/回调、上传/导入、两类工作区发布的事务顺序；记录普通训练回调 `findById()` 与推理回调 `findByIdForUpdate()` + attempt 的差别，保留训练重复 success 补产物分支，不夸大并发保证。
- 自动生成后按原文件摘要、声明名/行号、调用范围与文档链接核对；合并前端共 870 个文件、10537 个定位、1479 个链接，错误 0。`git diff --check` 通过；源码未改，既有测试证据不算本轮新执行。
- 过程修正：静态 Java 扫描起初误纳入无返回类型的调用语句，补严格过滤并核对代表方法；跨行返回类型改用方法名所在行定位。Node 内启动 Git 被本地限制，改为 PowerShell 只读取得 HEAD 后传参；没有申请额外权限。辅助检查的 PowerShell 管道语法错误已修正。索引只承诺显式语法导航，动态派发/代理/Lombok/继承仍需源码和运行验证。
- 全部变动属于 Markdown 和工作区文档导航工具/证据，原 Java、Python、SQL、配置、测试均未改；不提交、推送或部署，不触碰并行工作区或共享服务器。

## G6：前后端架构与新增功能地图（2026-09-10，执行前登记）

- 用户要求梳理完整架构和功能扩展位置。本阶段只补文档，基线为后端 `30dda00`、前端 `523e5a0`，登记前两处工作树干净。
- 核对 Java 业务模块、JPA/MyBatis 两套数据入口、权限、异步恢复、Python worker、配置和交付脚本，并与前端页面和服务对应。
- 验收：现有入口路径准确；新增字段、业务模块、训练方案、数据格式、任务状态和权限的影响及测试要求明确；原生产代码、SQL、测试和部署配置保持原内容。记录文档检查结果，不把既有测试说成本阶段新执行。

### G6 文档完成与核对结果

- 新增《后端架构与功能扩展指南》并接入维护索引；覆盖 API/规则/数据、账号权限、后台恢复、训练方案、两类 worker、测试和交付目录。
- 明确资产/任务主要使用 JPA，账号/日志主要使用 MyBatis；正式登录沿 `module1`，根 AuthController 仍有演示实现；迁移目录实际到 V65，而旧目录 README 写到 V60，新增编号必须重查。
- 第二遍按源码清单核对路径/类名，文档链接均可解析；`git diff --check` 通过。首次文档校验命令的 PowerShell 管道语法错误已修正，随后检查退出 0。
- 工作树变动仅 Markdown；原 Java、Python、SQL、配置和测试无改动。本轮未重跑测试，指南中的 1414/1412/2 与十套 38 项来自 G5 既有证据；真实作业和客户安装等缺口明确保留。文档暂留本地，未提交、推送或部署。

## 本轮范围（2026-09-09）

独立分支 `codex/backend-code-governance`，基线 `2cac3f8`。不混入另一工作区的交付安装器改动，不推送 `backend`、`backend-ops` 或 `backend-gpu`，不部署。

### A：确认的事实

- `DatasetUploadService` 同时包含会话/版本事务、输入规则、目录 ZIP 和 MinIO SDK 操作，原文件约 2686 行。
- 原有完成流程是短事务占用 → 事务外存储/校验 → 短事务发布 → 失败补偿；对象存储与数据库不共享事务。三个合并对象代码块仅目标变量名称有差异，调用和参数构造相同。
- 原有条件更新、唯一约束、所有权校验及提交后清理已有并发/生命周期契约测试；必须保留。
- 已在重构前增加目录打包 5 项行为契约。内网机械盘隔离基线测试共 1364 项，1362 通过、2 跳过、0 失败；另明确排除 10 个依赖 Testcontainers 的套件，不能称为全量集成验收。

### B：采用的低风险方案

- 将无状态输入策略抽到 `DatasetUploadPolicy`，目录打包抽到 `DatasetFolderArchive`，重复 SDK 调用抽到 `DatasetUploadObjectStore`；均为包内小类，不新增 Spring Bean 或基础框架。
- 原公共入口及测试依赖的兼容入口保留。原类继续负责权限、事务、会话竞争、资源生命周期、失败补偿与审计；不得把这些职责拆散到隐式回调。
- SDK 包装不吞异常、不自动重试、不自行删除对象；流的关闭和临时文件清理由原调用方负责。

### C：本轮不擅自决定

不改数据库结构、权限、资产格式、状态机、重试规则、API 兼容性或跨环境部署策略。不能以文件缩短为理由重写所有上传编排；有业务风险的剩余工作另列。

## 阶段与验证

1. 基线和新增目录契约先运行，记录真实环境限制。
2. 机械搬移纯规则和目录打包；保留原调用次序；去重三个完全一致的对象合并调用。
3. 补 SDK 参数/顺序/异常不重试测试。运行重构后同一测试集合，对比基线；重点覆盖并发、重复完成、失败标记、权限拒绝、清理时序。
4. 独立第二遍检查 diff、构造器、事务注解、数据库/存储调用先后、无引用辅助方法；记录未验证项。
5. 单独提交、推送治理分支。测试通过不等于已部署或真实训练已验收。

## 内网测试保护范围

- 用户指定后端测试移至内网或外网，默认选择 seu5090，避免占用外网 CPU 验收集群。
- 独立路径 `/srv/tss-AIplatform/staging/fabric8-smoke/governance-20260909`，位于 `/dev/sda1` 机械盘；源码、Maven 缓存、临时文件和结果都放这里。
- `systemd-run --user --scope` 限制 CPU 100%（约 1 核）、总内存 2 GiB；Maven 与测试 JVM 各 768 MiB，串行。无 GPU、无真实业务数据库、无服务替换。
- 根盘 99%、可用约 15 GiB，Docker 仍在根盘。先不启动 Testcontainers；其专用镜像缺失，不允许测试偷偷拉取。没有删除或弱化原测试，只在本次命令显式排除并单列未验证。
- 基线包 SHA-256 `7482a8bb660508929c598f926e2a53baa23e089ab1cacd716502665722ad6176`；基线日志 `baseline-tests.log`。测试后根盘可用空间未出现可见变化。

## 执行记录

- G3 开始：前端 G2 已完成，提交 `3c1ccbc`；后端产品代码尚未修改，先形成以上约束。
- 机械抽取第一遍被审计保护阻止：目标变量一个叫 destName，另外两个叫 destinationObject；同时发现临时提取器把调用误认为声明。修正提取器并逐方法复查后重新生成，未部署或执行损坏代码。保留不同目标变量，不盲目统一业务变量。
- 基线两项跳过是 `Fabric8KubernetesRealClusterSmokeTest` 和 `Fabric8KubernetesRealGpuSmokeTest`，均需显式授权真实集群运行；本轮保持未启用。
- G3 实现完成：17 个方法迁入两个无状态辅助类，3 处对象合并由同一 SDK 适配器实现。原构造器签名、接口/DTO、数据库/迁移文件均未改。删除抽离后确无引用的私有 `extensionOf` 转发方法，真正的格式判断仍使用 `DatasetZipValidator`。
- 新增 9 个测试：目录契约 5 个（路径穿越、规范化碰撞、白名单、空文件、流异常关闭等）已纳入重构前基线；SDK 适配 4 个（分片顺序、对象目标、内容与长度、异常原样传播、不自动重试/清理）。原有测试未删除或修改。
- 重构后内网结果：1368 项中 **1366 通过、0 失败、2 显式真实集群测试跳过**；10 个容器套件仍未执行，与基线选择范围相同。`candidate-tests.log` 保存完整输出；耗时约 56 秒。
- 测试包 SHA-256 `ff1d7a3d0e79a2e2550b7b90b7ae4052b285755cd8aabde31d7f2f0cf5970107`，源码与本地候选一致；后续仅删除方法间冗余空白、补充文档。测试副本与独立缓存约 328 MiB，全部位于机械盘；未更新业务服务、镜像、配置和线上数据库。
- 第二遍反向审查：17 个迁移函数体与基线逐字一致（换行统一）；所有事务注解、事务调用与提交后清理入口顺序一致。五条有 SDK 替换的业务路径逐段对照：存储成功标记的位置、校验失败类别、对象目标、关闭流与失败补偿均保持原顺序。新适配器无数据库引用、无 catch、无重试、无删除操作。

## 预期行为—实现—测试

| 预期行为 | 实现责任 | 已执行证据 |
| --- | --- | --- |
| 正数文件大小先验证，分片数不超过原限制 | Service 入口 + Policy | 原 EnterpriseVersion / 分片测试 |
| 路径/格式不被抽离放宽 | Policy、FolderArchive、原 ZipValidator | 目录契约 5 项 + 原多模态/各格式测试 |
| 重复或并发上传不重复发布、失败可恢复 | 原 Service / Repository / RecoveryService | ChunkConcurrency、FailureLifecycle、ManifestCompletion、Recovery 系列通过；真实 PostgreSQL 竞争未在本轮重跑 |
| 存储调用保持对象和分片顺序 | ObjectStore，Service 控制调用次序 | 新 SDK 4 项 + 原上传失败/事务契约 |
| 权限、版本规则、提交后清理不变 | 原入口与事务编排 | 原相关服务/控制器测试，源码事务边界复核 |

## 维护入口及后续边界

- 新增输入校验先看 `DatasetUploadPolicy`；新增 ZIP/路径规则看 `DatasetFolderArchive` 和 `DatasetZipValidator`，不要复制第二套格式白名单。
- 新增 MinIO 参数处理看 `DatasetUploadObjectStore`；存储异常如何映射、何时标记失败、何时清理，仍看 `DatasetUploadService`。
- 修改会话状态、版本号竞争或清理顺序，必须同时运行 `DatasetUploadChunkConcurrencyTest`、`DatasetUploadFailureLifecycleTest` 及真实 PostgreSQL/MinIO 集成测试；Mockito 不能证明真实数据库锁和远端存储正确。
- 原编排仍较长；保留它是为了让占用、发布和补偿边界可见，不能在未补相应集成证据时继续拆成更多隐式流程。本轮完成已确认的职责分离，不宣称全仓库没有任何技术债。
- 上线建议：可以提交独立治理分支；**不建议凭本轮证据直接发布交付版**。先补 10 个容器套件，内网真实上传/草稿/发布/失败恢复冒烟，然后再合并部署。不要为跑测试连接业务数据库、重启共享 Docker 或自动占用 GPU。
- 其他存量告警：基线 Java 编译仍有弃用/泛型告警；GitHub 默认分支依赖告警不属于本次纯结构修改，需另做依赖风险评估，不能宣称已消除。

## G4：六项集中治理的后端部分（2026-09-09）

用户要求六项先完成修改，再统一测试。后端基线 `bab0447`，仍在 `codex/backend-code-governance`；不操作并行交付安装器工作区，不修改内外网部署分支或服务。前端同轮细节见其 `docs/six-issue-governance-sop.md`。

### 已确认规则与本轮改动

| 规则 | 实现与边界 | 验证 |
| --- | --- | --- |
| 上传校验沿用 `INVALID_UPLOAD_REQUEST`，资源校验沿用 `INVALID_REQUEST` | `DatasetWorkspaceResourcePolicy` 共用字段、枚举、边界、标注归属检查；调用方传入各自错误码 | 新 `SixIssueGovernanceTest`：空值、长度、负数、错误码差异、复制 Map、跨工作区/样本和已删除目标 |
| 工作区/样本归属必须先校验，原事务和写入顺序不变 | `V2DatasetWorkspaceResourceService`、`DatasetWorkspaceFileUploadService` 仍编排权限、事务、持久化和补偿；`DatasetWorkspaceResourceMapper` 只做 DTO 转换 | 原工作区资源、文件上传事务、发布与相关服务测试通过；新增拒绝场景不触发保存 |
| 导入计划到实体转换不能携带流程状态 | `ImportSampleMapper` 抽取原映射，ID、字段和 ZIP 路径处理保持 | 原 `ImportJobServiceTest`、单模态、目录及 Manifest 测试通过 |
| 错误分类不能依赖提示文案 | `ManifestFailureKind`、`ImportFailureMapper`；生产解析入口显式分类，旧异常构造器走独立 `LegacyImportFailureClassifier` | 新测试覆盖中文文案、误导关键词/用户路径、旧错误兼容、畸形 JSON、空单模态包；所有枚举类别验证 |
| 异常整理本身不能无限循环 | `ImportFailureMapper` 检查 cause 链已访问对象 | 新循环 cause 测试限时通过 |

新辅助类均为小范围职责提取，不增加数据库表、Spring 服务架构或公开接口字段。保留 `ManifestValidationException` 原构造器、错误码与 details 契约，只新增内部显式类别。Legacy 文本分类仍是历史兼容入口，不作为新错误产生路径的规范。

### 统一测试记录

- 测试位置：seu5090 `/srv/tss-AIplatform/staging/fabric8-smoke/six-issues-20260909`，项目机械盘 `/dev/sda1`，源码/临时文件/报告均在这里；复用机械盘离线 Maven 缓存。
- `systemd-run --user --scope` 限制 `CPUQuota=100%`、`MemoryMax=2G`、`TasksMax=256`；Maven 和测试 JVM 各 768 MiB。没有使用 GPU、连接业务数据库、拉取容器镜像、修改服务或重启服务器。
- 本轮新增 `SixIssueGovernanceTest` **8 项测试**；最终 Maven **1376 项，1374 通过、0 失败、0 错误、2 跳过**。完成于 `2026-09-09T16:11:11+08:00`，最终轮约 47 秒。
- 日志：测试目录 `backend-tests.log`，报告 `backend/target/surefire-reports`；`backend-round3.log` 是补充边界前的中间轮，最终以上述日志为准。
- 两项跳过仍为真实 Fabric8 CPU/GPU 集群冒烟；本轮没有冒充真实训练验收。
- 编译首次发现抽离 `toJson` 后遗漏静态导入，已补齐。第一次完整运行还发现隔离副本漏拷现有示例夹具导致 3 项失败，补齐夹具后重跑；没有删除或放宽测试断言。

以下 **10 个 Testcontainers 套件未执行**（命令中明确排除，并非通过或仅 2 项跳过的一部分）。原因：根盘 99%，Docker 所需镜像未齐，不允许测试自动拉取而挤占共享服务器空间。

```text
CodeAssetMinioContainerTest
CodeAssetPostgresContainerTest
CodeAssetPublishIntegrationTest
SaTokenRedisPersistenceContainerTest
DatasetWorkspaceUploadIntegrationTest
CatalogKeywordPostgresRepositoryTest
AssetNameAndAbandonedVersionPostgresRepositoryTest
DatasetUploadFailurePostgresRepositoryTest
DatasetCatalogReadinessPostgresContainerTest
DatasetWorkspaceMinioContainerTest
```

测试选择为 `-Dtest=*Test,!<上述各套件>`（每个套件分别排除），Maven 使用 `--offline --batch-mode --no-transfer-progress`；实际命令保存在本轮工作区根 `.cache/run-six-backend.sh`。此选择只用于本次隔离验证，不更改项目默认测试配置或 CI，不应复制为交付验收的默认全量测试命令。

### 反向审查、假设与上线边界

1. 在实现完成后的第二遍检查中，对照原函数体、调用顺序和错误码，确认映射/策略没有承担事务、写入、重试或清理职责；原 CAS、短事务、对象补偿和权限边界保留。
2. 复查各生产解析入口，补齐畸形 JSON、空单模态包等显式分类，并保留异常 cause；旧明确业务错误码仍优先透传。新增循环 cause 保护后再次执行最终测试集。
3. **假设：为保证兼容，不强制所有外部调用方立即更换异常构造器。** 依据是原测试与历史入口仍使用旧构造器。影响为保留单独 Legacy 分类器；新增生产错误必须显式指定类别，避免再扩展文本匹配。
4. **假设：本轮拆纯规则和映射，不继续拆事务编排。** 依据是现有 CAS/补偿与用户“不影响业务”。影响为部分 Service 仍较长，但关键状态顺序可见；不能把文件变短本身当成业务安全证据。
5. 最终测试后仅补文档及 Java 尾部空白整理，未再次改变业务逻辑。前端同期 208 项单测、setup/tsc/构建及 157 项本地浏览器断言通过，但都是独立回归证据，不能替代端到端验收。
6. 当前六项本轮范围已实现、未提交/推送/部署。建议先补齐独立 PostgreSQL/MinIO/Redis 集成环境，再做内网真实上传—发布—训练—结果读取回归，之后合并；**不建议直接替换正在验收的内外网服务**。不声明所有历史告警、长文件或技术债已清零。

## G5：接手保存与集成保护（2026-09-10，执行前登记）

- 用户授权先保存既有成果，再补关键集成、收拢内部契约与类型、逐模块拆职责和补中文维护说明；不推送、不部署、不修改并行安装包工作区。
- A：`bab0447` 上原 9 个修改文件、7 个新文件完整。1374 通过、2 跳过是 9 月 9 日的历史隔离结果，另 10 个容器套件未执行；本次保存不改变该证据边界。
- 先整体形成本地治理提交，再只读核对测试依赖和隔离条件。测试仅用独立容器及项目测试目录；不连接业务库、不占 GPU、不重启共享运行时、不自动拉镜像。条件不足记阻断，不把跳过算通过。
- B：在真实存储和数据库保护未通过前，保留事务/CAS/权限/补偿编排；可以补独立测试入口和纯规则的职责说明，不为缩短文件继续重构持久化链路。
- 每次后续实现先登记范围、可观察验收、资源与清理边界，完成后记录命令、版本、结果和第二遍反向审查。

### G5.1 真实依赖测试：本机 WSL（执行前登记）

- 保存提交为 `635677a`。seu5090 只读核实历史 Maven 为 1376/0/0/2；根盘仍 99%，缺固定测试镜像，故不在共享服务器启动测试。
- 本机 WSL Ubuntu / Docker 29.7.2 当前无运行容器，WSL 可用内存约 14 GiB、Windows C 盘约 48 GiB 可用。仅用项目 `.cache/governance-20260910` 的源码副本、缓存与报告，不安装宿主 JDK、不改 Docker 配置。
- 明确准备 PostgreSQL 16.6-alpine、MinIO RELEASE.2025-09-07T16-13-09Z、Redis 7.4.11-alpine、Testcontainers 1.21.4 对应 Ryuk 0.12.0/alpine:3.17、Maven 3.9.12 + Java 17。先查 manifest 与体积，受控下载到本机；测试入口本身不下载镜像，不使用 latest 或替换版本来放行。
- 范围是原先未执行的 10 个套件。测试容器只用随机新数据，串行运行；Maven 限 2 CPU / 2 GiB，依赖容器各限 1 CPU / 1 GiB，端口仅绑定本机。通过 Testcontainers 会话归属清理，不执行 prune 或批量删除。
- 新增只用于测试的隔离配置和原始 XML 报告检查；10 个套件必须均有实际用例、零失败、零错误、零跳过，缺报告直接失败。先验证报告检查的拒绝场景，再运行真实集成。
- 验收观察包括真实数据库约束/并发胜者、对象哈希/范围读取、提交失败补偿、跨用户查询和 Redis 会话持久性。认证替身的服务集成仍不等于真实登录或全站验收。

### G5.2 测试上下文收尾（执行前登记）

`backend-full-03` 已有 1414 项、0 失败/错误、2 个集群跳过，10 套容器报告共 38 项通过，但 Surefire 记录测试 JVM 退出 30 秒超时。线程转储显示 Spring 关闭上下文时 Hibernate 仍在向已停止的测试 PostgreSQL 申请连接；3 个仓储容器测试缺少相邻发布/工作区集成测试已有的 `@DirtiesContext(AFTER_CLASS)`。

仅为这 3 个仓储测试补同样的类结束清理声明及一句中文说明，保持容器顺序、所有断言、1 秒循环异常超时、产品配置和生产代码不变。重新完整回归，要求原 1414 项计数一致、十套零跳过，并且不再出现测试 JVM 的 30 秒强制退出；退出后另查容器残留。首轮线程转储和 XML 保留。

### G5 本轮环境问题与处理证据

证据目录：工作区 `.cache/governance-20260910/`。前端独立保存为 `523e5a0`，原后端成果已保存为 `635677a`；本节以后端该提交之上的测试入口和文档为候选，生产 Java、SQL、权限与事务编排未新增修改。

1. seu5090 只读核验历史报告仍为 1376/0/0/2，根盘 99%，因此没有在该共享服务器拉镜像或跑测试。本机 WSL 仅要求没有运行容器；原本已有一个停止的 Redis 容器和旧 Redis 镜像，均保留。
2. 固定 6 个镜像分层下载、检查压缩摘要和解压层摘要后导入本机。Docker 29 的 containerd 存储下 `.Id` 是 manifest 身份，不能直接和镜像 config 摘要比较；改为导出已加载镜像并对实际 config 字节核验，不放宽摘要判断。Ryuk 镜像有未压缩 tar 层，按声明的层类型处理，不强行当 gzip。准备工具只在项目缓存目录，未进入产品。
3. `integration-01`：38 项中 18 通过、20 错误、0 跳过，错误集中在 Mockito 初始化。`mock-probe.log` 与 `mock-probe-mounted-cwd.log` 保持同一镜像/依赖，仅换工作目录：容器自身目录动态挂载通过，Windows 挂载目录出现 attach socket 超时；显式加载同版本 agent 通过。未修改 Docker 安全选项、JDK 或业务代码。
4. `backend-full-02`：准备阶段的 Maven help 插件有 3 个离线传递依赖未缓存，没有进入测试。`mockito-version-probe.log` 保存具体缺项。最终使用 Maven 原有 `${mockito.version}` 属性直接展开 agent 路径，不引入查询插件、新依赖或硬编码第二个 Mockito 版本。
5. `maven-agent-probe.log` 证明 agent 可运行原治理测试；但 Windows 挂载目录下一个含冷类加载的 1 秒超时用例失败。保留原 1 秒断言，把源码和缓存复制到一次性容器的 Linux 文件系统，消除跨文件系统读写延迟；失败/成功均导出报告，原缓存只读。
6. `backend-full-03`：1414/0/0/2，十套 38 项均通过，1 秒用例也通过。退出时 Spring 缓存上下文仍向已停的测试数据库取连接，Surefire 等待 30 秒后结束测试 JVM；转储显示 Hibernate schema 清理等待连接。按 G5.2 只补 3 个测试的上下文清理，不把退出超时写成“完全正常”。

参考依据：[Mockito 显式 agent 说明](https://javadoc.io/static/org.mockito/mockito-core/5.17.0/org.mockito/org/mockito/Mockito.html)、[Docker containerd 镜像存储](https://docs.docker.com/engine/storage/containerd/)、[Testcontainers 配置](https://java.testcontainers.org/features/configuration/)。本机 Ubuntu 26.04 是测试运行环境，不新增客户系统支持承诺；交接资料的 Ubuntu 20.04/22.04 起点冲突保持待统一。

### G5 最终结果（2026-09-10）

最终证据是 `.cache/governance-20260910/backend-full-04/`，Maven 完成于 `2026-09-10T07:05:52Z`（北京时间 15:05:52），测试阶段 1 分 43 秒；不是前几轮的中间结果。执行命令：

```powershell
wsl -d Ubuntu -u root -- env TSS_MAVEN_REPOSITORY=/mnt/c/Users/chaohui/.m2/repository bash /mnt/d/Users/chaohui/Desktop/tssai/delivery-governance-backend/backend/scripts/qa/run-container-integration.sh /mnt/d/Users/chaohui/Desktop/tssai/.cache/governance-20260910/backend-full-04 --all
```

| 范围 | 最终证据与结果 |
| --- | --- |
| 后端完整 `*Test` | **1414 项：1412 通过、0 失败、0 错误、2 跳过**；`maven.log` / `maven-exit.txt`，退出 0 |
| 原缺失十套真实依赖 | **38/38 通过，0 跳过**；独立 XML 核验见 `integration-summary.json` |
| 报告保护门自测 | `report-gate-tests.log`，8 项通过，涵盖缺项、过期、零用例、计数/身份矛盾及失败/跳过 |
| 候选身份 | 源码归档 SHA256 `94c2e31aef5a91d4706be474d0d2c7ec9d056501710b0fae8c3c754158c57da4`；975 个输入文件与当前代码逐字节一致 |
| 独立汇总 | `backend-full-summary-final.json`，237 份本轮 XML，逐份核对用例计数及跳过原因 |
| 退出与清理 | 无 30 秒强制退出信息、无线程转储；`remaining-containers.txt` 为空。`backend-containers-final.txt` 仅原停止的 Redis 容器；无运行容器、无测试卷残留 |

十套分项和观察结果：

| 套件 | 通过数 | 业务观察 |
| --- | --- | --- |
| CodeAssetMinioContainerTest | 2 | 实际对象读写、范围读取 |
| CodeAssetPostgresContainerTest | 14 | 数据库迁移、约束和版本竞争 |
| CodeAssetPublishIntegrationTest | 5 | 发布产物哈希、并发单胜者、审计失败的精确补偿、跨用户查询范围 |
| SaTokenRedisPersistenceContainerTest | 1 | DAO 重建后的 token/session 持久性，不等于实际网页登录 |
| DatasetWorkspaceUploadIntegrationTest | 4 | 128 MiB 级文件乱序并发/重传、提交失败回滚与清理、历史版本发布与头版本漂移拒绝 |
| CatalogKeywordPostgresRepositoryTest | 3 | 名称筛选、用户/类型范围与 LIKE 字面字符 |
| AssetNameAndAbandonedVersionPostgresRepositoryTest | 3 | 名称归属/唯一性、软删除、放弃版本隐藏 |
| DatasetUploadFailurePostgresRepositoryTest | 3 | 失败会话恢复、原子清除失败信息、状态约束 |
| DatasetCatalogReadinessPostgresContainerTest | 1 | 可用性聚合 SQL 和字段映射 |
| DatasetWorkspaceMinioContainerTest | 2 | 草稿对象下载与继承 ZIP 内容的准确范围读取 |

最终第二遍审查确认：10 个原测试只增加独立运行约束，3 个仓储类补结束清理，原断言全部保留。资源/端口/镜像下载限制只在独立入口生效，不改变默认 CI 或产品行为；本机 Docker socket 被显式固定，不受远端 context 影响。报告和源码在本轮专属目录，构建容器及其内部临时缓存随退出释放；原 Maven 缓存不写入。旧 Java 弃用/泛型、Logback 等提示仍在，不声称全仓零告警。

两项跳过为 `Fabric8KubernetesRealClusterSmokeTest` 和 `Fabric8KubernetesRealGpuSmokeTest`，分别因未设置 `TSS_REAL_K8S_SMOKE` / `TSS_REAL_K8S_GPU_SMOKE`。本轮不连接真实集群，不能算实际 CPU/GPU 作业验收。真实身份权限、上传—草稿—发布—训练—结果读取的整条业务链、全站按钮、客户安装/重启/回滚仍未验证。

维护交付入口为 README → [后端代码维护索引](代码维护索引.md)，与前端维护索引/接口契约指南相互对应。本轮保存本地治理提交，不推送、合并或部署；没有操作并行安装包工作区、共享服务器非项目数据或正在运行的业务服务。结论是可进入真实业务联调的治理候选，不能直接当作交付版上线。剩余长 Service 按功能逐批处理，优先保持事务、CAS 和补偿边界可见。

## 六项维护风险修复（2026-09-10）

用户授权按剃刀原则修复编号 1、2、4、5、6、7。后端完成数据集按 ID 聚合、训练记录修订号与条件停止、持久提交回执防重、最新训练版本数据库分页、同实验版本号分配协调及接口功能归类检查。复用既有 Spring/JPA/PostgreSQL，没有新增框架、队列或 CI 工作流；只补必要中文注释和验收入口。

最终证据位于工作区 `.cache/maintenance-six-20260910/backend-after-03/`：1435 项中 1433 通过、0 失败/错误、2 项原真实集群测试跳过；11 套真实 PostgreSQL/MinIO/Redis 集成共 45 项全部通过，已包含在总数内。新 PG 套件验证旧快照不能覆盖停止、并发创建防重和版本 2/3 分配、10000 条版本记录分页及回执回滚。归档中 981 个输入与当前后端文件逐字节一致；测试容器已清理，未操作业务数据库或真实 CPU/GPU 任务。

唯一新增迁移 V66 增加修订号、提交回执表和查询索引，已在隔离库验证旧记录升级。以后直接批量更新训练表必须同步递增 `lock_revision`；不要随意清空回执，否则旧请求可能重新创建任务。发布时前后端配套，所有后端实例使用同一候选。本记录不表示代码已经部署；真实训练停止时序、浏览器与客户安装仍须另验，完整证据和交付边界以本节为准。
