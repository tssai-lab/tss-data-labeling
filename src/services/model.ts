import { request } from '@umijs/max';
import { isLegacyEndpointUnavailable } from '@/utils/apiCompatibility.mjs';
import { downloadAuthFile } from '@/utils/authFileDownload';
import { modelFiles, modelPreview, modelReadData } from './modelReadResponse';
import { collectPaginatedCandidates } from './paginatedCandidates.mjs';

export type ModelTaskType = 'CV' | 'NLP' | 'POINT_CLOUD' | 'ROBOT' | 'OTHER';

type BackendModelItem = {
  id: string;
  assetId?: string;
  name: string;
  version: string;
  type: ModelTaskType;
  remark?: string;
  storagePath?: string;
  fileName?: string;
  sizeBytes?: number;
  createdAt?: string;
  updatedAt?: string;
  artifactSha256?: string;
  artifactSpecId?: string;
  commitInfo?: string;
  hyperParams?: Record<string, unknown>;
  isCurrent?: boolean;
  status?: string;
};

/** §4 模型资产 */
export type ModelAsset = {
  id: string;
  name: string;
  type?: ModelTaskType;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
  currentVersionId?: string | null;
};

/** §5 模型版本 */
export type ModelVersion = {
  id: string;
  assetId: string;
  version: string;
  fileName?: string;
  storagePath?: string;
  sizeBytes?: number;
  createdAt?: string;
  updatedAt?: string;
  remark?: string;
  artifactSha256?: string;
  artifactSpecId?: string;
  commitInfo?: string;
  hyperParams?: Record<string, unknown>;
  isCurrent?: boolean;
  status?: string;
};

export type ModelDeleteResult = {
  id: string;
  assetId?: string;
  deleted?: boolean;
  deletedVersions?: number;
  deletedObjects?: number;
  minioDeleteQueued?: boolean;
};

function formatBytes(sizeBytes?: number) {
  if (
    sizeBytes === undefined ||
    sizeBytes === null ||
    Number.isNaN(sizeBytes)
  ) {
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

function toUnixTimestamp(value?: string) {
  if (!value) {
    return undefined;
  }
  const timestamp = Date.parse(value);
  if (Number.isNaN(timestamp)) {
    return undefined;
  }
  return String(Math.floor(timestamp / 1000));
}

function mapModelVersion(
  version: ModelVersion,
  asset?: ModelAsset,
): API.ModelVersionDetail {
  const currentId = asset?.currentVersionId ?? undefined;
  const isCurrent =
    version.isCurrent === true || (!!currentId && version.id === currentId);
  return {
    ...version,
    name: asset?.name,
    type: asset?.type,
    size: formatBytes(version.sizeBytes),
    isCurrent,
  };
}

/** 从版本记录解析模型版本 ID */
export function resolveModelVersionId(
  version?: Partial<ModelVersion> | null,
  assetId?: string,
): string | undefined {
  if (!version) return undefined;
  const extra = version as ModelVersion & {
    modelVersionId?: string;
    versionId?: string;
  };
  const candidates = [version.id, extra.modelVersionId, extra.versionId].filter(
    (v): v is string => typeof v === 'string' && v.length > 0,
  );
  for (const candidate of candidates) {
    if (assetId && candidate === assetId) continue;
    return candidate;
  }
  return undefined;
}

function normalizeModelVersionList(raw: unknown): ModelVersion[] {
  if (Array.isArray(raw)) return raw as ModelVersion[];
  if (raw && typeof raw === 'object') {
    const obj = raw as { data?: unknown; list?: unknown; records?: unknown };
    if (Array.isArray(obj.data)) return obj.data as ModelVersion[];
    if (Array.isArray(obj.list)) return obj.list as ModelVersion[];
    if (Array.isArray(obj.records)) return obj.records as ModelVersion[];
  }
  return [];
}

function mapModelItem(item?: BackendModelItem): API.ModelItem | undefined {
  if (!item) {
    return undefined;
  }
  return {
    id: item.id,
    assetId: item.assetId,
    name: item.name,
    version: item.version,
    type: item.type,
    remark: item.remark,
    storagePath: item.storagePath,
    fileName: item.fileName,
    sizeBytes: item.sizeBytes,
    size: formatBytes(item.sizeBytes),
    uploadTime: item.createdAt,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
    artifactSha256: item.artifactSha256,
    artifactSpecId: item.artifactSpecId,
    commitInfo: item.commitInfo,
    hyperParams: item.hyperParams,
    isCurrent: item.isCurrent,
    status: item.status,
  };
}

/** 解析 V2 model-uploads 响应为统一进度结构 */
function normalizeV2ModelUploadDto(
  raw: unknown,
): API.ModelUploadInitResult | null {
  const obj = (raw && typeof raw === 'object' ? raw : {}) as Record<
    string,
    unknown
  >;
  const data = (
    obj.data && typeof obj.data === 'object' ? obj.data : obj
  ) as Record<string, unknown>;
  if (!data.uploadId) return null;
  return {
    uploadId: String(data.uploadId),
    status: data.status ? String(data.status) : undefined,
    fileName: data.fileName ? String(data.fileName) : undefined,
    fileSize: data.fileSize != null ? Number(data.fileSize) : undefined,
    chunkSize: data.chunkSize != null ? Number(data.chunkSize) : undefined,
    totalChunks:
      data.totalChunks != null ? Number(data.totalChunks) : undefined,
    uploadedChunks:
      data.uploadedChunks != null ? Number(data.uploadedChunks) : undefined,
    uploadedBytes:
      data.uploadedBytes != null ? Number(data.uploadedBytes) : undefined,
    uploadedPartIndexes: Array.isArray(data.uploadedPartIndexes)
      ? (data.uploadedPartIndexes as number[])
      : [],
    artifactSpecId: data.artifactSpecId
      ? String(data.artifactSpecId)
      : undefined,
  };
}

/** 解析 V2 complete 响应为 BackendModelItem */
function normalizeV2ModelUploadComplete(raw: unknown): BackendModelItem | null {
  const obj = (raw && typeof raw === 'object' ? raw : {}) as Record<
    string,
    unknown
  >;
  const data = (
    obj.data && typeof obj.data === 'object' ? obj.data : obj
  ) as Record<string, unknown>;
  const modelVersionId = data.modelId || data.modelVersionId || data.id;
  if (!modelVersionId) return null;
  return {
    id: String(modelVersionId),
    assetId: data.assetId ? String(data.assetId) : undefined,
    name: String(data.modelName || data.name || ''),
    version: String(data.modelVersion || data.version || ''),
    type: (data.taskType || data.type || 'CV') as ModelTaskType,
    remark: data.remark ? String(data.remark) : undefined,
    fileName: data.fileName ? String(data.fileName) : undefined,
    sizeBytes: data.fileSize != null ? Number(data.fileSize) : undefined,
    artifactSha256: data.artifactSha256
      ? String(data.artifactSha256)
      : undefined,
    artifactSpecId: data.artifactSpecId
      ? String(data.artifactSpecId)
      : undefined,
    commitInfo: data.commitInfo ? String(data.commitInfo) : undefined,
    hyperParams: data.hyperParams as Record<string, unknown> | undefined,
    isCurrent:
      data.isCurrent === true || data.isCurrent === 'true' ? true : undefined,
    status: data.status ? String(data.status) : undefined,
  };
}

/**
 * 初始化或恢复模型分片上传。
 * 优先 V2（业务字段在 init 写入会话），仅接口不支持时兼容 Legacy。
 */
export async function modelUploadInit(
  params: API.ModelUploadInitParams,
  options?: { [key: string]: unknown },
) {
  const v2Body: Record<string, unknown> = {
    fileName: params.fileName,
    fileSize: params.fileSize,
    fileFingerprint: params.fileFingerprint,
    commitInfo: params.commitInfo,
    hyperParams: params.hyperParams ?? {},
  };
  if (params.modelName) v2Body.modelName = params.modelName;
  if (params.modelVersion) v2Body.modelVersion = params.modelVersion;
  if (params.taskType) v2Body.taskType = params.taskType;
  if (params.remark) v2Body.remark = params.remark;
  if (params.targetAssetId) v2Body.targetAssetId = params.targetAssetId;

  try {
    const raw = await request<unknown>('/v2/model-uploads/init', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: v2Body,
      skipErrorHandler: true,
      ...(options || {}),
    });
    const normalized = normalizeV2ModelUploadDto(raw);
    if (normalized) {
      return { data: normalized };
    }
    throw new Error('模型上传回执缺少 uploadId，请查询上传状态后再操作');
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
  }

  return request<{ data: API.ModelUploadInitResult }>('/model/upload/init', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: {
      fileName: params.fileName,
      fileSize: params.fileSize,
      fileFingerprint: params.fileFingerprint,
      commitInfo: params.commitInfo,
      ...(params.hyperParams ? { hyperParams: params.hyperParams } : {}),
    },
    ...(options || {}),
  });
}

/** 上传模型分片；仅 V2 接口不支持时兼容旧接口，不重放结果不明的请求。 */
export async function modelUploadChunk(
  uploadId: string,
  partIndex: number,
  chunk: Blob,
  options?: { [key: string]: unknown },
) {
  try {
    const formData = new FormData();
    formData.append('partIndex', String(partIndex));
    formData.append('file', chunk);
    const raw = await request<unknown>(
      `/v2/model-uploads/${encodeURIComponent(uploadId)}/chunks`,
      {
        method: 'POST',
        data: formData,
        skipErrorHandler: true,
        ...(options || {}),
      },
    );
    const normalized = normalizeV2ModelUploadDto(raw);
    if (normalized) {
      return { data: normalized };
    }
    throw new Error('模型分片回执缺少 uploadId，请查询上传状态后再操作');
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
  }

  const formData = new FormData();
  formData.append('uploadId', uploadId);
  formData.append('partIndex', String(partIndex));
  formData.append('file', chunk);
  return request<{ data: API.ModelUploadInitResult }>('/model/upload/chunk', {
    method: 'POST',
    data: formData,
    ...(options || {}),
  });
}

/** 查询模型上传进度；仅 V2 接口不支持时兼容旧接口。 */
export async function modelUploadProgress(
  uploadId: string,
  options?: { [key: string]: unknown },
) {
  try {
    const raw = await request<unknown>(
      `/v2/model-uploads/${encodeURIComponent(uploadId)}`,
      {
        method: 'GET',
        skipErrorHandler: true,
        ...(options || {}),
      },
    );
    const normalized = normalizeV2ModelUploadDto(raw);
    if (normalized) {
      return { data: normalized };
    }
    throw new Error('模型进度回执缺少 uploadId，请确认服务端响应');
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
  }

  return request<{ data: API.ModelUploadInitResult }>(
    '/model/upload/progress',
    {
      method: 'GET',
      params: { uploadId },
      ...(options || {}),
    },
  );
}

/**
 * 完成模型上传。
 * 优先 V2 complete（仅 uploadId）；仅接口不支持时兼容 Legacy（带业务字段）。
 * 服务端可能已完成入库，回执异常或超时不能再次提交到另一个接口。
 */
export async function modelUploadComplete(
  params: API.ModelUploadCompleteParams,
  options?: { [key: string]: unknown },
) {
  try {
    const raw = await request<unknown>(
      `/v2/model-uploads/${encodeURIComponent(params.uploadId)}/complete`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        data: {},
        skipErrorHandler: true,
        ...(options || {}),
      },
    );
    const completed = normalizeV2ModelUploadComplete(raw);
    if (completed?.id) {
      return { data: completed };
    }
    throw new Error('模型完成回执缺少版本 ID，请先在模型列表确认是否已保存');
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
  }

  return request<{ data: BackendModelItem }>('/model/upload/complete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: params,
    ...(options || {}),
  });
}

export type ModelConsumerManifest = {
  modelAssetId: string;
  modelVersionId: string;
  version: string;
  status: string;
  type: string;
  fileName: string;
  sizeBytes: number;
  artifactSha256?: string;
  commitInfo?: string;
  hyperParams?: Record<string, unknown>;
  isCurrent?: boolean;
  downloadUrl?: string;
  filesUrl?: string;
};

/** GET /api/v2/model-versions/{versionId}/consumer-manifest */
export async function fetchModelConsumerManifest(
  versionId: string,
  options?: { [key: string]: unknown },
): Promise<ModelConsumerManifest | null> {
  try {
    const raw = await request<unknown>(
      `/v2/model-versions/${encodeURIComponent(versionId)}/consumer-manifest`,
      {
        method: 'GET',
        skipErrorHandler: true,
        ...(options || {}),
      },
    );
    const data = modelReadData(raw) as Record<string, unknown> | null;
    if (!data?.modelVersionId || data.modelVersionId !== versionId)
      throw new Error('模型消费清单回执不完整或版本不匹配');
    return {
      modelAssetId: String(data.modelAssetId || ''),
      modelVersionId: String(data.modelVersionId),
      version: String(data.version || ''),
      status: String(data.status || ''),
      type: String(data.type || ''),
      fileName: String(data.fileName || ''),
      sizeBytes: Number(data.sizeBytes ?? 0),
      artifactSha256: data.artifactSha256
        ? String(data.artifactSha256)
        : undefined,
      commitInfo: data.commitInfo ? String(data.commitInfo) : undefined,
      hyperParams: data.hyperParams as Record<string, unknown> | undefined,
      isCurrent: data.isCurrent === true,
      downloadUrl: data.downloadUrl ? String(data.downloadUrl) : undefined,
      filesUrl: data.filesUrl ? String(data.filesUrl) : undefined,
    };
  } catch (error) {
    if (isLegacyEndpointUnavailable(error)) return null;
    throw error;
  }
}

/** GET /api/model/list 查询参数（module2-api-doc 3.1） */
export type ModelListQuery = {
  type?: ModelTaskType;
  keyword?: string;
  current?: number;
  pageSize?: number;
  page?: number;
  artifactSpecIds?: string;
};

export async function getModelList(
  params?: ModelListQuery & {
    sortBy?: string;
    sortDirection?: string;
  },
  options?: { [key: string]: unknown },
) {
  return request<{ data: { data: BackendModelItem[]; total: number } }>(
    '/model/list',
    {
      method: 'GET',
      params,
      ...(options || {}),
    },
  );
}

export async function getModelDetail(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: BackendModelItem }>('/model/detail', {
    method: 'GET',
    params: { id },
    ...(options || {}),
  });
}

export async function listModelCodeFiles(
  id: string,
  options?: { [key: string]: unknown },
) {
  try {
    const raw = await request<unknown>(
      `/v2/model-versions/${encodeURIComponent(id)}/files`,
      {
        method: 'GET',
        skipErrorHandler: true,
        ...(options || {}),
      },
    );
    return { data: modelFiles(raw) };
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
    const raw = await request<unknown>('/model/code-files', {
      method: 'GET',
      params: { id },
      ...(options || {}),
    });
    return { data: modelFiles(raw) };
  }
}

export async function previewModelCode(
  id: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  try {
    const raw = await request<unknown>(
      `/v2/model-versions/${encodeURIComponent(id)}/files/content`,
      {
        method: 'GET',
        params: { path },
        skipErrorHandler: true,
        ...(options || {}),
      },
    );
    return { data: modelPreview(raw, path) };
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
    const raw = await request<unknown>('/model/previewCode', {
      method: 'GET',
      params: { id, path },
      ...(options || {}),
    });
    return { data: modelPreview(raw, path) };
  }
}

export async function deleteModel(
  id: string,
  options?: { [key: string]: unknown },
) {
  return deleteModelVersion(id, options);
}

// ——— §4 模型资产 CRUD ———

export async function createModelAsset(
  body: Pick<ModelAsset, 'name' | 'type'> & { remark?: string },
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelAsset }>('/model-assets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}

export async function getModelAsset(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelAsset }>(
    `/model-assets/${encodeURIComponent(id)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

export async function listModelAssets(options?: { [key: string]: unknown }) {
  return request<{ data: ModelAsset[] }>('/model-assets', {
    method: 'GET',
    ...(options || {}),
  });
}

export async function updateModelAsset(
  id: string,
  body: Pick<ModelAsset, 'name' | 'type'> & { remark?: string },
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelAsset }>(
    `/model-assets/${encodeURIComponent(id)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

export async function deleteModelAsset(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelDeleteResult }>(
    `/model-assets/${encodeURIComponent(id)}`,
    {
      method: 'DELETE',
      ...(options || {}),
    },
  );
}

/** 切换当前版本；仅新接口不支持时走兼容路径，保留业务冲突和权限错误。 */
export async function switchModelCurrentVersion(
  assetId: string,
  versionId: string,
  options?: { [key: string]: unknown },
) {
  try {
    return await request<{ success?: boolean; data?: unknown }>(
      `/v2/model-assets/${encodeURIComponent(assetId)}/current-version`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        data: { versionId },
        skipErrorHandler: true,
        ...(options || {}),
      },
    );
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
    return request<{ success?: boolean; data?: unknown }>(
      `/model-assets/${encodeURIComponent(assetId)}/current-version`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        data: { versionId },
        ...(options || {}),
      },
    );
  }
}

/**
 * GET /api/v2/model-versions/{versionId}/download
 * 新契约 list/detail 不再保证 storagePath，下载走带鉴权的版本制品流。
 */
export async function downloadModelVersion(
  versionId: string,
  fileName?: string,
  options?: {
    onProgress?: (ratio: number | null) => void;
    [key: string]: unknown;
  },
) {
  await downloadAuthFile({
    url: `/v2/model-versions/${encodeURIComponent(versionId)}/download`,
    fileName: fileName?.trim() || `${versionId}.zip`,
    onProgress: options?.onProgress,
  });
  return { success: true };
}

// ——— §5 模型版本 CRUD ———

export async function createModelVersion(
  body: Pick<ModelVersion, 'assetId' | 'version'>,
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelVersion }>('/model-versions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}

export async function getModelVersion(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelVersion }>(
    `/model-versions/${encodeURIComponent(id)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

export async function listModelVersions(
  assetId?: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelVersion[] }>('/model-versions', {
    method: 'GET',
    params: assetId ? { assetId } : undefined,
    ...(options || {}),
  });
}

export async function updateModelVersion(
  id: string,
  body: Partial<
    Pick<
      ModelVersion,
      'assetId' | 'version' | 'fileName' | 'storagePath' | 'sizeBytes'
    >
  >,
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelVersion }>(
    `/model-versions/${encodeURIComponent(id)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

export async function deleteModelVersion(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: ModelDeleteResult }>(
    `/model-versions/${encodeURIComponent(id)}`,
    {
      method: 'DELETE',
      ...(options || {}),
    },
  );
}

export async function fetchModelList(options?: {
  current?: number;
  pageSize?: number;
  name?: string;
  version?: string;
  type?: string;
  sortBy?: string;
  sortDirection?: string;
  artifactSpecIds?: string[];
}) {
  const params: ModelListQuery & {
    sortBy?: string;
    sortDirection?: string;
  } = {};

  if (options?.type) {
    params.type = options.type as ModelListQuery['type'];
  }

  const name = options?.name?.trim();
  const version = options?.version?.trim();
  const keywordParts = [name, version].filter(Boolean);
  if (keywordParts.length) {
    params.keyword = keywordParts.join(' ');
  }

  if (options?.current) {
    params.current = options.current;
  }
  if (options?.pageSize) {
    params.pageSize = options.pageSize;
  }
  if (options?.sortBy) {
    params.sortBy = options.sortBy;
  }
  if (options?.sortDirection) {
    params.sortDirection = options.sortDirection;
  }
  if (options?.artifactSpecIds) {
    params.artifactSpecIds = options.artifactSpecIds.join(',');
  }

  const res = await getModelList(params);
  const inner = res?.data;
  const list = (inner?.data ?? [])
    .map((item) => mapModelItem(item))
    .filter((item): item is API.ModelItem => Boolean(item));
  const total = inner?.total ?? list.length;
  return { data: list, total };
}

/** V2 训练方案候选：服务端先按规格筛选，再逐页取回，避免 100 条截断。 */
export async function fetchTrainingModelCandidates(artifactSpecIds: string[]) {
  const normalizedSpecIds = [
    ...new Set(artifactSpecIds.map((value) => value.trim())),
  ].filter(Boolean);
  if (!normalizedSpecIds.length) return { data: [], total: 0 };
  return collectPaginatedCandidates<API.ModelItem>(
    (current, pageSize) =>
      fetchModelList({
        current,
        pageSize,
        artifactSpecIds: normalizedSpecIds,
      }),
    { keyOf: (item) => item.id, pageSize: 200 },
  );
}

export async function fetchAllModelList() {
  return collectPaginatedCandidates<API.ModelItem>(
    (current, pageSize) => fetchModelList({ current, pageSize }),
    { keyOf: (item) => item.id, pageSize: 200 },
  );
}

/** §4+§5 模型资产详情（资产 + 版本列表） */
export async function fetchModelAssetDetail(
  assetId: string,
  options?: { [key: string]: unknown },
) {
  const [assetRes, versionRes] = await Promise.all([
    getModelAsset(assetId, options),
    listModelVersions(assetId, options),
  ]);
  const asset = assetRes?.data;
  if (!asset) {
    return { data: undefined };
  }

  let listLatestVersionId: string | undefined;
  try {
    const listRes = await getModelList(
      { pageSize: 200, type: asset.type },
      options,
    );
    const row = (listRes?.data?.data ?? []).find(
      (item) => (item.assetId || item.id) === asset.id,
    );
    listLatestVersionId = row?.id;
  } catch {
    // ignore
  }

  const versions = normalizeModelVersionList(versionRes?.data)
    .map((v) => mapModelVersion(v, asset))
    .filter((v) => !!v.id)
    .sort((a, b) =>
      a.createdAt && b.createdAt ? b.createdAt.localeCompare(a.createdAt) : 0,
    );

  const defaultVersionId =
    (asset.currentVersionId &&
    versions.some((v) => v.id === asset.currentVersionId)
      ? asset.currentVersionId
      : undefined) ??
    versions.find((v) => v.isCurrent)?.id ??
    versions.map((v) => resolveModelVersionId(v, asset.id)).find(Boolean) ??
    (listLatestVersionId && listLatestVersionId !== asset.id
      ? listLatestVersionId
      : undefined);

  const latestVersion =
    versions.find((v) => v.id === defaultVersionId) ?? versions[0];

  return {
    data: {
      id: asset.id,
      name: asset.name,
      type: asset.type ?? 'CV',
      remark: asset.remark,
      createdAt: asset.createdAt,
      updatedAt: asset.updatedAt,
      uploadTime: latestVersion?.createdAt ?? asset.createdAt,
      currentVersionId: asset.currentVersionId ?? defaultVersionId,
      latestVersion,
      versions,
      defaultVersionId,
    } as API.ModelAssetDetail,
  };
}

/** 加载指定版本的代码预览（§3.3 / §3.4） */
export async function fetchModelVersionCodePreview(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  const versionRes = await getModelVersion(versionId, options).catch(
    (error) => {
      if (!isLegacyEndpointUnavailable(error)) throw error;
      return getModelDetail(versionId, options);
    },
  );
  const raw = modelReadData(versionRes) as
    | ModelVersion
    | BackendModelItem
    | undefined;
  const version: API.ModelVersionDetail | undefined = raw
    ? {
        id: raw.id,
        assetId: 'assetId' in raw ? raw.assetId || '' : '',
        version: 'version' in raw ? raw.version : '',
        fileName: 'fileName' in raw ? raw.fileName : undefined,
        storagePath: raw.storagePath,
        sizeBytes: raw.sizeBytes,
        size: formatBytes(raw.sizeBytes),
        createdAt: raw.createdAt,
        updatedAt: 'updatedAt' in raw ? raw.updatedAt : undefined,
        artifactSha256:
          'artifactSha256' in raw ? raw.artifactSha256 : undefined,
        commitInfo: 'commitInfo' in raw ? raw.commitInfo : undefined,
        hyperParams: 'hyperParams' in raw ? raw.hyperParams : undefined,
        isCurrent: 'isCurrent' in raw ? raw.isCurrent : undefined,
        status: 'status' in raw ? raw.status : undefined,
        remark: 'remark' in raw ? raw.remark : undefined,
      }
    : undefined;

  if (!version || version.id !== versionId)
    throw new Error('模型版本回执不完整或版本不匹配');

  let codeContent: string | undefined;
  let codeFileName: string | undefined;
  let codeFilePath: string | undefined;
  let codeFiles: API.ModelCodeFile[] = [];

  {
    const codeFilesRes = await listModelCodeFiles(versionId, options);
    codeFiles = codeFilesRes?.data ?? [];
    if (codeFiles.length > 0 && codeFiles[0].path) {
      const previewRes = await previewModelCode(
        versionId,
        codeFiles[0].path,
        options,
      );
      if (typeof previewRes?.data?.content === 'string') {
        codeContent = previewRes.data.content;
        codeFileName =
          previewRes.data.fileName ||
          codeFiles[0].fileName ||
          codeFiles[0].path;
        codeFilePath = previewRes.data.path || codeFiles[0].path;
      }
    }
  }

  return {
    data: {
      ...version,
      codeContent,
      codeFileName,
      codeFilePath,
      codeFiles,
    } as API.ModelVersionDetail,
  };
}

/** 兼容：按模型版本 ID 查详情（§3.2） */
export async function fetchModelDetail(
  id: string,
  options?: { [key: string]: unknown },
) {
  const codeRes = await fetchModelVersionCodePreview(id, options);
  if (!codeRes?.data) {
    return { data: undefined };
  }

  const version = codeRes.data;
  const updateTime = version.createdAt;
  const detail: API.ModelDetail = {
    id: version.id,
    assetId: version.assetId,
    name: version.name ?? '',
    version: version.version,
    type: (version.type as API.ModelItem['type']) ?? 'CV',
    storagePath: version.storagePath,
    size: version.size,
    sizeBytes: version.sizeBytes,
    uploadTime: version.createdAt,
    updateTime,
    timestamp: toUnixTimestamp(updateTime),
    codeContent: version.codeContent,
    codeFileName: version.codeFileName,
    codeFilePath: version.codeFilePath,
    codeFiles: version.codeFiles,
    versionHistory: [
      {
        version: version.version,
        updateTime: updateTime ?? '',
        timestamp: toUnixTimestamp(updateTime) ?? '',
      },
    ],
  };

  return { data: detail };
}
