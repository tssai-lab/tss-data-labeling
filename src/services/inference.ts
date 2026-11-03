import { request } from '@umijs/max';

/**
 * 推理服务。
 *
 * 对接后端 `/api/inference/**`：推理脚本版本、推理任务 CRUD / 停止 / 重试、任务结果。
 * 运行日志与结果文件本体不在本文件下载，经任务上的 `logPath` / `outputPath`
 * 转为 objectName 后走 `files.ts` 的 `/files/download`。
 */

/** 推理输入方式：单文件对象 或 数据集版本 */
export type InferenceInputMode = 'SINGLE_OBJECT' | 'DATASET_VERSION';

/** 推理任务生命周期状态 */
export type InferenceTaskStatus =
  | 'pending'
  | 'queued'
  | 'scheduled'
  | 'running'
  | 'success'
  | 'failed'
  | 'stopped';

/** 推理脚本某一版本的元数据 */
export type InferenceScriptVersion = {
  id: string;
  assetId: string;
  scriptName: string;
  version: string;
  fileName?: string;
  storagePath?: string;
  sizeBytes?: number;
  runtime: 'PYTHON3' | string;
  /** 包内入口文件，如 main.py */
  entryFile: string;
  /** 创建任务时参数表单的 JSON Schema */
  paramsSchema?: Record<string, unknown>;
  status?: string;
  ownerUserId?: number;
  createdAt?: string;
};

/** 上传脚本 ZIP 成功后的返回摘要 */
export type InferenceScriptUploadResult = {
  scriptAssetId: string;
  scriptVersionId: string;
  scriptName: string;
  version: string;
  fileName?: string;
  storagePath?: string;
  sizeBytes?: number;
  runtime: string;
  entryFile: string;
  paramsSchema?: Record<string, unknown>;
  status?: string;
};

/** 推理任务（列表 / 详情完整结构） */
export type InferenceTask = {
  id: string;
  name: string;
  modelVersionId: string;
  scriptVersionId: string;
  inputMode: InferenceInputMode | string;
  datasetVersionId?: string | null;
  /** SINGLE_OBJECT 时的输入对象路径 */
  inputObjectName?: string | null;
  /** 新任务会固化资源档位；历史任务可能为空并沿用旧全局配置。 */
  resourceProfileId?: string | null;
  hardwareTargetId?: string | null;
  gpuNodeName?: string | null;
  gpuHostIndex?: string | null;
  gpuUuid?: string | null;
  gpuModel?: string | null;
  gpuTotalMemoryMiB?: number | null;
  gpuMemoryLimitMiB?: number | null;
  params?: Record<string, unknown>;
  status: InferenceTaskStatus | string;
  progress?: number;
  currentAttempt?: number;
  retryCount?: number;
  maxRetries?: number;
  retryable?: boolean;
  lastRetryAt?: string | null;
  /** 结构化结果摘要，供结果可视化使用 */
  result?: Record<string, unknown>;
  /**
   * 运行日志 MinIO 路径。有值后可下载或在结果抽屉「运行日志」中整文件预览；
   * 本期无 `/inference/tasks/{id}/logs` 增量接口。
   */
  logPath?: string | null;
  /** 输出目录 MinIO 路径；结果 JSON / 可视化媒体多相对此路径 */
  outputPath?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  remark?: string | null;
  ownerUserId?: number;
  createdAt?: string;
  updatedAt?: string;
};

/** 任务结果摘要（GET .../result 返回字段子集） */
export type InferenceTaskResult = Pick<
  InferenceTask,
  | 'id'
  | 'status'
  | 'progress'
  | 'currentAttempt'
  | 'retryCount'
  | 'maxRetries'
  | 'retryable'
  | 'lastRetryAt'
  | 'result'
  | 'logPath'
  | 'outputPath'
  | 'errorMessage'
>;

/** 创建推理任务请求体 */
export type CreateInferenceTaskBody = {
  name: string;
  modelVersionId: string;
  scriptVersionId: string;
  inputMode: InferenceInputMode;
  datasetVersionId?: string;
  inputObjectName?: string;
  resourceProfileId?: string;
  hardwareTargetId?: string;
  gpuMemoryLimitMiB?: number;
  params?: Record<string, unknown>;
  remark?: string;
};

/** 后端白名单中的可用推理资源规格。 */
export type InferenceResourceProfile = {
  id: string;
  displayName: string;
  description?: string;
  deviceType: 'CPU' | 'NVIDIA_GPU' | string;
  cpuRequest: string;
  cpuLimit: string;
  memoryRequest: string;
  memoryLimit: string;
  ephemeralStorageRequest: string;
  ephemeralStorageLimit: string;
  gpuCount: number;
};

/** 后端实时检测到的一张可用于推理的物理 GPU。 */
export type InferenceHardwareOption = {
  hardwareTargetId: string;
  displayName: string;
  nodeName: string;
  hostGpuIndex: string;
  uuid: string;
  model: string;
  totalMemoryMiB: number;
  freeMemoryMiB?: number | null;
  utilizationRate?: number | null;
  temperatureCelsius?: number | null;
  observedAt: string;
  message: string;
};

/** 推理任务分页列表 */
export type InferenceTaskPage = {
  data: InferenceTask[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
};

/** 将字节数格式化为可读字符串（B / KB / MB…） */
export function formatBytes(sizeBytes?: number) {
  if (sizeBytes === undefined || sizeBytes === null || Number.isNaN(sizeBytes)) {
    return '-';
  }
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = sizeBytes / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(2)} ${units[unitIndex]}`;
}

/**
 * 将 `minio://...` 或带 bucket 前缀的路径转为 `/files/download` 可用的 objectName。
 * 下载日志、结果文件、可视化配图前都会用到。
 */
export function objectNameFromMinioPath(path?: string | null) {
  if (!path) return '';
  const clean = path.trim();
  if (!clean) return '';
  if (!clean.startsWith('minio://')) return clean.replace(/^\/+/, '');
  const withoutScheme = clean.slice('minio://'.length).replace(/^\/+/, '');
  if (withoutScheme.startsWith('users/')) return withoutScheme;
  const parts = withoutScheme.split('/');
  if (parts.length > 1 && !parts[0].includes('.')) {
    const maybeBucket = parts[0];
    if (['models', 'tss-platform', 'default'].includes(maybeBucket)) {
      return parts.slice(1).join('/');
    }
  }
  return withoutScheme;
}

/**
 * 上传推理脚本 ZIP，登记为新的脚本版本。
 * POST `/inference/scripts/upload`（multipart）。
 */
export async function uploadInferenceScript(
  body: {
    file: File;
    scriptName: string;
    version: string;
    runtime?: string;
    entryFile: string;
    paramsSchemaJson?: string;
    remark?: string;
  },
  options?: { [key: string]: unknown },
) {
  const formData = new FormData();
  formData.append('file', body.file);
  formData.append('scriptName', body.scriptName);
  formData.append('version', body.version || 'v1');
  formData.append('runtime', body.runtime || 'PYTHON3');
  formData.append('entryFile', body.entryFile);
  if (body.paramsSchemaJson?.trim()) {
    formData.append('paramsSchemaJson', body.paramsSchemaJson.trim());
  }
  if (body.remark?.trim()) {
    formData.append('remark', body.remark.trim());
  }
  return request<{
    success?: boolean;
    data: InferenceScriptUploadResult;
    errorMessage?: string;
  }>('/inference/scripts/upload', {
    method: 'POST',
    data: formData,
    ...(options || {}),
  });
}

/** 列出可用推理脚本版本。GET `/inference/scripts` */
export async function listInferenceScripts(options?: { [key: string]: unknown }) {
  return request<{ data: InferenceScriptVersion[] }>('/inference/scripts', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 按版本 ID 获取脚本详情。GET `/inference/scripts/{versionId}` */
export async function getInferenceScript(versionId: string, options?: { [key: string]: unknown }) {
  return request<{ data: InferenceScriptVersion }>(
    `/inference/scripts/${encodeURIComponent(versionId)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** 创建推理任务。POST `/inference/tasks` */
export async function createInferenceTask(
  body: CreateInferenceTaskBody,
  options?: { [key: string]: unknown },
) {
  return request<{ data: InferenceTask }>('/inference/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}

/** 获取当前后端实际启用的推理资源档位。 */
export async function listInferenceResourceProfiles(
  options?: { [key: string]: unknown },
) {
  return request<{ data: InferenceResourceProfile[] }>(
    '/inference/resource-profiles',
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** 获取可精确选择的物理 GPU；指标过期的卡不会返回。 */
export async function listInferenceHardwareOptions(
  options?: { [key: string]: unknown },
) {
  return request<{ data: InferenceHardwareOption[] }>(
    '/inference/hardware-options',
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** 分页查询推理任务。GET `/inference/tasks` */
export async function listInferenceTasks(
  params?: { page?: number; pageSize?: number; status?: string },
  options?: { [key: string]: unknown },
) {
  return request<{ data: InferenceTaskPage }>('/inference/tasks', {
    method: 'GET',
    params,
    ...(options || {}),
  });
}

/** 获取推理任务详情。GET `/inference/tasks/{id}` */
export async function getInferenceTask(id: string, options?: { [key: string]: unknown }) {
  return request<{ data: InferenceTask }>(`/inference/tasks/${encodeURIComponent(id)}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 停止运行中的推理任务。POST `/inference/tasks/{id}/stop` */
export async function stopInferenceTask(id: string, options?: { [key: string]: unknown }) {
  return request<{ data: InferenceTask }>(
    `/inference/tasks/${encodeURIComponent(id)}/stop`,
    {
      method: 'POST',
      ...(options || {}),
    },
  );
}

/** 重试失败且可重试的推理任务。POST `/inference/tasks/{id}/retry` */
export async function retryInferenceTask(id: string, options?: { [key: string]: unknown }) {
  return request<{ data: InferenceTask }>(
    `/inference/tasks/${encodeURIComponent(id)}/retry`,
    {
      method: 'POST',
      ...(options || {}),
    },
  );
}

/** 删除任务接口返回：是否已删、MinIO 清理是否入队等 */
export type DeleteInferenceTaskResult = {
  id: string;
  deleted: boolean;
  minioDeleteQueued?: boolean;
  queuedObjectCount?: number;
};

/** 删除推理任务。DELETE `/inference/tasks/{id}` */
export async function deleteInferenceTask(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DeleteInferenceTaskResult }>(
    `/inference/tasks/${encodeURIComponent(id)}`,
    {
      method: 'DELETE',
      ...(options || {}),
    },
  );
}

/** 删除脚本版本接口返回 */
export type DeleteInferenceScriptResult = {
  id: string;
  assetId: string;
  deleted: boolean;
  assetDeleted?: boolean;
  minioDeleteQueued?: boolean;
};

/** 删除推理脚本版本。DELETE `/inference/scripts/{versionId}` */
export async function deleteInferenceScript(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DeleteInferenceScriptResult }>(
    `/inference/scripts/${encodeURIComponent(versionId)}`,
    {
      method: 'DELETE',
      ...(options || {}),
    },
  );
}

/**
 * 获取推理任务结果摘要（状态、进度、result、logPath、outputPath 等）。
 * GET `/inference/tasks/{id}/result`
 * 打开结果抽屉时调用；日志正文仍需按 logPath 走文件下载。
 */
export async function getInferenceTaskResult(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: InferenceTaskResult }>(
    `/inference/tasks/${encodeURIComponent(id)}/result`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}
