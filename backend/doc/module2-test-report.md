# 模块二测试结果报告

## 1. 测试结论

本轮模块二后端功能测试通过。

2026-05-16 对模块二新增和修复后的边界能力做了增量回归测试，结果为 `54/54` 通过；测试后生成的临时业务资产已通过模块二删除接口清理，清理结果为 `5/5` 通过。

模块二当前可进入前端联调、演示验收和与其他模块的接口对接阶段。若进入公网或生产环境，仍建议先完成权限加固、配置密钥外置、上传额度和定时清理等工程化补强。

## 2. 测试信息

| 项目 | 内容 |
| --- | --- |
| 测试日期 | 2026-05-10；增量回归：2026-05-16 |
| 测试入口 | `http://127.0.0.1:8080` |
| 后端健康接口 | `/api/files/health` |
| 测试依据 | `module2-test-plan.md`、`module2-test-execution-guide.md`、2026-05-16 增量回归脚本 |
| 数据库 | PostgreSQL |
| 对象存储 | MinIO |
| 后端鉴权 | Sa-Token |

健康检查结果：

```json
{"success":true,"data":{"minio":"ok"},"errorMessage":null}
```

## 3. 测试账号

| 账号 | 密码 | 用户 ID | 角色 | 用途 |
| --- | --- | ---: | ---: | --- |
| `user_a` | `test123` | 3 | 3 | 普通用户，资源拥有者 |
| `user_b` | `test123` | 2 | 3 | 普通用户，越权隔离测试 |
| `admin01` | `test123` | 4 | 1 | 管理员视图测试 |

说明：本报告不记录 token。测试时通过登录接口动态获取 token。

## 4. 测试文件

### 4.1 最小功能测试文件

| 文件 | 类型 | 用途 |
| --- | --- | --- |
| `test-files/model-cv.zip` | CV 模型 | 模型上传、代码预览 |
| `test-files/cv-valid.zip` | CV 数据集 | CV 数据集上传 |
| `test-files/nlp-valid.txt` | NLP 数据集 | NLP 单文件上传 |
| `test-files/not-zip.pt` | 非法模型 | 非 zip 模型拒绝测试 |
| `test-files/fake-model.zip` | 非法模型 | 扩展名为 zip 但内容非 zip |
| `test-files/cv-no-image.zip` | 非法 CV 数据集 | CV zip 无图片拒绝测试 |
| `test-files/nlp-invalid.zip` | 非法 NLP 数据集 | NLP zip 混入非法文件拒绝测试 |
| `test-files/cv-casting-512x512/` | CV 图片文件夹 | CV 文件夹上传增量回归 |

### 4.2 真实数据集测试文件

| 文件 | 类型 | 文件数 | 大小 | 分片数 | 结果 |
| --- | --- | ---: | ---: | ---: | --- |
| `test-files/nlp-outputs.zip` | NLP | 131 个 JSON | 9.42 MB | 2 | 通过 |
| `test-files/nlp-outputs-2.zip` | NLP | 145 个 JSON | 10.13 MB | 3 | 通过 |
| `test-files/cv-casting-512x512.zip` | CV | 1300 张 JPEG | 30.65 MB | 7 | 通过 |
| `test-files/cv-casting-data.zip` | CV | 7348 张 JPEG | 68.67 MB | 14 | 通过 |
| `test-files/cv-extra.zip` | CV | 455 张 PNG | 188.94 MB | 38 | 通过 |
| `test-files/nlp-extra.zip` | NLP | 4 个 TXT | 42.98 MB | 9 | 通过 |
| `archive.zip` | CV / YOLO 目标检测 | 10668 张 JPG、10668 个 TXT 标注、1 个 data.yaml | 1.10 GB | 226 | 通过 |

说明：原始 `NLP.zip` 内包含 3 个 `.csv` 和 1 个 `.txt`。本轮测试时后端尚未放开 `.csv`，因此当时将 CSV 文本内容保留不变，整理为 `.txt` 后重新打包为 `test-files/nlp-extra.zip`。后续代码已扩展 NLP 白名单，新的后端版本可直接接收 `.csv`、Excel、PDF、Word 和 XML 文件。

`archive.zip` 识别为 `pcb-defect-dataset`，数据集配置文件为 `pcb-defect-dataset/data.yaml`，任务类型为 PCB 缺陷目标检测。目录划分为 `train/images` 8534 张、`val/images` 1066 张、`test/images` 1068 张；类别包括 `mouse_bite`、`spur`、`missing_hole`、`short`、`open_circuit`、`spurious_copper`。本轮按 `type=CV`、`cvTaskType=OBJECT_DETECTION`、`annotationFormat=YOLO` 测试。

## 5. 核心资源记录

### 5.1 模型资源

| 字段 | 值 |
| --- | --- |
| 模型名称 | `resnet-test-20260510160032` |
| 模型版本 ID | `model-ver-1157c57aabdd4af3ab829179d2f4df30` |
| 类型 | `CV` |
| 归属用户 | `user_a` / `ownerUserId=3` |
| 存储路径 | `users/3/models/resnet-test-20260510160032/v1/model-cv.zip` |

说明：上表为 2026-05-10 历史测试资源记录。2026-05-16 修复后，新模型上传最终路径已调整为 `users/{userId}/models/{assetId}/{version}/{fileName}`，避免同名模型、同版本、同文件名重复上传时覆盖 MinIO 对象。

### 5.2 数据集资源

| 数据集名称 | 类型 | 版本 ID | 大小 | 归属用户 |
| --- | --- | --- | ---: | --- |
| `cv-dataset-test-20260510160032` | CV | `dataset-ver-b213e3c505bc413998632a499596b4da` | 336 B | `user_a` |
| `nlp-dataset-test-20260510160032` | NLP | `dataset-ver-a85331cdfe544038930d22bf663def3f` | 43 B | `user_a` |
| `nlp-outputs-20260510160331` | NLP | `dataset-ver-b507b662e9044835aaaaf7dc564fda2f` | 9.42 MB | `user_a` |
| `nlp-outputs-2-20260510160331` | NLP | `dataset-ver-5b0829dc2ba14cf4b775febf4f2f07b0` | 10.13 MB | `user_a` |
| `cv-casting-512x512-20260510160331` | CV | `dataset-ver-f010260bfa6540749dffc69eb9eebabb` | 30.65 MB | `user_a` |
| `cv-casting-data-20260510160331` | CV | `dataset-ver-2b3fe3ecb3cc4a13ba57887dae27920b` | 68.67 MB | `user_a` |
| `cv-extra-20260510165306` | CV | `dataset-ver-f0f8c8f6c0554eaaa8652be52900d53e` | 188.94 MB | `user_a` |
| `nlp-extra-20260510165306` | NLP | `dataset-ver-a43847b81ccb413d97b7cdac85629484` | 42.98 MB | `user_a` |
| `pcb-defect-dataset-archive-20260510213351` | CV / YOLO | `dataset-ver-9c48172e8930451c83166ee4bb80c2ba` | 1.10 GB | `user_a` |

本轮 `archive.zip` 资源补充信息：

| 字段 | 值 |
| --- | --- |
| 数据集资产 ID | `dataset-asset-ff1b44f998194c25b9cc64a7f0388be1` |
| 上传会话 ID | `dataset-upload-1778420032365-8846fbb4aee84495a8c2644c29b392da` |
| CV 子任务 | `OBJECT_DETECTION` |
| 标注格式 | `YOLO` |
| 存储路径 | `users/3/datasets/dataset-asset-ff1b44f998194c25b9cc64a7f0388be1/v1/archive.zip` |
| 分片结果 | `226/226` 上传完成 |

### 5.3 训练实验资源

| 字段 | 值 |
| --- | --- |
| 实验名称 | `train-cv-20260510160032` |
| 实验 ID | `exp-1778400033606-86e1bf74ac38` |
| 最新训练版本 ID | `train-ver-4f9cf40ce81345529fab128f55790352` |
| 最新版本号 | 2 |
| 模型版本 ID | `model-ver-1157c57aabdd4af3ab829179d2f4df30` |
| 数据集版本 ID | `dataset-ver-b213e3c505bc413998632a499596b4da` |
| 最新超参数 | `{"epochs":3,"batchSize":8}` |
| 归属用户 | `user_a` / `ownerUserId=3` |

## 6. 用例结果

| 用例类别 | 测试内容 | 结果 |
| --- | --- | --- |
| 健康检查 | `/api/files/health` 返回 MinIO 正常 | 通过 |
| 鉴权 | `user_a`、`user_b`、`admin01` 登录 | 通过 |
| 鉴权 | 未登录访问 `/api/model/list` | 已拦截 |
| 模型上传 | CV 模型 zip 初始化、分片、完成 | 通过 |
| 模型查询 | 用户 A 可查询自己的模型列表和详情 | 通过 |
| 模型预览 | 可列出 zip 内代码文件并预览 `train.py` | 通过 |
| 数据集上传 | CV zip 上传 | 通过 |
| 数据集上传 | NLP txt 上传 | 通过 |
| 数据集查询 | 用户 A 可查询自己的数据集列表 | 通过 |
| 数据集筛选 | `/api/dataset/list?type=CV` 只返回 CV 数据集 | 通过 |
| 用户隔离 | 用户 B 不能查询用户 A 的模型详情 | 通过 |
| 用户隔离 | 用户 B 不能查询用户 A 的数据集版本 | 通过 |
| 用户隔离 | 用户 B 列表不包含用户 A 的资源 | 通过 |
| 管理员视图 | 管理员可看到用户 A 的模型 | 通过 |
| 管理员视图 | 管理员可看到用户 A 的数据集 | 通过 |
| 训练实验 | 创建 CV 训练实验 | 通过 |
| 训练实验 | 查询任务列表和详情 | 通过 |
| 训练实验 | 停止任务 | 通过 |
| 训练实验 | 查询实验版本历史 | 通过 |
| 训练实验 | 创建实验版本 2 | 通过 |
| 训练实验 | 修改版本 2 超参数 | 通过 |
| 类型校验 | CV 模型搭配 NLP 数据集创建任务 | 已拒绝 |
| 越权校验 | 用户 B 使用用户 A 资源创建任务 | 已拒绝 |
| 文件校验 | 非 zip 模型初始化 | 已拒绝 |
| 文件校验 | 假 zip 模型完成上传 | 已拒绝 |
| 文件校验 | CV zip 无图片 | 已拒绝 |
| 文件校验 | NLP zip 包含非法文件 | 已拒绝 |
| 安全回归 | 普通用户篡改 `storagePath` | 已拒绝 |
| 真实数据集 | 两个 NLP outputs zip 分片上传 | 通过 |
| 真实数据集 | 两个 CV casting zip 分片上传 | 通过 |
| 追加数据集 | `cv-extra.zip` 38 分片上传 | 通过 |
| 追加数据集 | `nlp-extra.zip` 9 分片上传 | 通过 |
| 大文件数据集 | 根目录 `archive.zip` 内容识别、226 分片上传、YOLO 标注格式校验、complete 合并 | 通过 |
| 大文件数据集 | `archive.zip` 上传后 progress、dataset version、dataset list 查询 | 通过 |

### 6.1 2026-05-16 增量回归结果

本轮增量回归只覆盖 `module2-test-report.md` 中未充分覆盖的模块二能力，以及本次修复后的回归点。模块一接口仅用于登录获取 token，不计入模块二测试结论。

| 用例类别 | 测试内容 | 结果 |
| --- | --- | --- |
| CRUD 安全回归 | `/api/model-assets` 创建时传入客户端 `id`，服务端强制生成新 ID | 通过 |
| CRUD 安全回归 | `/api/dataset-assets` 创建时传入客户端 `id`，服务端强制生成新 ID | 通过 |
| CRUD 安全回归 | `/api/model-versions` 创建时传入客户端 `id`，服务端强制生成新 ID | 通过 |
| CRUD 安全回归 | `/api/dataset-versions` 创建时传入客户端 `id`，服务端强制生成新 ID | 通过 |
| CRUD 安全回归 | 使用已有资源 ID 再次创建资产/版本，不会覆盖原记录 | 通过 |
| CRUD 权限隔离 | 普通用户 B 不能访问普通用户 A 的模型资产、模型版本、数据集资产、数据集版本详情 | 通过 |
| 管理员视图 | 管理员可访问普通用户 A 的模块二 CRUD 详情接口 | 通过 |
| 模型上传回归 | 同一用户以相同 `modelName + version + fileName` 连续上传两次模型文件 | 通过 |
| 模型上传回归 | 两次上传返回不同 `storagePath`，且路径中包含各自 `assetId` | 通过 |
| 训练结果回写 | `/api/task/result` 回写 `status`、`progress`、`metrics`、`logPath`、`outputPath` | 通过 |
| 训练结果回写 | `/api/experiments/{experimentId}/versions/{versionNo}/result` 回写成功状态和完成时间 | 通过 |
| 删除引用保护 | 被训练实验引用的模型版本拒绝删除 | 已拒绝 |
| 删除引用保护 | 被训练实验引用的数据集版本拒绝删除 | 已拒绝 |
| 删除回归 | 删除训练实验解除引用后，模型版本和数据集版本可软删除且详情不可见 | 通过 |
| 文件对象 | `/api/files/upload`、`download`、`delete` 完成对象上传、内容校验和删除入队 | 通过 |
| 数据集文件夹上传 | `/api/dataset/upload/folder` 上传 CV 图片文件夹并生成数据集资产/版本 | 通过 |
| 删除任务 | 删除有对象文件的模型/数据集资产时，MinIO 删除任务成功入队 | 通过 |
| 清理 | 增量测试生成的 5 个临时资产通过模块二删除接口清理 | 通过 |

关键验证样例：

| 项目 | 值 |
| --- | --- |
| 增量测试批次 | `20260516180855` |
| 模型重复上传路径 1 | `users/3/models/model-asset-73591953eccd4e2e92053fa1a319430b/v-collision/model-cv.zip` |
| 模型重复上传路径 2 | `users/3/models/model-asset-312ab27f1d9f47a7aa21232d41d05d4e/v-collision/model-cv.zip` |
| 增量断言结果 | `54/54` 通过 |
| 清理结果 | `5/5` 通过 |

## 7. 自动脚本说明

测试过程中使用 PowerShell 辅助脚本调用接口。

2026-05-16 增量回归使用临时 Python 脚本调用接口，依赖 `requests`，测试入口为 `http://127.0.0.1:8080`。测试前临时启动后端，测试完成后已停止；PostgreSQL 与 MinIO 使用本地 Docker 容器 `tss-postgres`、`minio-tss`。脚本断言结果为 `54/54` 通过，清理脚本结果为 `5/5` 通过。

首轮脚本统计为 33 项中 27 项直接通过，6 项失败。复核后确认这 6 项均为脚本断言写法与实际响应结构不匹配导致，例如 PowerShell 对 `data.data` 数组字段的取值判断不准确。通过直接拉取接口原始响应复核后，这些项均确认通过。

复核的接口包括：

```text
GET /api/model/list
GET /api/dataset/list
GET /api/task/list
GET /api/model/code-files
```

`archive.zip` 单独使用后台 PowerShell 脚本执行大文件分片上传，过程日志和最终响应保存为：

```text
backend/archive-upload-test-progress.log
backend/archive-upload-test-result.json
```

本轮大文件上传统计：

| 项目 | 结果 |
| --- | --- |
| 文件大小 | `1183720755` bytes |
| 分片大小 | `5242880` bytes |
| 总分片数 | `226` |
| 上传时间 | 2026-05-10 21:33:52 至 2026-05-10 21:35:14 |
| complete 校验与合并 | 2026-05-10 21:35:14 至 2026-05-10 21:35:59 |
| 最终状态 | `COMPLETED` |

## 8. 问题与风险

### 8.1 已修复并完成回归的模块二问题

| 问题 | 修复结果 | 回归结果 |
| --- | --- | --- |
| 模块二 CRUD 创建接口可接收客户端传入的主键，存在覆盖已有资产/版本的风险 | `model-assets`、`dataset-assets`、`model-versions`、`dataset-versions` 创建时均强制由服务端生成 ID | 2026-05-16 增量回归通过 |
| 模型上传最终对象路径使用 `modelName/version/fileName`，同名同版本同文件名重复上传可能覆盖 MinIO 对象 | 新上传路径调整为 `users/{userId}/models/{assetId}/{version}/{fileName}` | 2026-05-16 增量回归通过 |

### 8.2 模块二后续工程加固项

| 项目 | 说明 | 是否阻塞当前交付 |
| --- | --- | --- |
| 统一错误码 | 模块二多处使用 `success=false + errorMessage`，错误码尚未统一 | 不阻塞 |
| DTO 收敛 | 部分 CRUD 接口直接接收实体对象，后续建议改为专用 DTO | 不阻塞 |
| `storagePath` 返回 | 普通用户响应中仍返回 MinIO 内部路径，建议后续减少暴露 | 不阻塞 |
| 上传额度 | 暂无用户级上传大小、次数、频率限制 | 不阻塞演示，生产建议补 |
| 清理任务 | 未完成分片、失败上传会话暂无定时清理；已完成资源删除会通过 MinIO 删除任务异步处理 | 不阻塞演示，长期运行建议补 |
| 数据库迁移 | 模块二当前已使用 Flyway 迁移并由 Hibernate `ddl-auto=validate` 校验；历史库首次接入时需要关注 Flyway baseline 和脏数据清理 | 不阻塞 |

说明：模块一公开注册、密码策略等问题不属于本报告本轮模块二测试范围，本次未纳入结论。

## 9. 验收结论

模块二以下能力已经验证通过：

```text
模型上传、查询、详情、删除前置能力
模型代码预览
CV/NLP 数据集上传和格式校验
真实 CV/NLP 数据集分片上传
大文件 YOLO 目标检测数据集分片上传与格式校验
用户级资源隔离
管理员全局查看
训练实验创建、版本管理、超参数修改、停止
训练结果回写
模型与数据集类型匹配校验
关键越权和文件安全回归
模块二 CRUD 创建接口主键覆盖回归
模型重复上传最终路径唯一性回归
CV 文件夹上传
通用文件对象上传、下载、删除入队
```

结论：

```text
模块二后端核心功能和 2026-05-16 增量回归测试通过。
可以进入前端联调与交付演示。
```
