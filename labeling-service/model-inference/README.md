# 独立预标注模型

使用 Python 3.11 或兼容的运行环境，从本目录执行：

```powershell
python prelabel.py yolo request.json
```

第一个参数可为 yolo、grounded、prompted 或 timeseries。标准输出为 JSON，
包含 annotations 和 confidence。模型日志走标准错误。输入使用可信的本地文件路径。
图像任务的项目配置由调用方解析，再提供对应控件的绑定和可用标签。

```json
{
  "image": "image.jpg",
  "weights": "yolo11n.pt",
  "mode": "detection",
  "binding": {"fromName": "objects", "toName": "image", "labels": ["人"]},
  "classMap": {"person": "人"}
}
```

| 模型 | 依赖和请求字段 |
| --- | --- |
| YOLO | ultralytics、Pillow。weights 为本地权重。mode 可为 classification、detection、segmentation 或 pose，权重类型须匹配。classMap 将模型类别映射到项目标签。pose 按索引接收 keypointLabels 和浮点 keypointThreshold。 |
| Grounding DINO | transformers、torch、Pillow。weights 为本地模型目录，prompts 为英文对象描述到项目标签的映射，boxThreshold 为检测阈值。提供 samWeights 时使用 ultralytics SAM，输出多边形。 |
| 提示词分类 | llama-cpp-python。weights 为本地 GGUF 文件。guidelines 为业务判定规则，sample 可传文本或结构化数据。图片分类另需 image 和 projector，以及提供 Qwen3VLChatHandler 的运行版本。默认输出一个 Choices 标签。设置 mode 为 timeseries 可输出 TimeSeriesLabels 区间。 |
| 时序 LSTM | torch。提供 times 数值时间轴、values 时间点 × 通道矩阵、weights 本地训练产物。连续同类点转换为 TimeSeriesLabels，null 类为空白。 |

时序训练输入是 JSON 数组，每项如
`{"values":[[0],[8],[9]],"labels":[null,"峰值","峰值"]}`。
每行一个标签，顺序与输入一致。用人工确认的样本训练：

```powershell
python train_timeseries.py labeled_sequences.json series.pt
python prelabel.py timeseries request.json
```

标注候选需交给平台按冻结配置做完整校验后才能保存。当前提供模型入口和结果转换，
真实权重、训练效果及端到端流程待在具备资源的环境验收。

提示词时序模式另传 times 和 values，分别是严格递增的小时坐标和对应能耗值。
缺测值可传 null。sample 可提供站点背景和已有历史基线。
模型返回的区间端点必须出现在 times 中，标签必须属于 binding.labels；
缺少判定依据时允许返回空区间列表。
## 平台异步入口与权重位置

POST /api/labeling/v1/projects/{projectId}/prelabel-runs 使用 Idempotency-Key，
接收 specVersionId、modelId、samples 或 selectionId，以及可选 instructions。
返回 202 和现有执行记录。通过 GET /api/labeling/v1/ai-executions/{id}
及其 /results 路径读取进度和结果。

服务端通过以下环境变量将公开模型标识绑定到现有执行配置：

| modelId | 服务端配置 |
| --- | --- |
| qwen-image | LABELING_PRELABEL_QWEN_IMAGE_AGENT_ID |
| yolo-pcb | LABELING_PRELABEL_YOLO_PCB_AGENT_ID |
| qwen-timeseries | LABELING_PRELABEL_QWEN_TIMESERIES_AGENT_ID |

权重部署在实际运行 Worker 的机器上。例如 Linux 主机使用
/opt/annotation/models，容器以只读方式挂载至 /models。
每套权重按模型和版本建立子目录，Worker 配置引用具体本地路径。
Qwen 图像模型同时配置 GGUF 和视觉投影；YOLO 使用匹配 PCB 类别的专用权重。
平台数据库保存模型版本和执行配置引用，权重文件由推理运行环境管理。

平台入口复用现有持久队列。独立 Worker 使用已有的内部执行接口，
前端从 AI 发布页提交批次并查询候选。

## Worker 请求接入

为每种固定模型配置一个内部执行身份和独立凭据。管理员使用现有登记接口
登记 COMPUTER_VISION 或 TIME_SERIES 类型的执行身份，再通过上面列出的变量
将其 ID 绑定到固定模型。Worker 凭据使用 LABELING_MODEL_WORKER_KEY 注入。
每个 Worker 进程只消费其凭据对应的任务，模型在第一次推理时加载并在进程内复用。

```json
{
  "baseUrl": "http://127.0.0.1:8083",
  "model": {
    "mode": "timeseries",
    "options": {
      "weights": "/opt/annotation/models/qwen3-vl-2b/model.gguf",
      "guidelines": "按项目规则选择标签，证据不足返回空列表"
    }
  }
}
```

运行 python worker.py worker-config.json。使用 --once 可以只领取一次。
mode 为 image 时设置视觉 projector；mode 为 pcb 时配置检测权重和 classMap。
时序首版要求单通道列式 JSON，使用冻结 XML 指定时间字段和通道字段。
读取样本和图片资源均使用执行凭据、样本范围和有效租约。
图像分类和能耗时序共用项目「标注说明」作为判断标准。Worker 从批次绑定的规范版本
读取 `specInstructions`，发布时填写的本轮说明作为补充。旧规范没有说明时仍可使用本轮说明或 Worker 默认说明。

服务器 Qwen Worker 可在 options 中设置 runtimeUrl，连接同机回环地址上的
llama-server。模型权重与视觉投影由 llama-server 读取。服务器预览使用
Qwen3-VL-2B-Instruct 的 GGUF 运行时验证了真实图像分类和能耗时序候选回写。
时序样本的部分区间与业务试标规则不符，流程验证不代表准确性验收通过。
PCB YOLO 在发布者对照图回写了缺陷框，VisA 目标图片的检出覆盖率仍待验证。

## 在线模型连接

在标注管理员的「用户管理」页面新增在线模型连接。正常的联合发布页会显示已启用连接。连接配置只显示密钥的环境变量名，实际密钥注入 Python Worker 进程。

1. Java 标注服务用 `LABELING_ONLINE_IMAGE_AGENT_ID` 和 `LABELING_ONLINE_TIMESERIES_AGENT_ID` 指向各任务类型的执行身份。对应 Worker 使用该执行身份的 `LABELING_MODEL_WORKER_KEY`，模式分别为 `image` 和 `timeseries`。
2. Worker 使用 `LABELING_ONLINE_MODEL_HOSTS` 配置逗号分隔的上游主机名。在线请求使用 HTTPS，且不跟随重定向。保留原本地 Worker 的配置可以继续处理历史执行。
3. 百炼连接选择 `openai-chat`，填写工作空间对应的完整 Chat Completions 地址和模型 ID，例如 `qwen3.6-plus`。在 Worker 进程注入 `DASHSCOPE_API_KEY`，在连接中引用该变量并关闭思考模式。[百炼部署地区和接口地址](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope)
4. 用户自建服务可选 `prediction-v1`。Worker 发出包含 `model`、`input` 和 `outputSchema` 的 JSON，图像任务另有 `image` 字段，值为 JPEG 或 PNG 的 Base64 数据 URL。预测服务直接返回与 `outputSchema` 匹配的 JSON。若需要 Bearer 鉴权，通过独立的 `LABELING_MODEL_API_KEY_` 前缀环境变量提供密钥。

在千问 AI 平台创建的按量付费 Key 使用 `https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions`，Worker 的主机配置对应 `maas.qianwenaiapi.com`。[千问 AI 平台按量付费接口](https://platform.qianwenai.com/docs/api-reference/preparation/api-key)

新增连接会保留旧配置。切换地址或模型时，新增连接并停用旧连接。每个批次保存提交时的连接配置，重试沿用该配置。先用获准的单样本发布验证连接可用，再扩大批次。

## Qwen 能耗站点日试标

energy-prelabel.xml 是人工和 Qwen 共用的站点日规范，采用原生时序结果格式。
在「标注设置」使用自定义 XML 粘贴该模板。在「标注说明」里用完整语句写明判断规则，
例如「当天用量低于过去 30 天日用量中位数的 60% 时，标为低负荷」。保存规范后，用该版本提交 AI 批次。
横轴为 hour_local，单位为 kWh，数值字段为 series.value_kwh。
冻结配置包含日级主标签、异常点或区间、判断依据和可选的人工自评。
Qwen 读取冻结说明、小时读数、当天总量和历史统计，直接输出日级标签、区间和判断依据。
Worker 校验 XML 中的标签和实际采样时间，转换为原生结果。证据不足时可给出「待复核」，人工自评留空。
历史规范继续按原计算方式执行。保存新规范会启用 `editorConfig.timeseriesPrelabelMode=instructions`。

用实际能耗数据包生成一份站点日任务 ZIP：

```powershell
python prepare_energy_tasks.py "<能耗数据 ZIP>" energy-preview.zip --station ST-0001 --date 2026-10-01 --date 2026-10-02 --date 2026-10-03
```

任务包的历史统计使用此前 30 天的数据，小时 P99/P5 使用线性插值，仅在历史完整时提供。
标注员按相同的说明核查候选及依据，需要现场信息的结论由人工核实。
