// Node 18+. Uses the real platform API; no dependencies or embedded credentials.
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { open, stat, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

export async function runImport({ apiBase, token, projectId, zipPath, downloadDir, batchId,
  creationKey = randomUUID(), timeoutMs = 600_000, retryFailed = false, onProgress = () => {} }) {
  if (!apiBase || !token || !projectId || !zipPath) throw new Error('需要 apiBase、token、projectId 和 zipPath');
  const base = new URL(apiBase.replace(/\/$/, '') + '/');
  const authorization = token.startsWith('Bearer ') ? token : `Bearer ${token}`;
  const request = async (method, route, body, headers = {}) => {
    for (let attempt = 0; attempt < 3; attempt++) {
      let response;
      try {
        response = await fetch(new URL(route, base), {
          method, headers: { Authorization: authorization, ...headers }, body, signal: AbortSignal.timeout(30_000)
        });
      } catch (error) {
        if (attempt === 2) throw error;
        await delay(250 * (attempt + 1)); continue;
      }
      if (response.status >= 500 && attempt < 2) {
        await response.arrayBuffer(); await delay(250 * (attempt + 1)); continue;
      }
      const payload = await response.json();
      if (!response.ok) throw new Error(`${response.status} ${payload.error?.code}: ${payload.error?.message} (requestId=${payload.requestId})`);
      return payload.data;
    }
  };
  const post = (route, key, body) => request('POST', route,
    body === undefined ? undefined : JSON.stringify(body),
    { 'Idempotency-Key': key, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) });
  const info = await stat(zipPath);
  const hash = createHash('sha256');
  for await (const bytes of createReadStream(zipPath)) hash.update(bytes);
  const checksum = hash.digest('hex');
  let batch = batchId ? await request('GET', `imports/${encodeURIComponent(batchId)}`)
    : await post(`projects/${encodeURIComponent(projectId)}/imports`, creationKey,
      { filename: path.basename(zipPath), sizeBytes: info.size, sha256: checksum });
  // POST replays an immutable creation snapshot; only GET describes current progress.
  batch = await request('GET', `imports/${encodeURIComponent(batch.batchId)}`);
  if (batch.projectId !== projectId || batch.sizeBytes !== info.size) throw new Error('批次与项目或本地文件大小不匹配');
  batchId = batch.batchId;
  onProgress({ batchId, creationKey, status: batch.status, receivedBytes: batch.receivedBytes });
  const completeKey = randomUUID();
  if (batch.status === 'UPLOADING') {
    const chunkSize = batch.chunkSizeBytes;
    if (!Number.isSafeInteger(chunkSize) || chunkSize < 1 || chunkSize > 64 * 1024 * 1024) {
      throw new Error('服务器分片尺寸超出本示例支持范围（1 字节到 64 MiB）');
    }
    const file = await open(zipPath, 'r');
    try {
      let offset = batch.receivedBytes;
      while (offset < info.size) {
        const bytes = Buffer.alloc(Math.min(chunkSize, info.size - offset));
        let filled = 0;
        while (filled < bytes.length) {
          const read = await file.read(bytes, filled, bytes.length - filled, offset + filled);
          if (!read.bytesRead) throw new Error('本地文件在上传期间被修改或截断');
          filled += read.bytesRead;
        }
        const result = await request('PUT', `imports/${batchId}/chunks?offset=${offset}`, bytes,
          { 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': sha256(bytes) });
        if (result.receivedBytes <= offset || result.receivedBytes > info.size) throw new Error('服务器返回了无效的上传进度');
        offset = result.receivedBytes;
        onProgress({ batchId, status: 'UPLOADING', receivedBytes: offset, sizeBytes: info.size });
      }
    } finally { await file.close(); }
    batch = await post(`imports/${batchId}/complete`, completeKey);
  } else if (batch.status === 'FAILED' && retryFailed) {
    batch = await post(`imports/${batchId}/retry`, randomUUID());
  }
  const deadline = Date.now() + timeoutMs;
  while (batch.status === 'PROCESSING') {
    if (Date.now() > deadline) throw new Error(`等待解析超时；可用 BATCH_ID=${batchId} 继续查询`);
    await delay(300);
    batch = await request('GET', `imports/${batchId}`);
  }
  if (batch.status !== 'READY') throw new Error(`导入未成功：${JSON.stringify(batch)}`);
  const samples = [];
  let cursor;
  do {
    const query = new URLSearchParams({ limit: '100' });
    if (cursor) query.set('cursor', cursor);
    const page = await request('GET', `imports/${batchId}/samples?${query}`);
    samples.push(...page.items); cursor = page.nextCursor;
  } while (cursor);
  if (downloadDir) {
    await mkdir(downloadDir, { recursive: true });
    for (const sample of samples) {
      const url = new URL(sample.fileUrl, base);
      if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) throw new Error('图片 URL 不属于当前标注 API');
      const response = await fetch(url, { headers: { Authorization: authorization }, signal: AbortSignal.timeout(30_000) });
      if (!response.ok) throw new Error(`图片下载失败 ${response.status}`);
      const bytes = Buffer.from(await response.arrayBuffer());
      if (sha256(bytes) !== sample.contentSha256) throw new Error(`图片摘要不一致：${sample.sampleId}`);
      const extension = sample.mediaType === 'image/png' ? '.png' : '.jpg';
      // Generated sample IDs, never archive source paths, are used as local filenames.
      if (!/^[a-f0-9-]{36}$/i.test(sample.sampleId)) throw new Error('样本 ID 格式无效');
      await writeFile(path.join(downloadDir, sample.sampleId + extension), bytes);
    }
    await writeFile(path.join(downloadDir, 'manifest.json'), JSON.stringify({ batch, samples }, null, 2));
  }
  return { batch, samples };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [projectId, zipPath, downloadDir] = process.argv.slice(2);
  runImport({ apiBase: process.env.LABELING_API_BASE ?? 'http://127.0.0.1:8082/api/labeling/v1',
    token: process.env.TOKEN, projectId, zipPath, downloadDir, batchId: process.env.BATCH_ID,
    creationKey: process.env.IMPORT_IDEMPOTENCY_KEY || randomUUID(), retryFailed: process.env.RETRY_FAILED === 'true',
    onProgress: value => console.error(JSON.stringify(value))
  }).then(result => console.log(JSON.stringify(result, null, 2)))
    .catch(error => { console.error(error.message); process.exitCode = 1; });
}
