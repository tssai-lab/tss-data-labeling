# 图片导入接口细则（本地功能分支）

本轮范围：ZIP 导入 JPEG/PNG、样本分页与 owner 原图读取。统一前缀、身份和响应包装沿用 `04-api.md`。此文固定原文尚未给出的响应字段，不表示已部署或已完成真实数据集验收。

## 批次

创建输入：`filename` 为不含目录的 ZIP 名称，`sizeBytes` 为正整数字节数，`sha256` 为 64 位十六进制字符串。大小不能超过部署上限。项目必须属于调用者且 taskType=IMAGE_CLASSIFICATION。

创建（201）、查询（200）、complete/retry（202）的 `data` 为同一结构：

| 字段 | 类型/含义 |
|---|---|
| batchId、projectId | UUID 字符串 |
| status | UPLOADING / PROCESSING / READY / FAILED |
| sizeBytes、receivedBytes | 整包声明大小、已持久化接收大小 |
| chunkSizeBytes | 服务器为此批次冻结的分片尺寸 |
| originalComplete | 是否曾通过整包长度和 SHA-256 校验；重试时仍会重新检查文件 |
| validSampleCount | 完成解析并持久化的有效图片数 |
| errorCount | 解析错误总数 |
| errors | `[{sourcePath,code,message}]`；批次级错误的 sourcePath 为空字符串 |
| errorCode | 批次失败原因；非失败通常为 null |

errors 最多返回 LABELING_MAX_ERRORS 条，errorCount 保留总数。部分图片有效时状态 READY，有效图片可浏览，其他文件仍须展示错误。没有有效图片时 FAILED/NO_VALID_SAMPLES。路径、链接、重复路径、展开限制或归档完整性问题会使整个批次 FAILED，不公开半批结果。

POST 创建、complete、retry 均必须带 `Idempotency-Key`。同调用者/方法/路由/键、同内容返回原 data 快照，requestId 每次请求重新生成。例如 complete 的同键响应仍可能为 PROCESSING，此时应通过 GET 查询最新状态。complete/retry 不接受请求体，包括 `{}`。

## 分片

`PUT /imports/{batchId}/chunks?offset=<receivedBytes>`。

- Content-Type：application/octet-stream；请求体为当前分片原始字节。
- X-Chunk-SHA256：当前分片的 64 位十六进制摘要。
- 非末片长度必须等于 chunkSizeBytes，末片等于剩余字节数。
- 成功 `data={receivedBytes:<服务器当前进度>}`。
- 同一已记录 offset、同长度、同摘要重传返回当前总进度，不再次追加。
- 同 offset 不同内容：409 UPLOAD_CONFLICT；未知间隙/边界：409 OFFSET_MISMATCH。
- 分片摘要不符：422 CHECKSUM_MISMATCH；长度不符：422 CHUNK_SIZE_MISMATCH；单片超尺寸：413 UPLOAD_TOO_LARGE。
- 完成受理后，即使重传旧分片也返回 409 UPLOAD_CLOSED。

数据库批次锁串行化并发写入。文件 fsync 后提交进度；若进程在数据库提交前退出，下次追加会截掉超出数据库进度的磁盘尾部。如果文件短于数据库进度，返回 STORAGE_UNAVAILABLE，需检查存储或重新建立批次，不会静默补零。

## 完成、重试和恢复

complete 校验数据库进度、实际文件大小和整包摘要，失败 422 UPLOAD_INCOMPLETE/CHECKSUM_MISMATCH。错误原包不能通过重传旧分片覆盖；修正文件后新建批次。

成功后将 PROCESSING 写入数据库，由定时 worker 解析。服务启动后会扫描已有 PROCESSING 批次；解析和样本入库/READY 更新在同一事务中完成。并行实例通过数据库锁避免同批次重复处理，需要共享同一持久化文件目录。

retry 仅允许 FAILED 且 originalComplete=true 的批次，且会复核原包；不符合返回 409 IMPORT_NOT_RETRYABLE。同批次相同规范化源路径的样本 ID 和存储文件名保持稳定，数据库唯一约束防重复。不支持删除/替换已接收分片。

## 样本页与原图

`GET /imports/{batchId}/samples?limit=20&cursor=...`：仅 READY 可浏览，其他状态 409 IMPORT_NOT_READY。limit 为 1..100，默认20；响应 `data={items:[...],nextCursor:null或字符串}`。游标与批次绑定，按 UUID 稳定排序。

每个 item 字段：

| 字段 | 含义 |
|---|---|
| sampleId、projectId、batchId | 字符串 ID |
| sourcePath | ZIP 内相对路径，Unicode NFC 规范化 |
| mediaType | image/png 或 image/jpeg |
| contentSha256 | 原始图片字节 SHA-256 |
| metadata | `{width,height,sizeBytes}` |
| fileUrl | `/api/labeling/v1/projects/{projectId}/samples/{sampleId}/file`，仍需授权 |

原图接口直接返回二进制与正确 Content-Type，附 `Cache-Control: private, no-store` 和 `X-Content-Type-Options: nosniff`。图片不转码，下载字节与原数据一致。不返回 storageKey、磁盘路径或长效凭据。

每个接口重新验证登录、ENABLED 与 owner。其他项目/批次/样本统一 404，训练/标注管理员均无额外原图读取特权。owner 的 DRAFT/OPEN 可上传和预览；PAUSED 拒绝写入和原图读取；ARCHIVED 只读已有图片。样本元数据/批次状态查询可用于历史查看。这不改变领取者必须有效领取且项目 OPEN 的后续授权规则。

## 格式和容量配置

| 环境变量 | 默认值 | 含义 |
|---|---|---|
| LABELING_STORAGE_ROOT | ./data/labeling | 独立持久化目录，相对路径以服务工作目录为基准 |
| LABELING_CHUNK_SIZE_BYTES | 4194304 | 单片4 MiB，新批次冻结 |
| LABELING_MAX_ARCHIVE_BYTES | 2147483648 | 压缩包2 GiB |
| LABELING_MAX_EXPANDED_BYTES | 8589934592 | 全部展开内容8 GiB，含非图片 |
| LABELING_MAX_FILE_BYTES | 67108864 | 单个展开文件64 MiB |
| LABELING_MAX_ENTRIES | 100000 | 总条目数量，含目录和非图片 |
| LABELING_MAX_PIXELS | 40000000 | 单张图片最大像素数，解码前检查 |
| LABELING_MAX_ERRORS | 1000 | 状态接口最多展示的错误数 |
| LABELING_ZIP_ENCODING | UTF-8 | 无 UTF-8 标记的文件名回退编码，可按真实样本改为 GBK |
| LABELING_IMPORT_WORKER_ENABLED | true | 是否在本实例处理持久化导入队列 |
| LABELING_IMPORT_POLL_DELAY_MS | 1000 | 两次队列扫描间隔 |

支持单卷 ZIP/ZIP64；不支持加密、分卷、RAR、7z、SVG、TIFF 等格式。JPEG 扩展名允许 jpg/jpeg，PNG 为 png；扩展名须与真实解码格式一致。其他扩展名、损坏图片与超像素图片返回文件级错误。

这些是可调的部署初始值，并非已验证的容量或性能承诺。大数据量应根据真实样本、内存和磁盘重新配置和测试。本轮 worker 有界串行处理；长批次解析会保持数据库事务和项目共享锁，应测量实际耗时后再安排生产部署。

ZIP 先以有界读取检查中央目录数量和原始路径，再使用 Commons Compress 读取中央目录属性并拒绝符号链接；实际解压计数、长度、CRC 和图片解码另行校验。实现依据：[Apache ZIP 指南](https://commons.apache.org/proper/commons-compress/zip.html)、[Java ImageReader](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/imageio/ImageReader.html)。
