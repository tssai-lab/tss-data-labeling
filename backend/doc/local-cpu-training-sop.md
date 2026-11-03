# TSS Platform 本机 CPU 训练闭环 SOP

## 1. 本次验收范围

本 SOP 只验证训练，不要求启动或验证推理服务：

```text
前端注册/登录
  -> 上传模型 ZIP
  -> 上传 YOLO 数据集 ZIP
  -> 创建 CPU 训练任务
  -> 回传状态和进度
  -> 回传训练指标
  -> 回传日志及输出产物路径
```

当前本机目录：

```text
/opt/tss-platform/backend
/opt/tss-platform/frontend
```

需要的服务：

| 服务 | 地址 |
|---|---|
| 前端 | `http://127.0.0.1:8000` |
| 主后端 | `http://127.0.0.1:8080` |
| PostgreSQL | `127.0.0.1:5432` |
| MinIO API | `127.0.0.1:9010` |
| MinIO Console | `http://127.0.0.1:9011` |
| MLflow | `http://127.0.0.1:5000` |

不需要：

- 启动 8081 推理服务。
- 创建 `tss_inference` 数据库。
- 构建推理镜像。
- 验证部署、在线推理和推理记录。

本 SOP 不删除现有数据库、MinIO 对象或 Docker 数据卷。

## 2. 先确认基础容器

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

至少应存在并处于运行状态：

```text
tss-postgres
tss-minio
tss-mlflow
```

分别检查：

```bash
docker exec tss-postgres pg_isready -U postgres
curl -fsS http://127.0.0.1:9010/minio/health/live
curl -fsS http://127.0.0.1:5000/ >/dev/null
```

## 3. 启动主后端

打开终端一：

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

启动日志应包含：

```text
Tomcat started on port 8080
Started TssPlatformApplication
```

健康性检查：

```bash
curl -i http://127.0.0.1:8080/api/user/current-user
```

未登录时返回 HTTP 401 是正常的，说明服务已经响应。

## 4. 启动前端

首次运行安装依赖：

```bash
cd /opt/tss-platform/frontend
npm ci
```

当前依赖树中 `camera-controls` 会提示推荐 Node.js 22，但本机 Node.js 20 可以完成当前项目的生产构建。验证命令：

```bash
npm run build
```

当前仓库的 `npm run tsc` 仍有若干既存类型错误，集中在模拟数据、任务类型和资源监控声明。生产构建可以成功，但后续应单独修复这些类型问题。

打开终端二并启动：

```bash
cd /opt/tss-platform/frontend

HOST=0.0.0.0 \
PORT=8000 \
DEV_API_TARGET=http://127.0.0.1:8080 \
DEV_MLFLOW_TARGET=http://127.0.0.1:5000 \
npm run start:dev
```

浏览器打开：

```text
http://127.0.0.1:8000/#/user/register
```

必须显式设置 `DEV_API_TARGET`，否则当前前端默认连接远程后端，而不是本机 8080。

## 5. 准备训练测试包

推荐先运行训练冒烟脚本，并保留脚本生成的文件：

```bash
KEEP_WORKDIR=true \
/opt/tss-platform/backend/scripts/local-cpu-closed-loop-smoke.sh
```

脚本默认只执行训练，不连接 8081。结束时会输出类似：

```text
Work directory retained: /tmp/tss-cpu-smoke-XXXXXX
```

其中包含：

```text
cpu-yolo-model.zip
cpu-yolo-dataset.zip
```

模型 ZIP 只是合法的测试资产；当前本机训练器只统计模型文件大小，不会读取其中的模型权重。

## 6. 从前端执行训练

### 6.1 注册和登录

1. 在注册页创建本机测试用户。
2. 回到登录页登录。
3. 打开浏览器开发者工具的 Network 面板。
4. 确认 `/api/user/login` 和 `/api/user/current-user` 返回 HTTP 200。

### 6.2 上传模型

进入：

```text
模型管理 -> 上传模型
```

选择 `cpu-yolo-model.zip`，模型类型选择 `CV`，完成上传后记录真实的模型版本 ID：

```text
model-ver-...
```

### 6.3 上传数据集

进入：

```text
数据集管理 -> 上传数据集
```

选择 `cpu-yolo-dataset.zip`，参数使用：

```text
任务类型：CV
CV 任务：OBJECT_DETECTION
标注格式：YOLO
版本：v1
```

完成后记录真实的数据集版本 ID：

```text
dataset-ver-...
```

ZIP 内至少需要存在同名图片和标签：

```text
images/sample.png
labels/sample.txt
```

YOLO 标签示例：

```text
0 0.5 0.5 0.5 0.5
```

### 6.4 创建 CPU 训练任务

进入：

```text
任务管理 -> 创建任务
```

选择刚上传的模型版本和数据集版本。代码版本可以保持前端默认值：

```text
frontend-training-demo
```

建议超参数：

```json
{
  "epochs": 3,
  "lr0": 0.05,
  "batch_size": 4,
  "imgsz": 640,
  "device": "cpu"
}
```

提交后记录：

```text
training id
experimentId
versionNo
```

### 6.5 检查训练结果回传

任务详情页应自动轮询，最终满足：

```text
status = success
progress = 100
metrics 非空
logPath 非空
outputPath 非空
```

当前训练输出通常为：

```text
minio://training-results/<training-id>/local-regressor.json
```

训练日志通常为：

```text
minio://training-results/<training-id>/train.log
```

## 7. 自动训练冒烟测试

不经过浏览器、直接验证同一套后端接口：

```bash
/opt/tss-platform/backend/scripts/local-cpu-closed-loop-smoke.sh
```

可调整等待时间：

```bash
PLATFORM_URL=http://127.0.0.1:8080 \
TRAIN_TIMEOUT_SECONDS=300 \
POLL_SECONDS=2 \
/opt/tss-platform/backend/scripts/local-cpu-closed-loop-smoke.sh
```

脚本自动完成：

1. 注册并登录。
2. 生成合法模型 ZIP 和最小 YOLO 数据集 ZIP。
3. 分片上传模型。
4. 分片上传数据集。
5. 创建 `device=cpu` 的训练任务。
6. 轮询任务状态。
7. 校验训练成功、指标和输出路径。

成功标志：

```text
========== CPU TRAINING LOOP PASSED ==========
Training status: success
Training output: minio://...
Training metrics: ...
```

## 8. 数据库和 MinIO 验收

查询最近训练记录：

```bash
docker exec tss-postgres \
  psql -U postgres -d tss -c \
  "SELECT id, experiment_id, version_no, status, progress,
          model_version_id, dataset_version_id, log_path, output_path
   FROM training_experiment_version
   ORDER BY created_at DESC
   LIMIT 10;"
```

检查训练产物：

```bash
docker exec tss-minio \
  find /data/models/training-results \
  -type f \
  \( -name 'local-regressor.json' -o -name 'train.log' \)
```

这是本机开发环境对 MinIO 数据目录的只读检查，不要修改或删除 `/data/models` 下的文件。

前端任务详情、后端接口和数据库中的以下字段必须一致：

```text
training id
experimentId
versionNo
modelVersionId
datasetVersionId
status
outputPath
```

## 9. 当前训练功能的真实性边界

当前确实会执行 CPU 训练，但它是 Java 实现的轻量线性框回归器：

- 解析 YOLO 标签。
- 读取图片并计算简单图像特征。
- 迭代训练框坐标回归参数。
- 将指标写回数据库和 MLflow。
- 将 `local-regressor.json`、`train.log` 写入 MinIO。

当前还不是完整的 YOLO/PyTorch 训练：

- 上传的模型权重不会参与参数更新，只会校验模型资产并统计文件大小。
- `codeVersionId` 只被记录，没有拉取或执行对应训练代码。
- `batch_size`、`imgsz` 和 `device` 会被保存，但本地训练器实际只读取 `epochs` 和 `lr0`。
- 页面中的“下载”入口尚未形成完整的训练产物下载链路。

所以本次可验收的是“训练任务业务闭环和轻量 CPU 训练执行”，不能验收“上传模型的微调训练”。

## 10. 防止前端模拟数据造成误判

模型、数据集、任务列表和部分仪表盘在接口失败时可能回退到模拟数据。

不能只看页面出现了列表项。必须同时确认：

- Network 面板请求为 HTTP 200。
- 本次上传生成了新的真实版本 ID。
- 任务详情实验 ID 与数据库一致。
- 数据库状态为 `success`、进度为 `100`。
- MinIO 中存在对应输出和日志对象。

资源监控页面不纳入本次训练闭环验收。

## 11. 常见故障

### 11.1 页面请求到了远程地址

停止并重新启动前端，确认设置：

```bash
DEV_API_TARGET=http://127.0.0.1:8080
DEV_MLFLOW_TARGET=http://127.0.0.1:5000
```

### 11.2 任务失败：未解析到 YOLO label

检查 ZIP：

```bash
jar tf /tmp/tss-cpu-smoke-*/cpu-yolo-dataset.zip
```

图片与标签的文件名主干必须相同，例如：

```text
images/sample.png
labels/sample.txt
```

### 11.3 任务一直处于 pending

检查后端日志：

```bash
ps -ef | grep '[s]pring-boot:run'
```

确认训练启动配置没有关闭：

```text
training.local-runner-enabled=true
```

### 11.4 MLflow 无指标

训练是否成功与 MLflow 展示是两层验收。先检查后端训练详情和数据库，再检查：

```bash
curl -v http://127.0.0.1:5000/
```

后端启动环境必须包含：

```text
TRAINING_MLFLOW_ENABLED=true
TRAINING_MLFLOW_TRACKING_URI=http://127.0.0.1:5000
```

### 11.5 页面有数据但数据库没有

这是前端回退到模拟数据的典型表现。以 Network 面板、任务详情接口、数据库和 MinIO 为准。

## 12. 停止服务

前端终端按 `Ctrl+C`。

主后端终端按 `Ctrl+C`。

保留 PostgreSQL、MinIO 和 MLflow 容器，不要执行：

```bash
docker compose down -v
```
