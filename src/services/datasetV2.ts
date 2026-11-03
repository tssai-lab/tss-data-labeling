/**
 * 数据集 V2：列表 + 版本工作区（取代 edit-sessions）
 * @see module2-external-contract §6.1.2 / module2-api-doc §18.2
 */
import { request } from '@umijs/max';
import type { DatasetListItem, DatasetListQuery, DatasetType } from './dataset';
import type { MultimodalSampleDetail } from './datasetMultimodal';

export type V2DatasetDisplayStatus =
  | 'EMPTY'
  | 'READY'
  | 'EDITING'
  | 'IMPORTING'
  | 'IMPORT_FAILED'
  | 'IMPORT_PARTIAL';

export type V2DatasetCurrentVersion = {
  versionId: string;
  versionLabel?: string;
  versionNo?: number;
  status?: string;
};

export type V2PublishReadinessBlocker = {
  code?: string;
  message?: string;
  resourceType?: string | null;
  resourceId?: string | null;
};

export type V2PublishReadiness = {
  canPublish: boolean;
  evaluatedRevision?: number;
  blockers?: V2PublishReadinessBlocker[];
};

export type V2DatasetListItem = {
  datasetId: string;
  name: string;
  type: DatasetType;
  currentVersion?: V2DatasetCurrentVersion | null;
  currentVersionFileCount?: number | null;
  artifactSpecId?: string | null;
  displayStatus: V2DatasetDisplayStatus;
  hasDraft?: boolean;
  /** @deprecated 新契约用 workspaceId */
  editSessionId?: string | null;
  importJobId?: string | null;
  workspaceId?: string | null;
  workspaceRevision?: number | null;
  importProgress?: number | null;
  canPublish?: boolean;
  publishReadiness?: V2PublishReadiness | null;
  editability?: {
    canCreateWorkspace?: boolean;
    blockers?: V2PublishReadinessBlocker[];
  } | null;
  availableActions?: string[];
  userError?: {
    errorCode?: string;
    errorMessage?: string;
    details?: Record<string, unknown>;
  } | null;
  remark?: string;
  uploadTime?: string;
  sizeBytes?: number;
  fileName?: string;
};

export type V2DatasetListPage = {
  data: V2DatasetListItem[];
  total: number;
  page: number;
  pageSize: number;
  totalPages?: number;
};

export type V2VersionSummary = {
  versionId: string;
  versionLabel?: string;
  versionNo?: number;
  status?: string;
};

export type V2WorkspaceActiveOperation = {
  type: 'UPLOAD' | 'IMPORT' | string;
  id: string;
  status?: string;
  progress?: number | null;
} | null;

/** 数据集版本工作区 DTO */
export type V2DatasetWorkspace = {
  workspaceId: string;
  datasetId: string;
  baseVersion?: V2VersionSummary | null;
  targetVersion?: V2VersionSummary | null;
  status?: string;
  workspaceRevision: number;
  sampleCount?: number;
  activeOperation?: V2WorkspaceActiveOperation;
  publishReadiness?: V2PublishReadiness | null;
  availableActions?: string[];
  userError?: {
    errorCode?: string;
    errorMessage?: string;
    details?: Record<string, unknown>;
  } | null;
};

export type V2WorkspacePublishResult = {
  datasetId: string;
  currentVersion?: V2VersionSummary | null;
  publishedAt?: string;
};

export type V2VersionAllocation = {
  nextVersionNo?: number;
  defaultVersionLabel?: string;
  requestedVersionLabel?: string;
  requestedVersionLabelAvailable?: boolean;
  unavailableReason?: string;
};

export type V2WorkspaceSamplesPage = {
  data: Array<{
    sampleId?: string;
    id?: string;
    sampleIndex?: number;
    externalId?: string;
    deleted?: boolean;
    dataCount?: number;
    annotationCount?: number;
    [key: string]: unknown;
  }>;
  total: number;
  page: number;
  pageSize: number;
};

function formatBytes(sizeBytes?: number) {
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

function unwrapWorkspace(raw: unknown): V2DatasetWorkspace {
  if (!raw || typeof raw !== 'object') {
    throw new Error('工作区响应为空');
  }
  const obj = raw as Record<string, unknown>;
  const data =
    obj.data && typeof obj.data === 'object'
      ? (obj.data as Record<string, unknown>)
      : obj;
  const workspaceId = String(data.workspaceId || '');
  if (!workspaceId) {
    throw new Error('工作区响应缺少 workspaceId');
  }
  return {
    ...(data as unknown as V2DatasetWorkspace),
    workspaceId,
    datasetId: String(data.datasetId || ''),
    workspaceRevision: Number(data.workspaceRevision ?? 0),
  };
}

/** GET /api/v2/datasets */
export async function getV2DatasetList(
  params?: DatasetListQuery,
  options?: { [key: string]: unknown },
) {
  return request<V2DatasetListPage>('/v2/datasets', {
    method: 'GET',
    params,
    ...(options || {}),
  });
}

/** 将 V2 列表项映射为页面兼容的 DatasetListItem */
export function mapV2DatasetToListItem(row: V2DatasetListItem): DatasetListItem {
  const current = row.currentVersion;
  const workspaceId = row.workspaceId || row.editSessionId || null;
  const importStatusFromDisplay =
    row.displayStatus === 'IMPORTING'
      ? 'RUNNING'
      : row.displayStatus === 'IMPORT_FAILED'
        ? 'FAILED'
        : row.displayStatus === 'IMPORT_PARTIAL'
          ? 'PARTIAL'
          : null;
  return {
    id: row.datasetId,
    assetId: row.datasetId,
    name: row.name,
    type: row.type,
    remark: row.remark,
    versionId: current?.versionId,
    version: current?.versionLabel,
    versionStatus: current?.status,
    artifactSpecId: row.artifactSpecId ?? undefined,
    fileCount: row.currentVersionFileCount ?? undefined,
    uploadTime: row.uploadTime,
    sizeBytes: row.sizeBytes,
    size: formatBytes(row.sizeBytes),
    fileName: row.fileName,
    displayStatus: row.displayStatus,
    editSessionId: workspaceId,
    workspaceId,
    workspaceRevision: row.workspaceRevision ?? null,
    hasDraft: row.hasDraft,
    importJobId: row.importJobId ?? null,
    importStatus: importStatusFromDisplay,
    importProgress: row.importProgress,
    importErrorMessage: row.userError?.errorMessage ?? null,
    // 列表未必给 targetVersionId；详情页用 workspaceId 再拉工作区
    latestDraftVersionId: null,
  };
}

/** GET /api/v2/datasets/{datasetId}/version-allocation */
export async function getDatasetVersionAllocation(
  datasetId: string,
  versionLabel?: string,
  options?: { [key: string]: unknown },
) {
  return request<V2VersionAllocation>(
    `/v2/datasets/${encodeURIComponent(datasetId)}/version-allocation`,
    {
      method: 'GET',
      params: versionLabel?.trim()
        ? { versionLabel: versionLabel.trim() }
        : undefined,
      ...(options || {}),
    },
  );
}

/**
 * POST /api/v2/datasets/{datasetId}/workspaces
 * 创建或继续活动工作区。
 * - versionLabel：创建时写入目标草稿标签（无需再 Legacy PUT）
 * - baseVersionId：期望基线 READY 版本（方案 A；后端待支持，前端先传）
 */
export async function createOrOpenDatasetWorkspace(
  datasetId: string,
  body?: { versionLabel?: string; baseVersionId?: string } | null,
  options?: { [key: string]: unknown },
) {
  const label = body?.versionLabel?.trim();
  const baseVersionId = body?.baseVersionId?.trim();
  const data: Record<string, string> = {};
  if (label) data.versionLabel = label;
  if (baseVersionId) data.baseVersionId = baseVersionId;
  const hasBody = Object.keys(data).length > 0;
  const raw = await request<unknown>(
    `/v2/datasets/${encodeURIComponent(datasetId)}/workspaces`,
    {
      method: 'POST',
      ...(hasBody
        ? {
            headers: { 'Content-Type': 'application/json' },
            data,
          }
        : {}),
      ...(options || {}),
    },
  );
  return unwrapWorkspace(raw);
}

/** GET /api/v2/dataset-workspaces/{workspaceId} */
export async function getDatasetWorkspace(
  workspaceId: string,
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}`,
    { method: 'GET', ...(options || {}) },
  );
  return unwrapWorkspace(raw);
}

/** GET .../readiness */
export async function getDatasetWorkspaceReadiness(
  workspaceId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2PublishReadiness>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/readiness`,
    { method: 'GET', ...(options || {}) },
  );
}

/** POST .../publish — body 带 expectedWorkspaceRevision */
export async function publishDatasetWorkspace(
  workspaceId: string,
  expectedWorkspaceRevision: number,
  options?: { [key: string]: unknown },
) {
  return request<V2WorkspacePublishResult>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/publish`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: { expectedWorkspaceRevision },
      ...(options || {}),
    },
  );
}

/** DELETE ... — 放弃工作区 */
export async function abandonDatasetWorkspace(
  workspaceId: string,
  expectedWorkspaceRevision: number,
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}`,
    {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      data: { expectedWorkspaceRevision },
      ...(options || {}),
    },
  );
  // 放弃后可能仍返回工作区 DTO（ABANDONED）
  try {
    return unwrapWorkspace(raw);
  } catch {
    return null;
  }
}

function normalizeSamplesPage(
  raw: unknown,
  fallbackPage: number,
  fallbackSize: number,
): V2WorkspaceSamplesPage {
  const obj = (raw && typeof raw === 'object' ? raw : {}) as Record<
    string,
    unknown
  >;
  const inner =
    obj.data && typeof obj.data === 'object' && !Array.isArray(obj.data)
      ? (obj.data as Record<string, unknown>)
      : obj;
  const list = Array.isArray(inner.data)
    ? inner.data
    : Array.isArray(obj.data)
      ? obj.data
      : Array.isArray(raw)
        ? raw
        : [];
  return {
    data: list as V2WorkspaceSamplesPage['data'],
    total: Number(inner.total ?? obj.total ?? list.length),
    page: Number(inner.page ?? obj.page ?? fallbackPage),
    pageSize: Number(inner.pageSize ?? obj.pageSize ?? fallbackSize),
  };
}

/** GET /api/v2/dataset-workspaces/{workspaceId}/samples */
export async function listDatasetWorkspaceSamples(
  workspaceId: string,
  params?: { page?: number; pageSize?: number; includeDeleted?: boolean },
  options?: { [key: string]: unknown },
) {
  const page = params?.page ?? 1;
  const pageSize = params?.pageSize ?? 20;
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/samples`,
    {
      method: 'GET',
      params: {
        page,
        pageSize,
        includeDeleted: params?.includeDeleted ?? false,
      },
      ...(options || {}),
    },
  );
  return { data: normalizeSamplesPage(raw, page, pageSize) };
}

/** GET .../samples/{sampleId} */
export async function getDatasetWorkspaceSample(
  workspaceId: string,
  sampleId: string,
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/samples/${encodeURIComponent(sampleId)}`,
    { method: 'GET', ...(options || {}) },
  );
  return { data: normalizeDatasetWorkspaceSample(raw) };
}

/** 把 V2 工作区字段名适配成详情组件沿用的字段名。 */
export function normalizeDatasetWorkspaceSample(
  raw: unknown,
): MultimodalSampleDetail | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const response = raw as Record<string, unknown>;
  const nested = response.data;
  const sample =
    nested && typeof nested === 'object' && !Array.isArray(nested)
      ? (nested as Record<string, unknown>)
      : response;
  const data = Array.isArray(sample.data)
    ? sample.data.map((item) => {
        const value = item as Record<string, unknown>;
        return {
          ...value,
          sampleDataId: String(value.sampleDataId || value.dataId || ''),
        };
      })
    : [];
  return {
    ...sample,
    sampleId: String(sample.sampleId || ''),
    datasetVersionId: String(
      sample.datasetVersionId || sample.workspaceId || '',
    ),
    sampleIndex: Number(sample.sampleIndex ?? 0),
    data,
    annotations: Array.isArray(sample.annotations) ? sample.annotations : [],
  } as MultimodalSampleDetail;
}

/** DELETE .../samples/{sampleId} */
export async function deleteDatasetWorkspaceSample(
  workspaceId: string,
  sampleId: string,
  expectedWorkspaceRevision: number,
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/samples/${encodeURIComponent(sampleId)}`,
    {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      data: { expectedWorkspaceRevision },
      ...(options || {}),
    },
  );
  return unwrapWorkspaceRevision(raw, expectedWorkspaceRevision);
}

/** POST .../samples/{sampleId}/restore */
export async function restoreDatasetWorkspaceSample(
  workspaceId: string,
  sampleId: string,
  expectedWorkspaceRevision: number,
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/samples/${encodeURIComponent(sampleId)}/restore`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: { expectedWorkspaceRevision },
      ...(options || {}),
    },
  );
  return unwrapWorkspaceRevision(raw, expectedWorkspaceRevision);
}

/** 从工作区 DTO 提取活动 ImportJob 句柄（仅 IMPORT 活动期有效） */
export function extractActiveImportJobId(
  ws?: Pick<V2DatasetWorkspace, 'activeOperation'> | null,
): string | null {
  const op = ws?.activeOperation;
  if (!op || op.type !== 'IMPORT' || !op.id) return null;
  return String(op.id);
}

/**
 * PATCH /api/v2/dataset-workspaces/{workspaceId}
 * Content-Type: application/merge-patch+json
 */
export async function patchDatasetWorkspace(
  workspaceId: string,
  body: {
    expectedWorkspaceRevision: number;
    description?: string | null;
    changeLog?: string | null;
    cvTaskType?: string | null;
    annotationFormat?: string | null;
  },
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/merge-patch+json' },
      data: body,
      ...(options || {}),
    },
  );
  return unwrapWorkspace(raw);
}

/** POST .../samples — 创建样本（externalId 必填） */
export async function createDatasetWorkspaceSample(
  workspaceId: string,
  body: {
    expectedWorkspaceRevision: number;
    externalId: string;
    tags?: Record<string, unknown>;
    metadata?: Record<string, unknown>;
  },
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/samples`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
  const obj = raw as Record<string, unknown>;
  const data =
    obj?.data && typeof obj.data === 'object' ? obj.data : raw;
  const revision = unwrapWorkspaceRevision(raw, body.expectedWorkspaceRevision);
  return { data, ...revision };
}

/** PATCH .../samples/{sampleId} — 仅 tags/metadata + revision */
export async function patchDatasetWorkspaceSample(
  workspaceId: string,
  sampleId: string,
  body: {
    expectedWorkspaceRevision: number;
    tags?: Record<string, unknown> | null;
    metadata?: Record<string, unknown> | null;
  },
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/samples/${encodeURIComponent(sampleId)}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/merge-patch+json' },
      data: body,
      ...(options || {}),
    },
  );
  return unwrapWorkspaceRevision(raw, body.expectedWorkspaceRevision);
}

/** POST .../samples/{sampleId}/data — 内联小文本数据组件 */
export async function createDatasetWorkspaceSampleData(
  workspaceId: string,
  sampleId: string,
  body: {
    expectedWorkspaceRevision: number;
    dataType?: string;
    format?: string;
    fileName?: string;
    contentType?: string;
    content?: string;
    metadata?: Record<string, unknown>;
  },
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/samples/${encodeURIComponent(sampleId)}/data`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
  return unwrapWorkspaceRevision(raw, body.expectedWorkspaceRevision);
}

/** POST .../samples/{sampleId}/annotations — 内联小文本标注 */
export async function createDatasetWorkspaceSampleAnnotation(
  workspaceId: string,
  sampleId: string,
  body: {
    expectedWorkspaceRevision: number;
    annotationType?: string;
    format?: string;
    fileName?: string;
    contentType?: string;
    content?: string;
    sampleDataId?: string;
    metadata?: Record<string, unknown>;
  },
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/samples/${encodeURIComponent(sampleId)}/annotations`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
  return unwrapWorkspaceRevision(raw, body.expectedWorkspaceRevision);
}

/** POST .../file-uploads — 大文件/二进制组件 CREATE|REPLACE */
export async function initDatasetWorkspaceFileUpload(
  workspaceId: string,
  body: {
    expectedWorkspaceRevision: number;
    /** 后端契约：CREATE / REPLACE */
    targetOperation: 'CREATE' | 'REPLACE';
    /** 后端契约：DATA / ANNOTATION */
    targetKind: 'DATA' | 'ANNOTATION';
    sampleId: string;
    resourceId?: string;
    fileName: string;
    fileSize: number;
    fileFingerprint?: string;
    dataType?: string;
    format?: string;
    contentType?: string;
  },
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/file-uploads`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
  return unwrapPackageUploadInit(raw);
}

function unwrapPackageUploadInit(raw: unknown): {
  data: V2PackageUploadInitResult;
} {
  const obj = (raw && typeof raw === 'object' ? raw : {}) as Record<
    string,
    unknown
  >;
  const data = (
    obj?.data && typeof obj.data === 'object' ? obj.data : obj
  ) as Record<string, unknown>;
  return {
    data: {
      uploadId: String(data.uploadId || ''),
      chunkSize: data.chunkSize as number | undefined,
      totalChunks: data.totalChunks as number | undefined,
      uploadedPartIndexes: data.uploadedPartIndexes as number[] | undefined,
      workspaceRevision: data.workspaceRevision as number | undefined,
    },
  };
}

/** 组件文件分片上传到工作区（CREATE/REPLACE） */
export async function uploadDatasetWorkspaceFileComponent(
  workspaceId: string,
  file: File,
  params: {
    expectedWorkspaceRevision: number;
    targetOperation: 'CREATE' | 'REPLACE';
    targetKind: 'DATA' | 'ANNOTATION';
    sampleId: string;
    resourceId?: string;
    dataType?: string;
    format?: string;
    contentType?: string;
    onProgress?: (percent: number) => void;
    onRevision?: (revision: number) => void;
  },
  options?: { [key: string]: unknown },
) {
  let revision = params.expectedWorkspaceRevision;
  const fp = [
    file.name,
    String(file.size),
    workspaceId,
    params.sampleId,
    params.targetOperation,
  ].join('|');
  const initRes = await initDatasetWorkspaceFileUpload(
    workspaceId,
    {
      expectedWorkspaceRevision: revision,
      targetOperation: params.targetOperation,
      targetKind: params.targetKind,
      sampleId: params.sampleId,
      resourceId: params.resourceId,
      fileName: file.name,
      fileSize: file.size,
      fileFingerprint: fp,
      dataType: params.dataType,
      format: params.format,
      contentType: params.contentType,
    },
    options,
  );
  const uploadId = initRes.data.uploadId;
  if (!uploadId) throw new Error('初始化组件上传失败');
  if (typeof initRes.data.workspaceRevision === 'number') {
    revision = initRes.data.workspaceRevision;
    params.onRevision?.(revision);
  }
  const chunkSize =
    initRes.data.chunkSize && initRes.data.chunkSize > 0
      ? initRes.data.chunkSize
      : 5 * 1024 * 1024;
  const totalChunks =
    initRes.data.totalChunks && initRes.data.totalChunks > 0
      ? initRes.data.totalChunks
      : Math.max(1, Math.ceil(file.size / chunkSize));
  const done = new Set(initRes.data.uploadedPartIndexes ?? []);
  let uploaded = done.size;
  for (let partIndex = 0; partIndex < totalChunks; partIndex += 1) {
    if (done.has(partIndex)) continue;
    const start = partIndex * chunkSize;
    const end = Math.min(start + chunkSize, file.size);
    await uploadDatasetWorkspaceChunk(
      uploadId,
      partIndex,
      file.slice(start, end),
      options,
    );
    uploaded += 1;
    params.onProgress?.(
      Math.min(100, Math.round((uploaded / totalChunks) * 100)),
    );
  }
  const completed = await completeDatasetWorkspaceUpload(
    uploadId,
    revision,
    options,
  );
  if (typeof completed.data.workspaceRevision === 'number') {
    params.onRevision?.(completed.data.workspaceRevision);
  }
  return completed;
}

/** POST /api/v2/dataset-uploads/init — 首次资产 ZIP 上传（V2） */
export async function initV2DatasetUpload(
  body: Record<string, unknown>,
  options?: { [key: string]: unknown },
) {
  return request<{
    data?: {
      uploadId: string;
      chunkSize?: number;
      totalChunks?: number;
      uploadedPartIndexes?: number[];
      status?: string;
    };
    uploadId?: string;
    chunkSize?: number;
    totalChunks?: number;
    uploadedPartIndexes?: number[];
  }>('/v2/dataset-uploads/init', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}

/** GET /api/v2/dataset-uploads/{uploadId} */
export async function getV2DatasetUpload(
  uploadId: string,
  options?: { [key: string]: unknown },
) {
  return request<unknown>(
    `/v2/dataset-uploads/${encodeURIComponent(uploadId)}`,
    { method: 'GET', ...(options || {}) },
  );
}

function unwrapWorkspaceRevision(
  raw: unknown,
  fallback: number,
): { workspaceRevision: number } {
  try {
    const ws = unwrapWorkspace(raw);
    return { workspaceRevision: ws.workspaceRevision };
  } catch {
    const obj = raw as Record<string, unknown>;
    const data = (obj?.data || obj) as Record<string, unknown>;
    return {
      workspaceRevision: Number(data?.workspaceRevision ?? fallback),
    };
  }
}

export type V2PackageUploadInitResult = {
  uploadId: string;
  chunkSize?: number;
  totalChunks?: number;
  uploadedPartIndexes?: number[];
  workspaceRevision?: number;
};

/** POST .../package-uploads */
export async function initDatasetWorkspacePackageUpload(
  workspaceId: string,
  body: {
    fileName: string;
    fileSize: number;
    fileFingerprint?: string;
    expectedWorkspaceRevision: number;
    sampleGrouping?: string;
    manifestPath?: string;
  },
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-workspaces/${encodeURIComponent(workspaceId)}/package-uploads`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
  const obj = raw as Record<string, unknown>;
  const data = (
    obj?.data && typeof obj.data === 'object' ? obj.data : obj
  ) as Record<string, unknown>;
  return {
    data: {
      uploadId: String(data.uploadId || ''),
      chunkSize: data.chunkSize as number | undefined,
      totalChunks: data.totalChunks as number | undefined,
      uploadedPartIndexes: data.uploadedPartIndexes as number[] | undefined,
      workspaceRevision: data.workspaceRevision as number | undefined,
    } as V2PackageUploadInitResult,
  };
}

/** POST /api/v2/dataset-uploads/{uploadId}/chunks */
export async function uploadDatasetWorkspaceChunk(
  uploadId: string,
  partIndex: number,
  file: Blob,
  options?: { [key: string]: unknown },
) {
  const formData = new FormData();
  formData.append('partIndex', String(partIndex));
  formData.append('file', file);
  return request<unknown>(
    `/v2/dataset-uploads/${encodeURIComponent(uploadId)}/chunks`,
    {
      method: 'POST',
      data: formData,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/dataset-uploads/{uploadId}/complete */
export async function completeDatasetWorkspaceUpload(
  uploadId: string,
  expectedWorkspaceRevision: number,
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/dataset-uploads/${encodeURIComponent(uploadId)}/complete`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: { expectedWorkspaceRevision },
      ...(options || {}),
    },
  );
  const obj = raw as Record<string, unknown>;
  const data = (
    obj?.data && typeof obj.data === 'object' ? obj.data : obj
  ) as Record<string, unknown>;
  return {
    data: {
      importJobId: (data.importJobId as string | null | undefined) ?? null,
      workspaceRevision: data.workspaceRevision as number | undefined,
      status: data.status as string | undefined,
    },
  };
}

/** 追加上传 ZIP 到 V2 工作区 */
export async function uploadDatasetWorkspaceAppendPackage(
  workspaceId: string,
  file: File,
  params: {
    expectedWorkspaceRevision: number;
    sampleGrouping?: string;
    manifestPath?: string;
    onProgress?: (percent: number) => void;
    onRevision?: (revision: number) => void;
  },
  options?: { [key: string]: unknown },
) {
  let revision = params.expectedWorkspaceRevision;
  const fp = [file.name, String(file.size), workspaceId, 'append'].join('|');
  const initRes = await initDatasetWorkspacePackageUpload(
    workspaceId,
    {
      fileName: file.name,
      fileSize: file.size,
      fileFingerprint: fp,
      expectedWorkspaceRevision: revision,
      ...(params.sampleGrouping
        ? { sampleGrouping: params.sampleGrouping }
        : {}),
      ...(params.manifestPath?.trim()
        ? { manifestPath: params.manifestPath.trim() }
        : {}),
    },
    options,
  );
  const init = initRes.data;
  if (!init.uploadId) {
    throw new Error('初始化追加上传失败：缺少 uploadId');
  }
  if (init.workspaceRevision != null) {
    revision = init.workspaceRevision;
    params.onRevision?.(revision);
  }

  const chunkSize =
    init.chunkSize && init.chunkSize > 0 ? init.chunkSize : 5 * 1024 * 1024;
  const totalChunks =
    init.totalChunks && init.totalChunks > 0
      ? init.totalChunks
      : Math.max(1, Math.ceil(file.size / chunkSize));
  const uploaded = new Set(init.uploadedPartIndexes ?? []);
  let finished = uploaded.size;

  for (let partIndex = 0; partIndex < totalChunks; partIndex += 1) {
    if (uploaded.has(partIndex)) {
      params.onProgress?.(
        Math.min(100, Math.round((finished / totalChunks) * 100)),
      );
      continue;
    }
    const start = partIndex * chunkSize;
    const end = Math.min(start + chunkSize, file.size);
    await uploadDatasetWorkspaceChunk(
      init.uploadId,
      partIndex,
      file.slice(start, end),
      options,
    );
    finished += 1;
    params.onProgress?.(
      Math.min(100, Math.round((finished / totalChunks) * 100)),
    );
  }

  const completeRes = await completeDatasetWorkspaceUpload(
    init.uploadId,
    revision,
    options,
  );
  if (completeRes.data.workspaceRevision != null) {
    params.onRevision?.(completeRes.data.workspaceRevision);
  }
  return completeRes;
}

export function formatPublishBlockers(
  readiness?: V2PublishReadiness | null,
): string {
  const blockers = readiness?.blockers ?? [];
  if (!blockers.length) {
    return readiness?.canPublish === false
      ? '当前工作区不可发布'
      : '';
  }
  return blockers
    .map((b) => b.message || b.code || '未知阻塞')
    .filter(Boolean)
    .join('；');
}

/** @deprecated 请改用 createOrOpenDatasetWorkspace */
export async function getOrCreateV2EditSession(
  datasetId: string,
  options?: { [key: string]: unknown },
  meta?: { versionLabel?: string; version?: string; baseVersionId?: string },
) {
  const ws = await createOrOpenDatasetWorkspace(
    datasetId,
    {
      versionLabel: meta?.versionLabel || meta?.version,
      baseVersionId: meta?.baseVersionId,
    },
    options,
  );
  return {
    editSessionId: ws.workspaceId,
    status: ws.status,
    parentVersionId: ws.baseVersion?.versionId,
    sampleCount: ws.sampleCount,
    canPublish: ws.publishReadiness?.canPublish,
    targetVersionId: ws.targetVersion?.versionId,
    ...ws,
  };
}

/** @deprecated */
export async function getV2EditSession(
  editSessionId: string,
  options?: { [key: string]: unknown },
) {
  const ws = await getDatasetWorkspace(editSessionId, options);
  return {
    editSessionId: ws.workspaceId,
    ...ws,
  };
}

/** @deprecated */
export async function publishV2EditSession(
  editSessionId: string,
  options?: { [key: string]: unknown },
) {
  const ws = await getDatasetWorkspace(editSessionId, {
    skipErrorHandler: true,
    ...(options || {}),
  });
  return publishDatasetWorkspace(
    editSessionId,
    ws.workspaceRevision,
    options,
  );
}

export const V2_DISPLAY_STATUS_LABEL: Record<V2DatasetDisplayStatus, string> = {
  EMPTY: '空',
  READY: '就绪',
  EDITING: '编辑中',
  IMPORTING: '导入中',
  IMPORT_FAILED: '导入失败',
  IMPORT_PARTIAL: '部分导入',
};
