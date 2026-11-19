import { hashFile, uploadChunks } from './labelingUpload.mjs';
export const archivePolicy = Object.freeze({ maxArchiveBytes: 2147483648 });

function createIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  if (globalThis.crypto?.getRandomValues) {
    return Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (byte) =>
      byte.toString(16).padStart(2, '0'),
    ).join('');
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export async function validateArchive(file, policy = archivePolicy) {
  if (
    !file ||
    !/^.+\.zip$/i.test(file.name) ||
    !Number.isSafeInteger(file.size) ||
    file.size <= 0
  )
    throw Error('请选择非空 ZIP 文件');
  if (file.name.length > 255 || /[\\/]/.test(file.name))
    throw Error('ZIP 文件名不能超过 255 个字符或包含目录');
  if (file.size > policy.maxArchiveBytes)
    throw Error(
      `压缩包超过当前允许大小（${Math.round(policy.maxArchiveBytes / 1024 / 1024)} MiB）`,
    );
  const head = new Uint8Array(await file.slice(0, 4).arrayBuffer());
  if (
    head[0] !== 80 ||
    head[1] !== 75 ||
    ![
      [3, 4],
      [5, 6],
      [7, 8],
    ].some(([a, b]) => head[2] === a && head[3] === b)
  )
    throw Error('文件头不是 ZIP 格式，请勿仅修改文件后缀');
}
export function imageImportAdapter(projectId, request) {
  if (!projectId) throw Error('缺少项目标识');
  const call = (path, init, signal) => request(path, { ...init, signal });
  const post = (path, body, key, signal) =>
    call(
      path,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
        ...(body ? { body: JSON.stringify(body) } : {}),
      },
      signal,
    );
  return {
    create: (file, key, signal) =>
      post(
        `/projects/${encodeURIComponent(projectId)}/imports`,
        file,
        key,
        signal,
      ),
    get: (id, signal) => call(`/imports/${encodeURIComponent(id)}`, {}, signal),
    upload: (file, batch, signal, progress) =>
      uploadChunks(
        file,
        batch,
        (path, init) => call(path, init, signal),
        signal,
        progress,
      ),
    complete: (id, signal) =>
      post(
        `/imports/${encodeURIComponent(id)}/complete`,
        null,
        `complete-${id}`,
        signal,
      ),
    retry: (id, signal) =>
      post(
        `/imports/${encodeURIComponent(id)}/retry`,
        null,
        `retry-${id}-${createIdempotencyKey()}`,
        signal,
      ),
  };
}
export class ArchiveImportSession {
  constructor({
    scope,
    adapter,
    storage,
    policy = archivePolicy,
    onChange = () => {},
  }) {
    this.scope = scope;
    this.adapter = adapter;
    this.storage = storage;
    this.policy = policy;
    this.onChange = onChange;
    this.state = {
      stage: 'IDLE',
      hashProgress: 0,
      batch: null,
      error: '',
      busy: false,
    };
    try {
      const value = JSON.parse(storage?.getItem(this.key) || 'null');
      if (value) {
        if (
          value.version !== 1 ||
          value.scope !== scope ||
          !value.file?.sha256 ||
          !value.key
        )
          throw Error('续传记录版本不兼容');
        this.receipt = value;
        this.state.stage = 'PAUSED';
      }
    } catch {
      this.state.error = '无法读取续传记录，请重新创建批次。';
    }
  }
  get key() {
    return `platform-archive-import-v1:${this.scope}`;
  }
  emit(patch) {
    this.state = { ...this.state, ...patch };
    if (!this.disposed) this.onChange({ ...this.state });
  }
  persist() {
    try {
      this.storage?.setItem(this.key, JSON.stringify(this.receipt));
    } catch {
      throw Error('无法保存续传记录，请检查浏览器存储后重试。');
    }
  }
  accept(batch) {
    if (
      !batch ||
      !['UPLOADING', 'PROCESSING', 'READY', 'FAILED'].includes(batch.status) ||
      !batch.batchId
    )
      throw Error('导入服务返回的批次状态无效');
    if (this.receipt?.batchId && this.receipt.batchId !== batch.batchId)
      throw Error('导入服务返回了其他批次');
    if (this.receipt && batch.sizeBytes !== this.receipt.file.sizeBytes)
      throw Error('批次文件大小与所选文件不一致');
    if (this.receipt) {
      this.receipt.batchId = batch.batchId;
      this.persist();
    }
    const stage = batch.status === 'UPLOADING' ? 'PAUSED' : batch.status;
    this.emit({
      batch,
      stage,
      error: batch.status === 'FAILED' ? '解析失败，请查看错误明细。' : '',
    });
  }
  async run(file) {
    if (this.running || this.disposed) return;
    if (!this.adapter) {
      this.emit({ error: '当前项目尚未连接数据导入服务，文件没有上传。' });
      return;
    }
    this.running = true;
    this.emit({ busy: true });
    this.abort = new AbortController();
    const signal = this.abort.signal;
    try {
      await validateArchive(file, this.policy);
      signal.throwIfAborted();
      this.emit({ stage: 'HASHING', error: '', hashProgress: 0 });
      const sha256 = await hashFile(file, signal, (progress) =>
        this.emit({ hashProgress: Math.round(progress * 100) }),
      );
      signal.throwIfAborted();
      const fingerprint = { filename: file.name, sizeBytes: file.size, sha256 };
      if (
        this.receipt &&
        Object.keys(fingerprint).some(
          (key) => fingerprint[key] !== this.receipt.file[key],
        )
      )
        throw Error('所选文件与续传记录不一致，请选择原 ZIP 或明确新建批次。');
      if (!this.receipt) {
        this.receipt = {
          version: 1,
          scope: this.scope,
          key: createIdempotencyKey(),
          file: fingerprint,
        };
        this.persist();
      }
      let batch = this.receipt.batchId
        ? await this.adapter.get(this.receipt.batchId, signal)
        : await this.adapter.create(fingerprint, this.receipt.key, signal);
      signal.throwIfAborted();
      this.accept(batch);
      if (batch.status === 'UPLOADING') {
        this.emit({ stage: 'UPLOADING' });
        await this.adapter.upload(file, batch, signal, (progress) =>
          this.emit({
            stage: 'UPLOADING',
            uploadProgress: Math.round(progress * 100),
            batch: {
              ...batch,
              receivedBytes: Math.round(progress * file.size),
            },
          }),
        );
        signal.throwIfAborted();
        this.emit({ stage: 'VERIFYING' });
        await this.adapter.complete(batch.batchId, signal);
        signal.throwIfAborted();
        batch = await this.adapter.get(batch.batchId, signal);
        signal.throwIfAborted();
        this.accept(batch);
      }
    } catch (e) {
      this.emit({
        stage: signal.aborted ? 'PAUSED' : 'ERROR',
        error: signal.aborted
          ? '本次操作已中止。重新选择原 ZIP 并查询批次状态后可继续；已提交的解析不受影响。'
          : e.message || '上传失败，请重试。',
      });
    } finally {
      this.running = false;
      this.emit({ busy: false });
    }
  }
  pause() {
    this.abort?.abort();
  }
  async refresh() {
    if (
      !this.adapter ||
      !this.receipt?.batchId ||
      this.running ||
      this.disposed
    )
      return;
    this.running = true;
    this.emit({ busy: true });
    this.abort = new AbortController();
    try {
      const batch = await this.adapter.get(
        this.receipt.batchId,
        this.abort.signal,
      );
      this.abort.signal.throwIfAborted();
      this.accept(batch);
    } catch (e) {
      if (!this.abort.signal.aborted)
        this.emit({ error: e.message || '读取状态失败，请重试。' });
    } finally {
      this.running = false;
      this.emit({ busy: false });
    }
  }
  async retry() {
    if (
      !this.adapter ||
      this.running ||
      this.disposed ||
      this.state.batch?.status !== 'FAILED' ||
      !this.state.batch.originalComplete
    )
      return;
    this.running = true;
    this.emit({ busy: true });
    this.abort = new AbortController();
    const signal = this.abort.signal;
    try {
      await this.adapter.retry(this.receipt.batchId, signal);
      signal.throwIfAborted();
      const batch = await this.adapter.get(this.receipt.batchId, signal);
      signal.throwIfAborted();
      this.accept(batch);
    } catch (e) {
      if (!signal.aborted) this.emit({ error: e.message || '重新解析失败。' });
    } finally {
      this.running = false;
      this.emit({ busy: false });
    }
  }
  reset() {
    if (this.running) return;
    this.storage?.removeItem(this.key);
    this.receipt = null;
    this.emit({
      stage: 'IDLE',
      batch: null,
      error: '',
      hashProgress: 0,
      uploadProgress: 0,
    });
  }
  dispose() {
    this.disposed = true;
    this.pause();
  }
}
