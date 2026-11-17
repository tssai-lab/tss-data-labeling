# LS 后端源码适配模块

这是仓库内可修改的 Python 源码，由现有 Java 标注服务调用。不启动 Django 或完整 LS 服务，也不在后端运行编辑器。

## 当前已实现

- 使用 LS 的 `parse_config` 解析 XML，保留控件名称、对象绑定和标签。
- 复用 LS 后端配置 schema 校验与示例生成；配置页通过平台 HTTP 接口获取模板、字段建议、校验和预览，不依赖完整 LS 服务。
- 使用 LS 的 `Converter` 按配置判断导出格式。
- 开放 JSON、JSON_MIN、CSV、TSV；适用的检测配置还开放 COCO、YOLO。
- 输入是原生 `task.data`、`annotations[].result`，可包含多通道时序、CSV 资源引用、分类和区间结果，不依赖能耗输入结构。
- 图片等资源仅从 Java 业务层提供的清单读取；不连接 LS 服务、不访问远程资源。
- Java 调用有并发上限、超时终止、私有任务目录和完成后清理；ZIP 输出写入调用方的输出流。

配置检查接口：`POST /api/labeling/v1/label-configs/inspect`，需要现有统一身份登录且 actor 启用。

请求：`{"labelConfig":"<View>...</View>"}`。响应沿用 `data/requestId`，`data` 包含 `parsedConfig` 和 `formats`。
每个格式包含 `name/title/supported/enabled`；`supported` 是上游根据控件的判断，`enabled` 是平台当前已验证可调用的子集。
这里是配置解析和导出能力检查，**不替代发布时的可信模板、结果语义及完成规则校验**。

## 本地运行

Python 3.9+，本地依赖锁定记录见 `requirements.txt`。启动 Java 服务前准备相应 Python 环境，配置：

```yaml
labeling:
  ls-source:
    python: D:/anaconda3/python.exe
    adapter-root: D:/projects/Annotation/backend/labeling-service/ls-source-adapter
    work-root: D:/projects/Annotation/backend/labeling-service/data/labeling/ls-jobs
    timeout-seconds: 30
```

这些值可通过 Spring 外部配置或 `LABELING_LS_SOURCE_PYTHON` 等标准环境变量设置。仓库不写入上述机器路径作为默认值。
默认相对路径以 `labeling-service` 为工作目录；打包 JAR 时本目录需和 JAR 分别提供，尚未添加分发打包脚本。

单独验证源码：在本目录执行 `python -X utf8 -m unittest discover -s tests -v`。
Windows 沙箱如不支持系统临时目录内的原子重命名，可将当前命令的 `TMP/TEMP` 指向本目录 `.test-jobs`。
Java 实际调用测试需加 `-Dls.test.python=<python路径>`；未传时会明确跳过相应测试，不算验证通过。

## 内部文件协议 v1

Java 创建唯一任务目录，写入 `request.json`，运行 `python -E -s -X utf8 adapter.py <任务目录>`；stdout 仅输出一个版本化 JSON 响应，诊断写 stderr。

检查配置：`{"protocolVersion":1,"action":"inspect","labelConfig":"..."}`。

即时校验：action=`validate`，返回 parsedConfig；生成示例：action=`sample`，返回 sample_task、受控资源清单和 isExample。它们不加载导出模块，也不写项目或答案。

导出：`{"protocolVersion":1,"action":"export","labelConfig":"...","format":"CSV","resources":{}}`，另有 `tasks.json` 原生任务数组。
`resources` 把任务中使用的资源引用映射到该任务目录 `resources/` 下的文件名。Java 只从平台已授权的文件存储路径复制资源。
产物写 `output/`，响应返回相对文件清单和大小。正式成果导出由异步任务逐任务写入文件，再按目录迭代转换；兼容内部 tasks.json 数组输入。容量仍有配置限制，不能据此宣称无限量导出。

JSON 保留原始任务和标注字段；JSON_MIN、CSV、TSV 保持 LS 原生转换语义，例如单项 Choices 可变成单值。它们不是原始采样 CSV 的逐行回填格式。
COCO 的 `file_name` 保留可追踪的输入资源引用，YOLO 文件名使用样本 ID；成果业务层负责打包配置和相应输入资源。

## 业务连接与验收边界

`LsSourceAdapter.writeExportZip` 是业务层内部方法，公共导出接口只接受授权的固定成果快照，不接受任意上传结果。规范冻结、原生任务导入/读取、草稿/提交/审核、项目完成和导出作业/下载已接入；旧图片分类结果由导出业务层显式转换。

配置页 HTTP 接口包括 `/label-configs/templates`、`data-summary`、`validate`、`sample-task`、`preview-image`，均要求登录和启用状态。实际样本预览额外检查项目所有权与样本所属项目；不匹配的字段或资源明确失败。示例生成不能冒充真实任务。

基础时序人工闭环已通过本地真实页面验证。完整真实数据容量、更多模板、多轮与 AI 验收尚未完成，详细记录见后端 docs/labeling-v1.1/19-native-workflow-progress.md 和前端 docs/ls-config-http.md。

源码版本和逐项修改见 [SOURCE.md](SOURCE.md)。
