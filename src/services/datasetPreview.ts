import { request } from '@umijs/max';
import { FILE_DOWNLOAD_REQUEST_TIMEOUT } from '@/constants/request';
import type { TaskType } from './dataset';

/**
 * 普通数据集预览服务（module2-api-doc §13）。
 *
 * 对接 `/dataset/preview/**`（全局 baseURL 已配置 `/api`）。
 * 仅用于 CV / NLP；点云仍走 pointcloud.ts。
 */

const PREVIEW_REQUEST_TIMEOUT = FILE_DOWNLOAD_REQUEST_TIMEOUT;

export enum DatasetPreviewFileKind {
  IMAGE = 'IMAGE',
  TEXT = 'TEXT',
  TABLE = 'TABLE',
  UNSUPPORTED = 'UNSUPPORTED',
}

export type DatasetPreviewApiResponse<T> = {
  success?: boolean;
  data?: T;
  errorMessage?: string | null;
};

export type DatasetPreviewFileItem = {
  path: string | null;
  fileName: string;
  extension?: string;
  kind: DatasetPreviewFileKind;
  sizeBytes?: number | null;
  previewAllowed?: boolean;
  previewUrl?: string | null;
  message?: string | null;
  /** 样本数据预览（package/APPEND 版本）：有值时走 sample-data 接口而非 zip path */
  sampleDataId?: string;
  source?: 'zip' | 'sample';
};

export type DatasetPreviewFilesQuery = {
  page?: number;
  pageSize?: number;
  keyword?: string;
  kind?: DatasetPreviewFileKind;
};

export type DatasetPreviewFilesData = {
  datasetVersionId: string;
  type: Extract<TaskType, 'CV' | 'NLP' | 'LEROBOT'> | 'CV' | 'NLP' | 'LEROBOT';
  fileName: string;
  sourceArchive?: boolean;
  page: number;
  pageSize: number;
  total: number;
  files: DatasetPreviewFileItem[];
};

export type DatasetPreviewContentType =
  | 'TEXT'
  | 'CSV'
  | 'JSONL'
  | 'JSON'
  | 'XML'
  | 'MARKDOWN'
  | string;

export type DatasetPreviewContentData = {
  path?: string | null;
  fileName: string;
  extension?: string;
  contentType: DatasetPreviewContentType;
  content?: string | null;
  columns?: string[] | null;
  rows?: string[][] | null;
  page?: number;
  pageSize?: number | null;
  /** 是否支持分页；为 true 时通常附带 total / totalPages */
  pageable?: boolean;
  /** 可预览范围内的数据行总数（不含表头） */
  total?: number | null;
  totalPages?: number | null;
  truncated?: boolean;
  message?: string | null;
};

export type DatasetPreviewContentQuery = {
  path?: string | null;
  page?: number;
  pageSize?: number;
};

function unwrapPreviewResponse<T>(res?: DatasetPreviewApiResponse<T>): T {
  if (!res) {
    throw new Error('响应为空');
  }
  if (res.success === false) {
    throw new Error(res.errorMessage || '请求失败');
  }
  if (res.data === undefined || res.data === null) {
    throw new Error(res.errorMessage || '响应数据为空');
  }
  return res.data;
}

/** GET /dataset/preview/files */
export async function getDatasetPreviewFiles(
  datasetVersionId: string,
  query?: DatasetPreviewFilesQuery,
  options?: { [key: string]: unknown },
) {
  return request<DatasetPreviewApiResponse<DatasetPreviewFilesData>>(
    '/dataset/preview/files',
    {
      method: 'GET',
      params: {
        id: datasetVersionId,
        page: query?.page ?? 1,
        pageSize: query?.pageSize ?? 100,
        keyword: query?.keyword || undefined,
        kind: query?.kind || undefined,
      },
      timeout: PREVIEW_REQUEST_TIMEOUT,
      ...(options || {}),
    },
  );
}

/** GET /dataset/preview/content */
export async function getDatasetPreviewContent(
  datasetVersionId: string,
  query?: DatasetPreviewContentQuery,
  options?: { [key: string]: unknown },
) {
  return request<DatasetPreviewApiResponse<DatasetPreviewContentData>>(
    '/dataset/preview/content',
    {
      method: 'GET',
      params: {
        id: datasetVersionId,
        path: query?.path ?? undefined,
        page: query?.page ?? undefined,
        pageSize: query?.pageSize ?? undefined,
      },
      timeout: PREVIEW_REQUEST_TIMEOUT,
      ...(options || {}),
    },
  );
}

/** GET /dataset/preview/image */
export async function getDatasetPreviewImage(
  datasetVersionId: string,
  zipEntryPath?: string | null,
  options?: { [key: string]: unknown },
) {
  return request<Blob>('/dataset/preview/image', {
    method: 'GET',
    params: {
      id: datasetVersionId,
      path: zipEntryPath ?? undefined,
    },
    responseType: 'blob',
    timeout: PREVIEW_REQUEST_TIMEOUT,
    ...(options || {}),
  });
}

export async function fetchDatasetPreviewFiles(
  datasetVersionId: string,
  query?: DatasetPreviewFilesQuery,
  options?: { [key: string]: unknown },
): Promise<DatasetPreviewFilesData> {
  const res = await getDatasetPreviewFiles(datasetVersionId, query, options);
  return unwrapPreviewResponse(res);
}

export async function fetchDatasetPreviewContent(
  datasetVersionId: string,
  query?: DatasetPreviewContentQuery,
  options?: { [key: string]: unknown },
): Promise<DatasetPreviewContentData> {
  const res = await getDatasetPreviewContent(datasetVersionId, query, options);
  return unwrapPreviewResponse(res);
}
