import { request } from '@umijs/max';
import { FILE_DOWNLOAD_REQUEST_TIMEOUT } from '@/constants/request';
import type { TaskType } from './dataset';

/**
 * 点云三维预览服务（module2-api-doc 第 15 章）。
 *
 * 基础路径：/dataset/point-cloud（全局 baseURL 已配置 /api）。
 * 渲染由前端 Three.js、PCDLoader、PLYLoader 完成；
 * 后端提供鉴权、预览元信息与点云文件流，不依赖 storagePath。
 * 文件流须使用 preview 接口返回的 previewUrl，不自行拼接路径。
 */

/** 点云接口请求超时（覆盖 app.tsx 全局 10s，大文件拉流需更长时间） */
const POINT_CLOUD_REQUEST_TIMEOUT = FILE_DOWNLOAD_REQUEST_TIMEOUT;

/** 单文件点云格式 */
export enum PointCloudFileFormat {
  PCD = 'PCD',
  PLY = 'PLY',
}

/** 预览元信息中的格式（含 zip 包） */
export enum PointCloudPreviewFormat {
  PCD = 'PCD',
  PLY = 'PLY',
  ZIP = 'ZIP',
}

/** 点云 preview 接口统一响应：{ success, data, errorMessage } */
export type PointCloudApiResponse<T> = {
  success: boolean;
  data: T;
  errorMessage?: string | null;
};

/** zip 包内点云文件项（pointCloudFiles[]） */
export type PointCloudZipEntry = {
  path: string;
  fileName: string;
  format: PointCloudFileFormat;
  sizeBytes?: number | null;
  previewUrl?: string | null;
  previewAllowed?: boolean;
  message?: string | null;
};

/** GET /dataset/point-cloud/preview 响应 data */
export type PointCloudPreviewInfo = {
  datasetVersionId: string;
  fileName: string;
  type: Extract<TaskType, 'POINT_CLOUD'>;
  format: PointCloudPreviewFormat;
  sizeBytes?: number | null;
  previewSupported?: boolean;
  previewUrl?: string | null;
  pointCloudFiles?: PointCloudZipEntry[] | null;
  message?: string | null;
};

function unwrapPointCloudResponse<T>(res: PointCloudApiResponse<T>): T {
  if (!res.success) {
    throw new Error(res.errorMessage || '请求失败');
  }
  if (res.data == null) {
    throw new Error(res.errorMessage || '响应数据为空');
  }
  return res.data;
}

/** preview 接口返回的 previewUrl 转为 umi request 路径（全局 baseURL 为 /api） */
function normalizePointCloudPreviewUrl(previewUrl: string): string {
  const trimmed = previewUrl.trim();
  if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
    return trimmed;
  }
  if (trimmed.startsWith('/api/')) {
    return trimmed.slice('/api'.length);
  }
  if (trimmed.startsWith('/api')) {
    return trimmed.slice(4) || '/';
  }
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
}

/**
 * 15.1 查询点云预览信息。
 *
 * GET /dataset/point-cloud/preview?id={datasetVersionId}
 */
export async function getPointCloudPreview(
  datasetVersionId: string,
  options?: { [key: string]: any },
) {
  return request<PointCloudApiResponse<PointCloudPreviewInfo>>(
    '/dataset/point-cloud/preview',
    {
      method: 'GET',
      params: { id: datasetVersionId },
      timeout: POINT_CLOUD_REQUEST_TIMEOUT,
      ...(options || {}),
    },
  );
}

/**
 * 15.2 / 15.3 按 preview 元信息返回的 previewUrl 拉取点云文件流。
 */
export async function fetchPointCloudPreviewBlob(
  previewUrl: string,
  options?: { [key: string]: any },
): Promise<Blob> {
  if (!previewUrl?.trim()) {
    throw new Error('缺少 previewUrl，无法加载点云');
  }
  return request<Blob>(normalizePointCloudPreviewUrl(previewUrl), {
    method: 'GET',
    responseType: 'blob',
    timeout: POINT_CLOUD_REQUEST_TIMEOUT,
    ...(options || {}),
  });
}

/** 文件流 blob 转 ArrayBuffer；若 Content-Type 为 JSON 则解析后端错误信息 */
export async function pointCloudBlobToArrayBuffer(
  blob: Blob,
): Promise<ArrayBuffer> {
  if (blob.type.includes('json')) {
    const text = await blob.text();
    try {
      const json = JSON.parse(text) as {
        errorMessage?: string | null;
        message?: string | null;
      };
      throw new Error(
        json.errorMessage || json.message || '点云文件读取失败',
      );
    } catch (e) {
      if (e instanceof Error && !e.message.startsWith('Unexpected')) {
        throw e;
      }
      throw new Error('点云文件读取失败');
    }
  }
  return blob.arrayBuffer();
}

/** 查询点云预览信息并返回 data（页面层便捷方法） */
export async function fetchPointCloudPreviewInfo(
  datasetVersionId: string,
  options?: { [key: string]: any },
): Promise<PointCloudPreviewInfo> {
  const res = await getPointCloudPreview(datasetVersionId, options);
  return unwrapPointCloudResponse(res);
}
