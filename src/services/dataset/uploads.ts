/** 上传流程：初始化、缺片续传、完成和查询对账；不重复不确定的写请求。 */
import { request } from '@umijs/max';
import { isLegacyEndpointUnavailable } from '@/utils/apiCompatibility.mjs';
import { mayReconcileDatasetUpload, normalizeDatasetUploadComplete, normalizeDatasetUploadProgress } from '../datasetUploadResponse';
import type { DatasetUploadInitParams, DatasetUploadProgress, DatasetUploadCompleteResult, DatasetFolderUploadParams, UploadDatasetCompatParams } from './types';

// ——— 分片上传 ———

/** 分片 init/chunk/complete 及合并阶段可能较慢，需长于全局 10s */
const DATASET_UPLOAD_REQUEST_TIMEOUT = 5 * 60 * 1000;

function withDatasetUploadRequestOptions(options?: { [key: string]: unknown }) {
  return {
    ...(options || {}),
    timeout: DATASET_UPLOAD_REQUEST_TIMEOUT,
  };
}

/**
 * 初始化或恢复数据集分片上传。
 * 优先 V2；只有明确不支持端点时兼容 Legacy，不能重放结果不明的写请求。
 */
export async function datasetUploadInit(
  body: DatasetUploadInitParams,
  options?: { [key: string]: unknown },
) {
  try {
    const raw = await request<unknown>('/v2/dataset-uploads/init', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
      ...withDatasetUploadRequestOptions(options),
    });
    return { data: normalizeDatasetUploadProgress(raw) };
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
  }
  const raw = await request<unknown>('/dataset/upload/init', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...withDatasetUploadRequestOptions(options),
  });
  return { data: normalizeDatasetUploadProgress(raw) };
}

/**
 * 上传数据集的单个分片。
 * 优先 V2 chunks，仅端点不支持时兼容 Legacy（form 含 uploadId）。
 */
export async function datasetUploadChunk(
  uploadId: string,
  partIndex: number,
  chunk: Blob,
  options?: { [key: string]: unknown },
) {
  const formData = new FormData();
  formData.append('partIndex', String(partIndex));
  formData.append('file', chunk);
  try {
    const raw = await request<unknown>(
      `/v2/dataset-uploads/${encodeURIComponent(uploadId)}/chunks`,
      {
        method: 'POST',
        data: formData,
        skipErrorHandler: true,
        ...withDatasetUploadRequestOptions(options),
      },
    );
    return { data: normalizeDatasetUploadProgress(raw, uploadId) };
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
  }
  formData.append('uploadId', uploadId);
  const raw = await request<unknown>('/dataset/upload/chunk', {
    method: 'POST',
    data: formData,
    ...withDatasetUploadRequestOptions(options),
  });
  return { data: normalizeDatasetUploadProgress(raw, uploadId) };
}

/** 查询数据集上传进度，用于刷新后恢复断点续传。 */
export async function datasetUploadProgress(
  uploadId: string,
  options?: { [key: string]: unknown },
) {
  try {
    const raw = await request<unknown>(
      `/v2/dataset-uploads/${encodeURIComponent(uploadId)}`,
      {
        method: 'GET',
        skipErrorHandler: true,
        ...withDatasetUploadRequestOptions(options),
      },
    );
    return { data: normalizeDatasetUploadProgress(raw, uploadId) };
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
  }
  const raw = await request<unknown>('/dataset/upload/progress', {
    method: 'GET',
    params: { uploadId },
    ...withDatasetUploadRequestOptions(options),
  });
  return { data: normalizeDatasetUploadProgress(raw, uploadId) };
}

/** 完成分片合并；COMPLETED 只表示上传完成，导入和版本可用状态另查。 */
export async function datasetUploadComplete(
  uploadId: string,
  options?: { [key: string]: unknown },
) {
  try {
    const raw = await request<unknown>(
      `/v2/dataset-uploads/${encodeURIComponent(uploadId)}/complete`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        data: {},
        skipErrorHandler: true,
        ...withDatasetUploadRequestOptions(options),
      },
    );
    return { data: normalizeDatasetUploadComplete(raw, uploadId) };
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
  }
  const raw = await request<unknown>('/dataset/upload/complete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { uploadId },
    ...withDatasetUploadRequestOptions(options),
  });
  return { data: normalizeDatasetUploadComplete(raw, uploadId) };
}

const UPLOAD_PROGRESS_POLL_MS = 2000;

const UPLOAD_PROGRESS_POLL_MAX_MS = 5 * 60 * 1000;

function sleep(ms: number) {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms);
  });
}

export function calcUploadPercent(progress: DatasetUploadProgress): number {
  const total = progress.totalChunks > 0 ? progress.totalChunks : 1;
  const done =
    progress.uploadedPartIndexes?.length ?? progress.uploadedChunks ?? 0;
  return Math.min(100, Math.round((done / total) * 100));
}

/** 轮询 progress，直到离开 COMPLETING（或超时） */
export async function waitDatasetUploadSettled(
  uploadId: string,
  options?: { [key: string]: unknown },
  onPoll?: (progress: DatasetUploadProgress) => void,
): Promise<DatasetUploadProgress> {
  const started = Date.now();
  while (Date.now() - started < UPLOAD_PROGRESS_POLL_MAX_MS) {
    const res = await datasetUploadProgress(uploadId, options);
    const data = res?.data;
    if (!data) {
      throw new Error('查询上传进度失败');
    }
    onPoll?.(data);
    if (data.status === 'COMPLETED') {
      return data;
    }
    if (data.status !== 'COMPLETING') {
      return data;
    }
    await sleep(UPLOAD_PROGRESS_POLL_MS);
  }
  throw new Error('服务端合并分片超时，请稍后刷新页面或重新提交');
}

/**
 * 完成上传并在 COMPLETING 时轮询 progress，避免并发重复 complete。
 * 即使后端支持幂等，页面也不自动重放结果不明的 complete，只查询进度。
 */
export async function datasetUploadCompleteWithPolling(
  uploadId: string,
  options?: { [key: string]: unknown },
  callbacks?: { onPoll?: (progress: DatasetUploadProgress) => void },
) {
  let result: { data: DatasetUploadCompleteResult } | undefined;
  try {
    result = await datasetUploadComplete(uploadId, options);
  } catch (error) {
    if (!mayReconcileDatasetUpload(error)) throw error;
  }
  if (result?.data.status === 'COMPLETED') return result;
  if (result && result.data.status !== 'COMPLETING')
    throw new Error('上传未完成，请检查上传状态后再操作');
  // complete 的结果不确定时只查询；禁止把一次页面操作变成第二次隐式写请求。
  const progress = await waitDatasetUploadSettled(
    uploadId,
    options,
    callbacks?.onPoll,
  );
  if (progress.status !== 'COMPLETED')
    throw new Error(`上传未完成（${progress.status}），请检查后再操作`);
  return { data: normalizeDatasetUploadComplete(progress, uploadId) };
}

/**
 * 上传 CV 图片文件夹。
 *
 * paths 必须与 files 一一对应，且只能是相对路径；后端会拒绝绝对路径、盘符和 `..`。
 */
export async function datasetUploadFolder(
  body: DatasetFolderUploadParams,
  options?: { [key: string]: unknown },
) {
  const formData = new FormData();
  formData.append('datasetName', body.datasetName);
  formData.append('version', body.version || 'v1.0.0');
  formData.append('type', body.type);
  if (body.cvTaskType) {
    formData.append('cvTaskType', body.cvTaskType);
  }
  if (body.annotationFormat) {
    formData.append('annotationFormat', body.annotationFormat);
  }
  if (body.remark) {
    formData.append('remark', body.remark);
  }
  body.files.forEach((file, index) => {
    const relativePath = body.paths[index] || file.name;
    formData.append('files', file, file.name);
    formData.append('paths', relativePath);
  });
  return request<{ data: DatasetUploadCompleteResult }>(
    '/dataset/upload/folder',
    {
      method: 'POST',
      data: formData,
      ...withDatasetUploadRequestOptions(options),
    },
  );
}

// ——— 兼容旧 platform / 页面 ———

const DEFAULT_CHUNK = 5 * 1024 * 1024;

/**
 * 兼容旧「多文件直传」：单文件走分片上传；多文件且 CV 走文件夹打包接口；
 * NLP 多文件请让用户打包为 zip 后单文件上传。
 */
export async function uploadDataset(
  params: UploadDatasetCompatParams,
  options?: { [key: string]: unknown },
) {
  const {
    name,
    files,
    type = 'CV',
    version = 'v1',
    assetId,
    cvTaskType,
    annotationFormat,
    remark,
    sampleGrouping,
    manifestPath,
    strictManifest,
    fileFingerprint,
    onProgress,
    onMergeStatus,
    onUploadSession,
  } = params;
  if (!files?.length) {
    throw new Error('请选择要上传的文件');
  }
  if (files.length === 1) {
    const file = files[0];
    const fp =
      fileFingerprint ||
      [
        file.name,
        String(file.size),
        name,
        version || 'v1',
        type,
        annotationFormat || '',
        cvTaskType || '',
      ].join('|');
    const initBody: DatasetUploadInitParams = {
      fileName: file.name,
      fileSize: file.size,
      fileFingerprint: fp,
      datasetName: name,
      version,
      versionLabel: version,
      type,
      cvTaskType,
      annotationFormat,
      remark,
      description: remark,
    };
    if (assetId) {
      initBody.assetId = assetId;
    }
    if (type === 'MULTIMODAL') {
      initBody.sampleGrouping = sampleGrouping ?? 'AUTO_DIRECTORY';
      if (initBody.sampleGrouping === 'MANIFEST' && manifestPath?.trim()) {
        initBody.manifestPath = manifestPath.trim();
      }
      if (initBody.sampleGrouping === 'MANIFEST' && strictManifest === true) {
        initBody.strictManifest = true;
      }
    }
    const initRes = await datasetUploadInit(initBody, options);
    const progress = initRes?.data;
    const uploadId = progress?.uploadId;
    if (!uploadId) {
      throw new Error('初始化数据集上传失败');
    }
    onUploadSession?.({ uploadId, fileFingerprint: fp });
    const chunkSize =
      progress.chunkSize > 0 ? progress.chunkSize : DEFAULT_CHUNK;
    const totalChunks =
      progress.totalChunks > 0
        ? progress.totalChunks
        : Math.max(1, Math.ceil(file.size / chunkSize));
    const done = new Set(progress.uploadedPartIndexes ?? []);
    let uploadedCount = done.size;
    for (let partIndex = 0; partIndex < totalChunks; partIndex++) {
      if (done.has(partIndex)) {
        continue;
      }
      const start = partIndex * chunkSize;
      const end = Math.min(start + chunkSize, file.size);
      await datasetUploadChunk(
        uploadId,
        partIndex,
        file.slice(start, end),
        options,
      );
      uploadedCount += 1;
      onProgress?.(
        Math.min(100, Math.round((uploadedCount / totalChunks) * 100)),
      );
    }
    onProgress?.(100);
    onMergeStatus?.('COMPLETING');
    return datasetUploadCompleteWithPolling(uploadId, options, {
      onPoll: (progress) => {
        if (progress.status === 'COMPLETING') {
          onMergeStatus?.('COMPLETING');
          onProgress?.(100);
        } else {
          onProgress?.(calcUploadPercent(progress));
        }
      },
    });
  }
  if (type === 'CV') {
    return datasetUploadFolder(
      {
        datasetName: name,
        version,
        type: 'CV',
        cvTaskType,
        annotationFormat,
        remark,
        files,
        paths: files.map((f) => f.webkitRelativePath || f.name),
      },
      options,
    );
  }
  throw new Error('NLP 数据集请将多个文件打包为 zip 后作为单个文件上传');
}
