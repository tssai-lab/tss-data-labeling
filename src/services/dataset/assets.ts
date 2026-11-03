/** 资产和版本请求：只透传原接口，权限、引用和清理由后端判定。 */
import { request } from '@umijs/max';
import { downloadAuthFile } from '@/utils/authFileDownload';
import type { DatasetAsset, DatasetVersion, DatasetDeleteResult, DatasetVersionLifecycleStatus } from './types';

/** 创建数据集资产记录。通常上传完成接口会自动创建，手动维护时才需要直接调用。 */
export async function createDatasetAsset(
  body: Partial<DatasetAsset>,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DatasetAsset }>('/dataset-assets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}

/** 查询全部数据集资产记录。 */
export async function listDatasetAssets(options?: { [key: string]: unknown }) {
  return request<{ data: DatasetAsset[] }>('/dataset-assets', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 查询单个数据集资产详情。 */
export async function getDatasetAsset(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DatasetAsset }>(
    '/dataset-assets/' + encodeURIComponent(id),
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** 删除资产及其版本；权限、引用校验和存储清理由后端处理。 */
export async function deleteDatasetAsset(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DatasetDeleteResult }>(
    '/dataset-assets/' + encodeURIComponent(id),
    {
      method: 'DELETE',
      ...(options || {}),
    },
  );
}

/** 创建数据集版本记录。通常上传完成接口会自动创建，手动维护时才需要直接调用。 */
export async function createDatasetVersion(
  body: Partial<DatasetVersion>,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DatasetVersion }>('/dataset-versions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}

/** 查询数据集版本列表；传 assetId 时只返回指定资产下的版本。 */
export async function listDatasetVersions(
  assetId?: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DatasetVersion[] }>('/dataset-versions', {
    method: 'GET',
    params: assetId ? { assetId } : undefined,
    ...(options || {}),
  });
}

/** 查询单个数据集版本详情。 */
export async function getDatasetVersion(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: DatasetVersion }>(
    '/dataset-versions/' + encodeURIComponent(id),
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** 更新数据集版本元数据（versionLabel/remark/description 等）。 */
export async function updateDatasetVersion(
  id: string,
  body: Partial<
    Pick<DatasetVersion, 'version' | 'remark' | 'assetId'> & {
      versionLabel?: string;
      description?: string;
      changeLog?: string;
    }
  >,
  options?: { [key: string]: unknown },
) {
  const payload = { ...body };
  if (payload.version && !payload.versionLabel) {
    payload.versionLabel = payload.version;
  }
  return request<{ data: DatasetVersion }>(
    '/dataset-versions/' + encodeURIComponent(id),
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      data: payload,
      ...(options || {}),
    },
  );
}

/** PUT /api/dataset-assets/{assetId}/current-version */
export async function switchDatasetCurrentVersion(
  assetId: string,
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<{ success?: boolean; data?: unknown }>(
    `/dataset-assets/${encodeURIComponent(assetId)}/current-version`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      data: { versionId },
      ...(options || {}),
    },
  );
}

/** PATCH /api/dataset-versions/{id}/status */
export async function updateDatasetVersionStatus(
  versionId: string,
  status: DatasetVersionLifecycleStatus,
  options?: { [key: string]: unknown },
) {
  return request<{ success?: boolean; data?: DatasetVersion }>(
    `/dataset-versions/${encodeURIComponent(versionId)}/status`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      data: { status },
      ...(options || {}),
    },
  );
}

/** 删除数据集版本。若被训练实验引用会失败。 */
export async function deleteDatasetVersion(
  id: string,
  options?: { [key: string]: unknown },
) {
  return request<{ data: unknown }>(
    '/dataset-versions/' + encodeURIComponent(id),
    {
      method: 'DELETE',
      ...(options || {}),
    },
  );
}

/**
 * GET /api/dataset-versions/{versionId}/download
 * 带鉴权拉取版本 ZIP；Chromium 安全上下文先弹保存框再流式写入。
 */
export async function downloadDatasetVersion(
  versionId: string,
  fileName?: string,
  options?: {
    onProgress?: (ratio: number | null) => void;
    [key: string]: unknown;
  },
) {
  await downloadAuthFile({
    url: `/dataset-versions/${encodeURIComponent(versionId)}/download`,
    fileName: fileName?.trim() || `${versionId}.zip`,
    onProgress: options?.onProgress,
  });
  return { success: true };
}

/** 删除数据集资产（兼容旧 `deleteDataset`） */
export async function deleteDataset(
  id: string,
  options?: { [key: string]: unknown },
) {
  return deleteDatasetAsset(id, options);
}
