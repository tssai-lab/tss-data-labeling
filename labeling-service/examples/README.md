# 给宋雷鸣的图片导入调用示例

本目录代码调用真实后端。接口前缀 `/api/labeling/v1`，开发服务默认为 `http://127.0.0.1:8082`。后端先按 `../README.md` 配置统一身份服务、专用数据库及持久化存储并启动。

## 1. 运行完整示例

需要 Node 18+；在 `labeling-service/` 目录运行。TOKEN 使用已有统一登录返回的值，PROJECT_ID 使用本人通过平台创建的项目 ID。

```powershell
$env:LABELING_API_BASE = 'http://127.0.0.1:8082/api/labeling/v1'
# TOKEN 使用现有登录结果通过环境变量传入，不写进代码或提交。
node examples/image-import.mjs <PROJECT_ID> <图片ZIP绝对路径> <下载验证目录>
```

脚本会流式计算整包 SHA-256，创建批次，按服务器分片尺寸上传，调用 complete，轮询解析状态，拉取全部分页样本，下载图片并核对每张 SHA-256。下载目录还会产生 `manifest.json`，包含项目/批次/样本 ID 和来源路径，供汤立娜核对映射。目录中不保存 Token。

标准输出为最终批次与样本 JSON；标准错误输出上传进度和 batchId。网络错误/服务端 5xx 最多重试三次，POST 重试保持同一幂等键，分片重试保持 offset、内容和摘要。

上传中断后，在确认使用同一原始 ZIP 的情况下设置 `BATCH_ID` 重新运行，脚本按服务器进度继续。也可以保存首次输出的 creationKey 到 `IMPORT_IDEMPOTENCY_KEY`，用于重放创建操作。要重试 FAILED 解析批次，设置 `BATCH_ID` 和 `RETRY_FAILED=true`；完整文件/摘要检查未通过会拒绝重试。不要把同一幂等键用于另一份 ZIP。

若 POST 创建请求的响应完全丢失，必须保存/复用创建键才能避免用户手工重新运行时创建第二个批次。脚本内的网络自动重试始终复用键；建议联调前自行设置 `IMPORT_IDEMPOTENCY_KEY`。

## 2. 页面接入顺序

1. 创建项目并取得 projectId。
2. 创建导入：`POST /projects/{projectId}/imports`，请求 `{filename,sizeBytes,sha256}`。
3. 读取返回的 chunkSizeBytes，每片以二进制 PUT 上传；下一片 offset 使用 receivedBytes。
4. 全部上传后 `POST /imports/{batchId}/complete`，不发 JSON 请求体，带 Idempotency-Key。
5. 轮询 GET 批次；202 表示受理，不代表解析完成。READY 时显示数量和 errors，FAILED 时显示 errorCode/errors。
6. `GET /imports/{batchId}/samples?limit=20`，后续带 nextCursor。
7. 使用每个 sample.fileUrl 通过授权接口获取图片。

浏览器 SHA-256 计算必须包括整个 ZIP 和每个分片。大文件应使用前端选定的增量哈希方案/Worker；Node 示例不要求浏览器把整包读入内存。

## 3. 前端图片预览

`image-preview.js` 导出 `loadImagePreview(sample, token, signal)`。它携带 Authorization 读取二进制并返回 Blob URL。不要将要求 Bearer 的 fileUrl 直接当成无认证的 `<img src>`。

```jsx
useEffect(() => {
  const controller = new AbortController();
  let objectUrl;
  setPreviewUrl(null);
  loadImagePreview(sample, token, controller.signal).then(url => {
    if (controller.signal.aborted) URL.revokeObjectURL(url);
    else { objectUrl = url; setPreviewUrl(url); }
  }).catch(error => {
    if (!controller.signal.aborted) setError(error.message);
  });
  return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
}, [sample.fileUrl, token]);
```

开发代理中将更具体的 `/api/labeling/v1` 路由转发到标注服务 8082，保留 Authorization；原训练接口仍走原后端。部署后也使用同源反向代理。不要为了联调把文件目录设成公开静态目录，或把 Token 放到 URL 查询参数。

## 4. 状态与异常

- 部分图片有效：READY，同时展示 errorCount/errors，不能把所有源文件都标为成功。
- errors 最多返回配置数量；errorCount 大于 errors.length 表示错误展示被截断。
- ZIP 中的非 JPG/JPEG/PNG 文件也有逐文件失败条目。无有效图片为 FAILED/NO_VALID_SAMPLES。
- 危险路径、链接、重复路径或展开超限：整个批次 FAILED，不公开部分结果。
- 文件接口返回二进制，失败返回标准 JSON 错误；可按 requestId 查日志。
- 项目 owner 可在 DRAFT/OPEN 上传和预览。PAUSED 拒绝原图和写入，ARCHIVED 仅可读已有图片。
- 未登录 401，他人资源 404，账号禁用 403；没有全局管理员文件读取例外。

分类保存、领取、Label Studio 同步和提交接口仍待各自负责人实现；本示例不会生成一条假的“分类保存成功”结果。
