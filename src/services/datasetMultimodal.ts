import { request } from '@umijs/max';
import { FILE_DOWNLOAD_REQUEST_TIMEOUT } from '@/constants/request';
import { downloadAuthFile } from '@/utils/authFileDownload';
import {
  datasetUploadChunk,
  type DatasetUploadProgress,
  type MultimodalSampleGrouping,
} from '@/services/dataset';

const DEFAULT_CHUNK = 5 * 1024 * 1024;

export type { MultimodalSampleGrouping };

/** 多模态导入任务状态（含 PARTIAL 部分成功） */
export type MultimodalImportStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'PARTIAL';

export type ImportRetryMode = 'FULL' | 'INCREMENTAL';

export type MultimodalImportJob = {
  importJobId: string;
  datasetVersionId?: string;
  workspaceId?: string | null;
  workspaceRevision?: number | null;
  status: MultimodalImportStatus;
  /** V2 展示状态 */
  displayStatus?: string | null;
  progress: number;
  totalSamples?: number | null;
  importedSamples: number;
  failedSamples?: number | null;
  retryable?: boolean;
  retryModes?: ImportRetryMode[];
  errorMessage?: string | null;
  /** Legacy ImportJob 结构化错误码（与 userError.errorCode 可能同源） */
  errorCode?: string | null;
  /** Legacy ImportJob 详情 JSON 文本 */
  errorDetailsJson?: string | null;
  userError?: {
    errorCode?: string;
    errorMessage?: string;
    details?: Record<string, unknown>;
  } | null;
  createdAt?: string;
  startedAt?: string | null;
  finishedAt?: string | null;
};

function unwrapImportJob(raw: unknown): MultimodalImportJob {
  const obj = (raw && typeof raw === 'object' ? raw : {}) as Record<
    string,
    unknown
  >;
  const data = (
    obj.data && typeof obj.data === 'object' ? obj.data : obj
  ) as Record<string, unknown>;
  const progress =
    typeof data.importProgress === 'number'
      ? data.importProgress
      : typeof data.progress === 'number'
        ? data.progress
        : 0;
  const retryModes = Array.isArray(data.retryModes)
    ? (data.retryModes as ImportRetryMode[])
    : undefined;
  const userError =
    data.userError && typeof data.userError === 'object'
      ? (data.userError as MultimodalImportJob['userError'])
      : null;
  return {
    importJobId: String(data.importJobId || ''),
    datasetVersionId: data.datasetVersionId
      ? String(data.datasetVersionId)
      : undefined,
    workspaceId: (data.workspaceId as string | null | undefined) ?? null,
    workspaceRevision:
      typeof data.workspaceRevision === 'number'
        ? data.workspaceRevision
        : null,
    status: (data.status as MultimodalImportStatus) || 'PENDING',
    displayStatus: (data.displayStatus as string | null | undefined) ?? null,
    progress,
    totalSamples:
      typeof data.totalSamples === 'number' ? data.totalSamples : null,
    importedSamples:
      typeof data.importedSamples === 'number' ? data.importedSamples : 0,
    failedSamples:
      typeof data.failedSamples === 'number' ? data.failedSamples : null,
    retryable: Boolean(data.retryable),
    retryModes,
    errorMessage:
      (data.errorMessage as string | null | undefined) ??
      userError?.errorMessage ??
      null,
    errorCode:
      (data.errorCode as string | null | undefined) ??
      userError?.errorCode ??
      null,
    errorDetailsJson:
      (data.errorDetailsJson as string | null | undefined) ??
      (userError?.details ? JSON.stringify(userError.details) : null),
    userError,
    createdAt: data.createdAt as string | undefined,
    startedAt: (data.startedAt as string | null | undefined) ?? null,
    finishedAt: (data.finishedAt as string | null | undefined) ?? null,
  };
}

export type MultimodalSampleSummary = {
  sampleId: string;
  datasetVersionId: string;
  externalId?: string;
  sampleIndex: number;
  tags?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  deleted?: boolean;
  createdAt?: string;
};

export type MultimodalSampleDataType =
  | 'IMAGE'
  | 'TEXT'
  | 'VIDEO'
  | 'AUDIO'
  | 'POINT_CLOUD'
  | 'OTHER';

export type MultimodalSampleDataItem = {
  sampleDataId: string;
  dataType: MultimodalSampleDataType;
  sensor?: string;
  channel?: string;
  seq?: number;
  format?: string;
  fileName?: string;
  sizeBytes?: number;
  checksum?: string | null;
  contentType?: string;
  metadata?: Record<string, unknown>;
  createdAt?: string;
  /** consumer-manifest 提供的固定预览/下载链接 */
  previewUrl?: string;
  downloadUrl?: string;
};

export type MultimodalSampleAnnotation = {
  annotationId: string;
  sampleDataId?: string;
  annotationType?: string;
  format?: string;
  fileName?: string;
  sizeBytes?: number;
  checksum?: string | null;
  contentType?: string;
  metadata?: Record<string, unknown>;
  createdAt?: string;
  downloadUrl?: string;
};

export type MultimodalSampleDetail = MultimodalSampleSummary & {
  data: MultimodalSampleDataItem[];
  annotations: MultimodalSampleAnnotation[];
};

export type MultimodalSamplesPage = {
  data: MultimodalSampleSummary[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
};

/**
 * POST /api/v2/import-jobs/{importJobId}/retry
 * body: { mode, expectedWorkspaceRevision }
 */
export async function retryMultimodalImport(
  importJobId: string,
  params: {
    mode: ImportRetryMode;
    expectedWorkspaceRevision: number;
  },
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/import-jobs/${encodeURIComponent(importJobId)}/retry`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: {
        mode: params.mode,
        expectedWorkspaceRevision: params.expectedWorkspaceRevision,
      },
      ...(options || {}),
    },
  );
  return { data: unwrapImportJob(raw) };
}

/** @deprecated Legacy query-mode 重试；新前端请用 retryMultimodalImport + revision */
export async function retryMultimodalImportLegacy(
  importJobId: string,
  mode: ImportRetryMode = 'FULL',
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/dataset-samples/import/${encodeURIComponent(importJobId)}/retry`,
    {
      method: 'POST',
      params: { mode },
      ...(options || {}),
    },
  );
  return { data: unwrapImportJob(raw) };
}

export type ConsumerManifestSample = MultimodalSampleSummary & {
  data: MultimodalSampleDataItem[];
  annotations: MultimodalSampleAnnotation[];
};

export type ConsumerManifestPage = {
  datasetVersionId: string;
  datasetId?: string;
  type?: string;
  versionLabel?: string;
  status?: string;
  page: number;
  pageSize: number;
  totalSamples: number;
  samples: ConsumerManifestSample[];
};

/** GET /api/v2/dataset-versions/{versionId}/consumer-manifest */
export async function fetchConsumerManifest(
  versionId: string,
  params?: { page?: number; pageSize?: number },
  options?: { [key: string]: unknown },
) {
  return request<ConsumerManifestPage>(
    `/v2/dataset-versions/${encodeURIComponent(versionId)}/consumer-manifest`,
    {
      method: 'GET',
      params,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/import-jobs/{importJobId}（新前端不以 Legacy 为主路径） */
export async function fetchMultimodalImportStatus(
  importJobId: string,
  options?: { [key: string]: unknown },
) {
  const raw = await request<unknown>(
    `/v2/import-jobs/${encodeURIComponent(importJobId)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
  return { data: unwrapImportJob(raw) };
}

/** GET /api/dataset-versions/{versionId}/samples */
export async function fetchMultimodalSamples(
  versionId: string,
  params?: { page?: number; pageSize?: number },
  options?: { [key: string]: unknown },
) {
  return request<{ data: MultimodalSamplesPage }>(
    `/dataset-versions/${encodeURIComponent(versionId)}/samples`,
    {
      method: 'GET',
      params,
      ...(options || {}),
    },
  );
}

/** GET /api/dataset-samples/{sampleId} */
export async function fetchMultimodalSampleDetail(
  sampleId: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: MultimodalSampleDetail }>(
    `/dataset-samples/${encodeURIComponent(sampleId)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** GET /api/dataset-sample-data/{dataId}/preview */
export async function fetchMultimodalDataPreview(
  dataId: string,
  options?: { [key: string]: unknown },
) {
  return request<Blob>(
    `/dataset-sample-data/${encodeURIComponent(dataId)}/preview`,
    {
      method: 'GET',
      responseType: 'blob',
      timeout: FILE_DOWNLOAD_REQUEST_TIMEOUT,
      ...(options || {}),
    },
  );
}

/** 浏览器原生下载样本数据；响应不进入前端内存。 */
export async function downloadMultimodalDataWithBrowser(
  dataId: string,
  fileName: string,
) {
  return downloadAuthFile({
    url: `/dataset-sample-data/${encodeURIComponent(dataId)}/download`,
    fileName: fileName || 'data',
  });
}

/** 浏览器原生下载样本标注；响应不进入前端内存。 */
export async function downloadMultimodalAnnotationWithBrowser(
  annotationId: string,
  fileName: string,
) {
  return downloadAuthFile({
    url: `/dataset-annotations/${encodeURIComponent(annotationId)}/download`,
    fileName: fileName || 'annotation',
  });
}


export const MULTIMODAL_DATA_TYPE_LABEL: Record<MultimodalSampleDataType, string> =
  {
    IMAGE: '图片',
    TEXT: '文本',
    VIDEO: '视频',
    AUDIO: '音频',
    POINT_CLOUD: '点云',
    OTHER: '其它',
  };

export const MULTIMODAL_IMPORT_STATUS_LABEL: Record<
  MultimodalImportStatus,
  string
> = {
  PENDING: '等待导入',
  RUNNING: '导入中',
  SUCCESS: '导入成功',
  FAILED: '导入失败',
  PARTIAL: '部分导入',
};

export type CreateWorkspaceDraftResult = {
  draftVersionId: string;
  parentVersionId: string;
  datasetAssetId: string;
  versionNo: number;
  status: string;
  currentVersionId: string;
  message?: string;
};

export type PublishDraftResult = {
  datasetVersionId: string;
  datasetAssetId: string;
  versionNo?: number;
  status: string;
  currentVersionId?: string;
  message?: string;
};

export type DraftPackageCompleteResult = {
  packageId?: string;
  packageRole?: string;
  packageOrder?: number;
  importJobId?: string;
  status?: string;
};

/** POST /api/dataset-versions/{readyVersionId}/draft */
export async function createWorkspaceDraft(
  readyVersionId: string,
  options?: { [key: string]: unknown },
  meta?: {
    versionLabel?: string;
    version?: string;
    remark?: string;
    description?: string;
    changeLog?: string;
  },
) {
  const body =
    meta &&
    Object.fromEntries(
      Object.entries(meta).filter(
        ([, value]) => value != null && String(value).trim() !== '',
      ),
    );
  return request<{ success?: boolean; data: CreateWorkspaceDraftResult }>(
    `/dataset-versions/${encodeURIComponent(readyVersionId)}/draft`,
    {
      method: 'POST',
      ...(body && Object.keys(body).length
        ? {
            headers: { 'Content-Type': 'application/json' },
            data: body,
          }
        : {}),
      ...(options || {}),
    },
  );
}

/** POST /api/dataset-versions/{draftVersionId}/publish */
export async function publishDraftVersion(
  draftVersionId: string,
  options?: { [key: string]: unknown },
) {
  return request<{ success?: boolean; data: PublishDraftResult }>(
    `/dataset-versions/${encodeURIComponent(draftVersionId)}/publish`,
    { method: 'POST', ...(options || {}) },
  );
}

/** GET /api/dataset-versions/{draftVersionId}/workspace/samples */
export async function fetchWorkspaceSamples(
  draftVersionId: string,
  params?: { page?: number; pageSize?: number; includeDeleted?: boolean },
  options?: { [key: string]: unknown },
) {
  return request<{ data: MultimodalSamplesPage }>(
    `/dataset-versions/${encodeURIComponent(draftVersionId)}/workspace/samples`,
    {
      method: 'GET',
      params,
      ...(options || {}),
    },
  );
}

/** GET /api/dataset-samples/{sampleId}/workspace */
export async function fetchWorkspaceSampleDetail(
  sampleId: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: MultimodalSampleDetail }>(
    `/dataset-samples/${encodeURIComponent(sampleId)}/workspace`,
    { method: 'GET', ...(options || {}) },
  );
}

/** DELETE /api/dataset-samples/{sampleId}/workspace */
export async function deleteWorkspaceSample(
  sampleId: string,
  options?: { [key: string]: unknown },
) {
  return request<{ success?: boolean }>(
    `/dataset-samples/${encodeURIComponent(sampleId)}/workspace`,
    { method: 'DELETE', ...(options || {}) },
  );
}

/** POST /api/dataset-samples/{sampleId}/workspace/restore */
export async function restoreWorkspaceSample(
  sampleId: string,
  options?: { [key: string]: unknown },
) {
  return request<{ success?: boolean }>(
    `/dataset-samples/${encodeURIComponent(sampleId)}/workspace/restore`,
    { method: 'POST', ...(options || {}) },
  );
}

/** POST /api/dataset-versions/{draftVersionId}/packages/init */
export async function draftPackageUploadInit(
  draftVersionId: string,
  body: {
    fileName: string;
    fileSize: number;
    fileFingerprint?: string;
    sampleGrouping?: MultimodalSampleGrouping;
    manifestPath?: string;
  },
  options?: { [key: string]: unknown },
) {
  return request<{ data: DatasetUploadProgress }>(
    `/dataset-versions/${encodeURIComponent(draftVersionId)}/packages/init`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

/** POST /api/dataset-versions/{draftVersionId}/packages/complete */
export async function draftPackageUploadComplete(
  draftVersionId: string,
  uploadId: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DraftPackageCompleteResult }>(
    `/dataset-versions/${encodeURIComponent(draftVersionId)}/packages/complete`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: { uploadId },
      ...(options || {}),
    },
  );
}

/** 追加上传 ZIP 到 DRAFT 工作区（init → chunk → complete） */
export async function uploadDraftAppendPackage(
  draftVersionId: string,
  file: File,
  params?: {
    sampleGrouping?: MultimodalSampleGrouping;
    manifestPath?: string;
    fileFingerprint?: string;
    onProgress?: (percent: number) => void;
  },
  options?: { [key: string]: unknown },
) {
  const fp =
    params?.fileFingerprint ||
    [file.name, String(file.size), draftVersionId, 'append'].join('|');
  const initBody: {
    fileName: string;
    fileSize: number;
    fileFingerprint?: string;
    sampleGrouping?: MultimodalSampleGrouping;
    manifestPath?: string;
  } = {
    fileName: file.name,
    fileSize: file.size,
    fileFingerprint: fp,
  };
  if (params?.sampleGrouping) {
    initBody.sampleGrouping = params.sampleGrouping;
    if (params?.manifestPath) {
      initBody.manifestPath = params.manifestPath;
    }
  }
  const initRes = await draftPackageUploadInit(
    draftVersionId,
    initBody,
    options,
  );
  const progress = initRes?.data;
  const uploadId = progress?.uploadId;
  if (!uploadId) {
    throw new Error('初始化追加包上传失败');
  }
  const chunkSize =
    progress.chunkSize > 0 ? progress.chunkSize : DEFAULT_CHUNK;
  const totalChunks =
    progress.totalChunks > 0
      ? progress.totalChunks
      : Math.max(1, Math.ceil(file.size / chunkSize));
  const done = new Set(progress.uploadedPartIndexes ?? []);
  let uploadedCount = done.size;
  for (let partIndex = 0; partIndex < totalChunks; partIndex += 1) {
    if (done.has(partIndex)) {
      continue;
    }
    const start = partIndex * chunkSize;
    const end = Math.min(start + chunkSize, file.size);
    await datasetUploadChunk(uploadId, partIndex, file.slice(start, end), options);
    uploadedCount += 1;
    params?.onProgress?.(
      Math.min(100, Math.round((uploadedCount / totalChunks) * 100)),
    );
  }
  params?.onProgress?.(100);
  return draftPackageUploadComplete(draftVersionId, uploadId, options);
}
