# 通用训练方案上线 SOP

## 目标

平台不再把训练逻辑写死在前端、Java 后端和 Python Worker 中。一次训练由**训练方案**把基础模型、数据集、训练代码和参数组合为不可变的 `RunSpec`，并以一个 Kubernetes Job 执行。每个任务使用独立 Pod，不会与其他训练或推理任务共用进程。

当前内置方案：

- `hf_image_classification`：HuggingFace 图像分类微调（CV），引用 `tss-cv-worker`；
- `yolo_object_detection`：真实加载上传的 YOLO 权重和数据集，执行上传的 `train.py`，输出并发布 `best.pt`（CV），引用 `tss-cv-worker`；
- `image_text_consistency_fusion_logreg`：保留兼容既有图文一致性流程（NLP），引用 `tss-nlp-worker`。

## 上线前必须准备

1. Kubernetes GPU 节点可创建 Job，节点可拉取 Worker 镜像。
2. MinIO、后端服务和 Worker 使用相同的内部回调令牌 `TRAINING_K8S_INTERNAL_CALLBACK_TOKEN`。不得删除此配置。
3. 构建并推送 CV / NLP 两套通用基础镜像（合同要求：不少于 2 套，覆盖 CV 与 NLP）：

   ```text
   # CV 通用镜像（图像分类、目标检测等）
   k8s/training-worker/Dockerfile.cv  →  registry.example.com/tss/tss-cv-worker:20260722
   # NLP 通用镜像（文本/表格/图文一致性等）
   k8s/training-worker/Dockerfile.nlp →  registry.example.com/tss/tss-nlp-worker:20260722
   ```

   本地 kind 集群可直接运行 `k8s/training-worker/build-and-load.sh`，脚本会构建 `tss-cv-worker:local` 与 `tss-nlp-worker:local` 并加载到 kind。

4. 训练方案 YAML 的 `runtimes.image` 已指向对应通用镜像（CV 方案指向 `tss-cv-worker`，NLP 方案指向 `tss-nlp-worker`）；生产环境需填写其不可变 digest。
5. 为测试准备固定版本的 `yolo11n.pt`、YOLO 数据集 ZIP、`examples/training/yolo_object_detection` 代码 ZIP 和两张固定推理图片。

## CV/NLP 通用基础镜像

平台预置两套通用基础镜像，覆盖合同要求的 CV 与 NLP 两类场景：

| 镜像 | 基础 | 预装库 | 覆盖方案 |
|---|---|---|---|
| `tss-cv-worker` | pytorch:2.7.1-cuda12.8-cudnn9-runtime（固定摘要） | torchvision + transformers + Pillow + opencv-python-headless + ultralytics + numpy + pandas | hf 图像分类、yolo 目标检测、其他 CV |
| `tss-nlp-worker` | pytorch:2.7.1-cuda12.8-cudnn9-runtime（固定摘要） | transformers + tokenizers + datasets + scikit-learn + numpy + pandas | fusion 图文一致性、qwen 微调、其他 NLP |

两套镜像都带 CUDA，CPU 任务（`gpuCount: 0`）同样可用，只是不使用 GPU。训练方案通过 `runtimes.image` 引用对应通用镜像，`deviceType` 与 `resourceProfiles.gpuCount` 决定是否调度到 GPU 节点。

当前 GPU 阶段只开放单卡任务：`deviceType: NVIDIA_GPU`、`gpuCount: 1`，
并使用可跨环境复用的 `tss.ai/accelerator=nvidia` 标签。多卡和分布式训练
需要独立完成通信、失败恢复和资源一致性设计后再开放。

通用镜像只装「重且通用」的库（torch/transformers 这种几个 G 的），覆盖 80% 常见场景。长尾依赖（小众库）由下一节「自动依赖镜像」处理，无需为每个新任务改通用镜像。

## 自动依赖镜像

训练代码 ZIP 可以带 `requirements.txt`。平台只接受确定性的 PyPI 包约束，例如：

```text
ultralytics==8.3.0
opencv-python>=4.9,<5
```

不接受 `git+`、URL、本地路径、`-r`、`--extra-index-url` 等内容；这样才能保证可复现，也避免在运行中的训练 Pod 内临时安装依赖。

启用自动构建时，后端会按「基础镜像 digest + requirements.txt 摘要」生成环境指纹：相同依赖复用同一派生镜像，不同依赖构建新的派生镜像。后端主机必须有 Docker 权限，并且 Kubernetes 所有节点都能访问同一个镜像仓库：

```text
TRAINING_K8S_RUNTIME_IMAGE_BUILD_ENABLED=true
TRAINING_K8S_RUNTIME_IMAGE_REPOSITORY=registry.example.com/tss/training-worker
TRAINING_K8S_RUNTIME_IMAGE_DOCKER_PATH=docker
TRAINING_K8S_RUNTIME_IMAGE_BUILD_DIRECTORY=/var/lib/tss/runtime-image-builds
TRAINING_K8S_RUNTIME_IMAGE_BUILD_TIMEOUT_SECONDS=1800
```

未配置这些条件时，含 `requirements.txt` 的任务会明确失败，不会悄悄忽略依赖或在 Pod 中临时 `pip install`。

## 新增训练方案

1. 在 `backend/src/main/resources/training-plans/` 增加 `方案ID-版本.yaml`。
2. 声明模型、数据集、代码入口、参数、运行镜像/资源规格以及必需输出物。
3. 代码 ZIP 的入口文件必须与方案 `execution.entrypoint` 一致；Worker 统一使用：

   ```text
   /workspace/job/model
   /workspace/job/data
   /workspace/job/code
   /workspace/job/output
   ```

4. 在非生产环境先完成一次成功、一次失败、一次停止的 Job 验证，再启用方案。

前端通过 `GET /api/training-plans` 获取可用方案；新增方案不需要再改前端的下拉框或 Worker 的硬编码分支。

## YOLO 闭环验收

1. 上传 `yolo11n.pt`（原始 `.pt` 或包含它的 ZIP）。
2. 上传包含 `data.yaml`、图片和标签的 YOLO 数据集 ZIP。
3. 上传示例 `train.py` 和 `requirements.txt` 组成的训练代码 ZIP，并等待自动准入通过。
4. 创建 `yolo_object_detection`、`FULL_FINETUNE`、`gpu-1-small` 任务。
5. 核对 Worker 日志：实际加载上传权重、实际读取 `data.yaml`、参数已传入脚本；输出必须有 `best.pt`、`last.pt`、`metrics.json`、`train.log`。
6. 检查 `best.pt` 已作为新的模型版本发布，SHA-256 与基础权重不同。
7. 推理任务选择该新模型版本；Worker 记录的模型 SHA-256 必须与训练产物相同。

前端“模型推理 → 推理任务”支持选择模型版本和独立推理脚本：单图推理会先调用
`POST /api/inference/inputs/upload` 将 jpg、jpeg、png、bmp 或 webp 上传到当前用户的受限目录；
批量推理直接选择已有数据集版本。两种方式都会创建独立 Kubernetes 推理 Job。

需要连续完成三次上述闭环，并覆盖损坏模型、缺少 `data.yaml`、错误参数、镜像不存在、MinIO 不可达、Job 创建失败、停止任务和回调短暂失败等异常，才可交付正式测试。
