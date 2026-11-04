# TSS Platform 本机 CPU 服务闭环 SOP

> 当前阶段只验收训练时，请使用
> [`local-cpu-training-sop.md`](./local-cpu-training-sop.md)。
> 本文保留完整训练加推理方案，推理不作为当前阶段前置条件。

## 1. 目标与验收边界

本 SOP 用于在当前 Linux 本机上，以 CPU 模式启动并验证以下链路：

```text
浏览器注册/登录
  -> 浏览器上传模型和数据集
  -> 浏览器创建训练任务
  -> 页面回显训练状态、指标、日志路径、输出路径
  -> 脚本创建推理部署记录
  -> 脚本上传测试图片并在线推理
  -> 查询持久化推理记录
```

前端代码位于：

```text
/opt/tss-platform/frontend
```

当前代码存在两个必须明确的边界：

- 前端已经实现用户、模型、数据集和训练任务页面，但没有部署管理和在线推理页面。
- 前端当前只代理主后端 `/api/**` 到 8080、代理 MLflow 到 5000，没有把推理服务 `/api/inference/**` 转发到 8081。

- 主平台训练输出是 `local-regressor.json`。
- DJL 推理服务不能加载该 JSON。
- 本 SOP 的部署步骤将真实训练实验 ID 与推理服务内置的 `yolov8n` 运行时模型绑定。

因此，当前闭环分为两段：

```text
前端页面 -> 主后端：登录、上传、创建训练、查看训练结果
自动脚本 -> 推理服务：部署、测试图片上传、推理、记录查询
```

本 SOP 可以验收“三个服务及各业务接口是否能串起来”，但不能证明“推理使用的模型就是本次训练产生的模型”，也不能从现有前端页面直接完成部署和推理。

真正的模型血缘闭环仍需补充：

```text
训练输出标准模型（PT/ONNX/TorchScript）
  -> 注册为 model_version
  -> 推理服务从共享 MinIO 下载
  -> 转换和加载该训练产物
```

## 2. 安全原则

本 SOP 默认复用已有的：

- `tss-postgres`
- `tss-minio`
- `tss-mlflow`
- Docker 网络 `tss-platform_default`

不会执行：

- `docker compose down -v`
- 删除 PostgreSQL 数据卷
- 删除 MinIO 数据
- 清空现有 `tss` 数据库

只会新增：

- PostgreSQL 数据库 `tss_inference`
- Docker 镜像 `tss-inference-cpu:local`
- Docker 容器 `tss-inference-cpu`
- 本次测试产生的用户、模型、数据集、训练和推理记录

## 3. 当前本机基线

已经确认：

| 项目 | 当前值 |
|---|---|
| Java | 17 |
| Maven | 3.9.x |
| Docker | 28.x |
| Docker Compose | 2.35.x |
| 主平台 PostgreSQL | `127.0.0.1:5432` |
| 主平台 MinIO API | `127.0.0.1:9010` |
| 主平台 MinIO Console | `127.0.0.1:9011` |
| MLflow | `127.0.0.1:5000` |
| 主平台后端 | `127.0.0.1:8080` |
| 推理服务 | `127.0.0.1:8081` |
| 前端开发服务 | `127.0.0.1:8000` |
| 共享 MinIO bucket | `models` |
| Node.js | 20.x |

推荐至少预留：

- 4 个 CPU 核心
- 8 GB 可用内存
- 15 GB 可用磁盘

## 4. 第一次运行：初始化推理数据库

确认基础容器：

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

创建推理数据库。命令是幂等的，数据库已存在时不会覆盖：

```bash
docker exec -i tss-postgres \
  psql -U postgres -d postgres <<'SQL'
SELECT 'CREATE DATABASE tss_inference'
WHERE NOT EXISTS (
  SELECT 1 FROM pg_database WHERE datname = 'tss_inference'
)\gexec
SQL
```

验证：

```bash
docker exec tss-postgres \
  psql -U postgres -d postgres -Atc \
  "SELECT datname FROM pg_database WHERE datname IN ('tss', 'tss_inference') ORDER BY datname;"
```

预期：

```text
tss
tss_inference
```

## 5. 构建 CPU 推理镜像

在 `/opt/inference_java2` 执行：

```bash
cd /opt/inference_java2
docker build -t tss-inference-cpu:local .
```

镜像包含：

- Java 17
- Python
- CPU 可运行的 PyTorch/DJL 环境
- 预置 `yolov8n` 等运行时模型

构建时间主要取决于 Python 和 Maven 依赖缓存。

## 6. 启动推理服务

先确认同名容器不存在：

```bash
docker ps -a --filter name='^/tss-inference-cpu$'
```

不存在时创建：

```bash
docker run -d \
  --name tss-inference-cpu \
  --restart unless-stopped \
  --network tss-platform_default \
  --cpus 4 \
  --memory 8g \
  -p 8081:8081 \
  -e SERVER_PORT=8081 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://tss-postgres:5432/tss_inference \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=password123 \
  -e MINIO_ENDPOINT=http://tss-minio:9000 \
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=password123 \
  -e MINIO_BUCKET=models \
  -e MINIO_AUTO_CREATE_BUCKET=false \
  -e DEV_USER_ID=1 \
  -e CUDA_VISIBLE_DEVICES='' \
  -e OMP_NUM_THREADS=4 \
  -e MKL_NUM_THREADS=4 \
  -e JAVA_TOOL_OPTIONS='-Xms512m -Xmx2g -XX:ActiveProcessorCount=4' \
  tss-inference-cpu:local
```

如果容器已经存在，只启动即可：

```bash
docker start tss-inference-cpu
```

查看日志：

```bash
docker logs -f tss-inference-cpu
```

健康检查：

```bash
curl -fsS http://127.0.0.1:8081/actuator/health
```

预期包含：

```json
{"status":"UP"}
```

确认内置运行时模型：

```bash
curl -fsS http://127.0.0.1:8081/api/models
```

结果中应存在：

```text
yolov8n
```

## 7. 启动 TSS 主后端

打开另一个终端：

```bash
cd /opt/tss-platform/backend

set -a
source /opt/tss-platform/.env.backend
set +a

export TRAINING_MLFLOW_ENABLED=true
export TRAINING_MLFLOW_TRACKING_URI=http://127.0.0.1:5000
export TRAINING_MLFLOW_EXPERIMENT_NAME=tss-training

./mvnw spring-boot:run \
  -Dspring-boot.run.arguments='--sms.expose-code=false'
```

启动成功后应看到：

```text
Tomcat started on port 8080
Started TssPlatformApplication
```

另一个终端检查端口：

```bash
curl -i http://127.0.0.1:8080/api/currentUser
```

返回 `401` 代表后端已经启动，只是请求未登录。

## 8. 启动前端

打开另一个终端：

```bash
cd /opt/tss-platform/frontend
npm ci
```

使用本机服务地址启动开发服务器：

```bash
cd /opt/tss-platform/frontend

HOST=0.0.0.0 \
PORT=8000 \
DEV_API_TARGET=http://127.0.0.1:8080 \
DEV_MLFLOW_TARGET=http://127.0.0.1:5000 \
npm run start:dev
```

启动后访问：

```text
http://127.0.0.1:8000/#/user/register
http://127.0.0.1:8000/#/user/login
```

这里必须设置 `DEV_API_TARGET`。如果不设置，前端当前默认会请求远程地址 `47.114.84.133:8080`，不会连接本机后端。

浏览器开发者工具的 Network 面板中确认：

```text
/api/user/login              -> 127.0.0.1:8000 代理到 8080
/api/model/**                -> 127.0.0.1:8000 代理到 8080
/api/dataset/**              -> 127.0.0.1:8000 代理到 8080
/api/training/**             -> 127.0.0.1:8000 代理到 8080
/mlflow-api/**               -> 127.0.0.1:8000 代理到 5000/ajax-api
```

### 8.1 用前端验证训练链路

按以下顺序操作：

1. 在“注册”页创建用户，然后登录。
2. 进入“模型管理 → 上传模型”，上传模型 ZIP。
3. 进入“数据集管理 → 上传数据集”，上传 YOLO 格式数据集 ZIP。
4. 进入“任务管理 → 创建任务”，选择刚上传的模型版本和数据集版本。
5. 将训练参数中的 `device` 保持为 `cpu`，提交任务。
6. 在“任务列表”和“任务详情”中等待状态变为成功。
7. 核对指标、日志路径和输出路径；启用 MLflow 后再核对任务详情中的 MLflow 指标。

如果没有现成测试包，可先运行第 9 节脚本。脚本会在 `/tmp/tss-cpu-smoke-*` 中生成模型 ZIP 和最小 YOLO 数据集 ZIP。

### 8.2 防止前端模拟数据造成误判

模型列表、数据集列表、任务列表和部分仪表盘代码在接口失败时会回退到前端模拟数据。因此“页面出现列表行”不等于后端已经跑通。

验收时必须同时满足：

- Network 面板对应请求为 HTTP 200。
- 上传后能打开本次资产的真实详情页。
- 数据库中能查到同一个模型版本、数据集版本或训练实验 ID。
- 任务详情中的实验 ID、状态和输出路径与后端返回一致。

资源监控页面目前也可能使用模拟数据，不纳入本闭环的成功标准。

### 8.3 当前前端不能完成的步骤

现有前端没有以下功能：

- 创建或查看推理部署。
- 上传推理文件。
- 发起在线推理。
- 查看推理记录。

同时，当前开发代理和 `scripts/serve-dist.cjs` 都没有为 8081 配置独立路由。部署和推理必须先使用第 9 节自动脚本或直接请求 `http://127.0.0.1:8081`。

如果后续要实现全浏览器闭环，推荐由主后端增加推理网关：

```text
浏览器 /api/inference/**
  -> 主后端 8080 校验 JWT 并解析用户 ID
  -> 推理服务 8081，写入可信 X-User-Id
```

不建议让浏览器自行填写 `X-User-Id`，否则用户身份可以被伪造。

## 9. 执行自动闭环冒烟测试

项目已提供脚本：

```text
/opt/tss-platform/backend/scripts/local-cpu-closed-loop-smoke.sh
```

执行：

```bash
chmod +x /opt/tss-platform/backend/scripts/local-cpu-closed-loop-smoke.sh

/opt/tss-platform/backend/scripts/local-cpu-closed-loop-smoke.sh
```

可以覆盖默认参数：

```bash
PLATFORM_URL=http://127.0.0.1:8080 \
INFERENCE_URL=http://127.0.0.1:8081 \
TRAIN_TIMEOUT_SECONDS=300 \
/opt/tss-platform/backend/scripts/local-cpu-closed-loop-smoke.sh
```

脚本自动执行：

1. 生成临时测试用户名。
2. 注册并登录主平台。
3. 使用 `/opt/inference_java2/model/yolo/yolov8n.pt` 创建模型 ZIP。
4. 通过分片上传接口创建模型资产和模型版本。
5. 使用 `yolo-bus.jpg` 创建最小 YOLO 数据集 ZIP。
6. 通过分片上传接口创建数据集资产和 READY 版本。
7. 创建训练任务。
8. 轮询训练状态直至 `success` 或 `failed`。
9. 检查 `metrics`、`logPath` 和 `outputPath`。
10. 使用相同的 `experimentId + versionNo` 创建推理部署记录。
11. 将测试图片上传到共享 MinIO。
12. 调用 CV 推理接口。
13. 查询持久化推理记录。

## 10. 成功验收标准

脚本最后应输出类似：

```text
Model version: model-ver-...
Dataset version: dataset-ver-...
Training experiment: exp-...
Training status: success
Training output: minio://training-results/train-ver-.../local-regressor.json
Deployment status: AVAILABLE
Inference record: infer-...
```

数据库检查：

```bash
docker exec tss-postgres \
  psql -U postgres -d tss -c \
  "SELECT experiment_id, version_no, status, progress, output_path
   FROM training_experiment_version
   ORDER BY created_at DESC
   LIMIT 5;"
```

```bash
docker exec tss-postgres \
  psql -U postgres -d tss_inference -c \
  "SELECT experiment_id, version_no, status, runtime_model_id
   FROM inference_deployment
   ORDER BY created_at DESC
   LIMIT 5;"
```

```bash
docker exec tss-postgres \
  psql -U postgres -d tss_inference -c \
  "SELECT experiment_id, version_no, status, latency_ms
   FROM inference_record
   ORDER BY created_at DESC
   LIMIT 5;"
```

关键关联字段必须一致：

```text
training_experiment_version.experiment_id
  = inference_deployment.experiment_id
  = inference_record.experiment_id
```

版本号也必须一致。

前端验收还应满足：

- 能从 `http://127.0.0.1:8000` 完成注册和登录。
- 能在页面上传模型、数据集并创建 CPU 训练任务。
- 任务详情展示的实验 ID 与数据库记录一致。
- 任务详情最终展示真实的成功状态和 `outputPath`，而不是模拟列表数据。

## 11. 结果真实性判定

完成本 SOP 后，可以确认：

- 主平台模型上传可用。
- 主平台数据集上传可用。
- CPU 训练任务可以执行。
- 训练状态和指标可以写回。
- 主平台与推理服务可以共享用户 ID、实验 ID 和 MinIO。
- 推理部署、推理调用和推理记录可用。
- 前端和主后端之间的注册、登录、模型、数据集、训练、MLflow 查询链路可用。

不能确认：

- 前端能够完成部署和推理；当前没有对应页面和服务调用。
- 上传的模型权重参与了训练。
- `codeVersionId` 对应的训练代码被执行。
- `local-regressor.json` 被推理服务加载。
- 推理结果来自本次训练产物。

当前部署使用的是推理镜像内置的 `yolov8n`：

```text
runtimeModelId = yolov8n
```

这一步是临时服务联调桥接，不是最终生产闭环。

## 12. 常见故障

### 12.1 前端仍然请求远程后端

确认启动前端的同一个终端设置了：

```bash
DEV_API_TARGET=http://127.0.0.1:8080
DEV_MLFLOW_TARGET=http://127.0.0.1:5000
```

修改环境变量后必须重启前端开发服务器。

### 12.2 前端页面有数据，但数据库没有记录

这通常是接口失败后回退到了模拟数据。打开浏览器 Network 面板，检查失败的 `/api/model/**`、`/api/dataset/**` 或 `/api/training/**` 请求。

不要用仪表盘数字、资源监控卡片或列表中的示例项作为闭环验收证据。

### 12.3 8081 端口被占用

```bash
docker ps --format '{{.Names}}\t{{.Ports}}' | grep 8081
```

停止冲突容器后重试，或将推理服务映射到其他端口：

```bash
-p 18081:8081
```

同时执行脚本时设置：

```bash
INFERENCE_URL=http://127.0.0.1:18081
```

### 12.4 推理服务连接不到 PostgreSQL

确认服务位于正确网络：

```bash
docker inspect tss-inference-cpu \
  --format '{{json .NetworkSettings.Networks}}'
```

结果应包含：

```text
tss-platform_default
```

### 12.5 推理读取不到测试图片

确认两个服务配置完全相同：

```text
MinIO endpoint
access key
secret key
bucket=models
```

容器内 endpoint 必须使用：

```text
http://tss-minio:9000
```

不能在容器内使用 `127.0.0.1:9010`。

### 12.6 训练任务失败：未解析到 YOLO label

检查数据集 ZIP：

```bash
jar tf /tmp/tss-cpu-smoke-*/cpu-yolo-dataset.zip
```

必须包含：

```text
images/yolo-bus.jpg
labels/yolo-bus.txt
```

### 12.7 MLflow 连接失败

```bash
docker ps --filter name=tss-mlflow
curl -v http://127.0.0.1:5000/
```

主后端必须使用：

```text
TRAINING_MLFLOW_TRACKING_URI=http://127.0.0.1:5000
```

### 12.8 CPU 推理速度较慢

首次推理会加载模型，通常明显慢于后续请求。不要把第一次请求耗时作为稳定性能指标。

可以调整：

```text
--cpus
--memory
OMP_NUM_THREADS
MKL_NUM_THREADS
JAVA_TOOL_OPTIONS
```

## 13. 停止服务

停止主后端：在运行 `spring-boot:run` 的终端按 `Ctrl+C`。

停止前端：在运行 `npm run start:dev` 的终端按 `Ctrl+C`。

停止推理容器但保留全部数据：

```bash
docker stop tss-inference-cpu
```

再次启动：

```bash
docker start tss-inference-cpu
```

不要执行 `docker compose down -v`。
