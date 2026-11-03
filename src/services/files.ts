import { request } from '@umijs/max';
import { FILE_DOWNLOAD_REQUEST_TIMEOUT } from '@/constants/request';
import { downloadAuthFile } from '@/utils/authFileDownload';

/**
 * 通用文件对象服务。
 *
 * 对接后端 `/api/files/**`，用于直接操作 MinIO 中的单个对象。
 * 注意：大模型/大数据集上传请优先使用 model.ts 或 dataset.ts 里的分片上传接口，
 * 这里的 uploadObject 更适合小文件、附件或调试场景。
 */

/**
 * 检查后端与 MinIO 的连通性。
 *
 * 联调时如果上传/下载失败，可以先调用该接口确认对象存储服务是否可用。
 */
export async function fileHealth(options?: { [key: string]: any }) {
  return request<{ data: { minio: string } }>('/files/health', {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 直接上传单个文件对象到 MinIO。
 *
 * objectName 是 MinIO 对象路径；不传时后端会使用原始文件名。
 * 该接口受单次 multipart 请求大小限制，不适合 10GB 级文件。
 */
export async function uploadObject(
  file: File,
  objectName?: string,
  options?: { [key: string]: any },
) {
  const formData = new FormData();
  formData.append('file', file);
  if (objectName) {
    formData.append('objectName', objectName);
  }
  return request<{ data: { objectName: string; size: number; etag: string } }>('/files/upload', {
    method: 'POST',
    data: formData,
    ...(options || {}),
  });
}

/**
 * 生成文件下载地址。
 *
 * 下载接口返回二进制流，不走通用 ApiResponse 包装；前端可用 window.open(url)
 * 或创建 a 标签触发下载。
 */
export function getDownloadUrl(objectName: string) {
  return `/api/files/download?objectName=${encodeURIComponent(objectName)}`;
}

export async function downloadObject(objectName: string, options?: { [key: string]: any }) {
  return request<Blob>('/files/download', {
    method: 'GET',
    params: { objectName },
    responseType: 'blob',
    skipErrorHandler: true,
    timeout: FILE_DOWNLOAD_REQUEST_TIMEOUT,
    ...(options || {}),
  });
}

/** 由浏览器下载管理器保存对象；文件内容不进入前端内存。 */
export async function downloadObjectWithBrowser(
  objectName: string,
  fileName?: string,
) {
  return downloadAuthFile({
    url: getDownloadUrl(objectName),
    fileName: fileName?.trim() || objectName.split('/').pop() || 'download',
  });
}

/**
 * 删除 MinIO 中的单个对象。
 *
 * 只删除指定 objectName 对应的文件对象；如果需要删除模型/数据集资产，
 * 应优先调用对应业务删除接口，确保数据库记录和 MinIO 文件一起清理。
 */
export async function deleteObject(objectName: string, options?: { [key: string]: any }) {
  return request<{ data: { objectName: string; deleted: boolean } }>('/files/delete', {
    method: 'DELETE',
    params: { objectName },
    ...(options || {}),
  });
}
