/** 列表和详情聚合：主体来自资产/版本接口，列表只补历史元数据。 */
import { request } from '@umijs/max';
import { getV2DatasetList, mapV2DatasetToListItem, type V2DatasetListPage } from '../datasetV2';
import { collectPaginatedCandidates } from '../paginatedCandidates.mjs';
import { getDatasetAsset, listDatasetVersions } from './assets';
import type { DatasetType, DatasetVersion, DatasetListItem, DatasetListQuery } from './types';

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

/** 从版本记录解析数据集版本 ID（预览/训练必须用版本 ID，不能用资产 ID） */
export function resolveDatasetVersionId(
  version?: Partial<DatasetVersion> | null,
  assetId?: string,
): string | undefined {
  if (!version) {
    return undefined;
  }
  const extra = version as DatasetVersion & {
    datasetVersionId?: string;
    versionId?: string;
  };
  const candidates = [
    version.id,
    extra.datasetVersionId,
    extra.versionId,
  ].filter((v): v is string => typeof v === 'string' && v.length > 0);

  for (const candidate of candidates) {
    if (assetId && candidate === assetId) {
      continue;
    }
    return candidate;
  }
  return undefined;
}

function normalizeDatasetVersionList(raw: unknown): DatasetVersion[] {
  if (Array.isArray(raw)) {
    return raw as DatasetVersion[];
  }
  if (raw && typeof raw === 'object') {
    const obj = raw as { data?: unknown; list?: unknown; records?: unknown };
    if (Array.isArray(obj.data)) {
      return obj.data as DatasetVersion[];
    }
    if (Array.isArray(obj.list)) {
      return obj.list as DatasetVersion[];
    }
    if (Array.isArray(obj.records)) {
      return obj.records as DatasetVersion[];
    }
  }
  return [];
}

function mapDatasetVersion(
  version: DatasetVersion,
  assetId?: string,
): API.DatasetVersionDetail {
  const versionId = resolveDatasetVersionId(version, assetId) ?? version.id;
  return {
    ...version,
    id: versionId,
    size: formatBytes(version.sizeBytes),
    status: version.status,
  };
}

/** 获取数据集列表页聚合数据，可按 keyword、类型、分页筛选。 */
export async function getDatasetList(
  params?: DatasetListQuery,
  options?: { [key: string]: unknown },
) {
  return request<{ data: { data: DatasetListItem[]; total: number } }>(
    '/dataset/list',
    {
      method: 'GET',
      params,
      ...(options || {}),
    },
  );
}

function normalizeV2ListPage(raw: unknown): V2DatasetListPage | null {
  if (!raw || typeof raw !== 'object') {
    return null;
  }
  const page = raw as V2DatasetListPage;
  if (Array.isArray(page.data)) {
    return page;
  }
  const wrapped = raw as { data?: V2DatasetListPage };
  const inner = wrapped.data;
  if (inner && Array.isArray(inner.data)) {
    return inner;
  }
  return null;
}

/** 获取数据集列表：优先 V2（无 storagePath），V1 仅补文件名/大小等展示字段 */
export async function fetchDatasetList(options?: {
  current?: number;
  pageSize?: number;
  name?: string;
  type?: string;
  sortBy?: string;
  sortDirection?: 'ASC' | 'DESC';
}) {
  const params: DatasetListQuery & {
    sortBy?: string;
    sortDirection?: string;
  } = {};

  if (options?.type) {
    params.type = options.type as DatasetListQuery['type'];
  }

  const keyword = options?.name?.trim();
  if (keyword) {
    params.keyword = keyword;
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

  const [v2Result, v1Result] = await Promise.allSettled([
    getV2DatasetList(params, options),
    getDatasetList(params, options),
  ]);

  let list: DatasetListItem[] = [];
  let total = 0;

  if (v2Result.status === 'fulfilled') {
    const v2Page = normalizeV2ListPage(v2Result.value);
    if (v2Page) {
      list = v2Page.data.map(mapV2DatasetToListItem);
      total = v2Page.total ?? list.length;
    }
  }

  if (v1Result.status === 'fulfilled') {
    const inner = v1Result.value?.data;
    const v1List: DatasetListItem[] = inner?.data ?? [];
    if (list.length && v1List.length) {
      // V2 为主，用 V1 补 size/fileName/versionRemark（不依赖 V1 storagePath）
      const v1ById = new Map<string | undefined, DatasetListItem>(
        v1List.map((item: DatasetListItem) => [item.assetId || item.id, item]),
      );
      list = list.map((item) => {
        const v1 = v1ById.get(item.assetId || item.id);
        if (!v1) return item;
        // V2 常无 sizeBytes，formatBytes 会得到 '-'；'-' 为真值会挡住 V1 补全
        const sizeMissing =
          item.sizeBytes == null ||
          item.size == null ||
          item.size === '' ||
          item.size === '-';
        return {
          ...item,
          fileName: item.fileName || v1.fileName,
          size: sizeMissing ? v1.size || item.size : item.size,
          sizeBytes: item.sizeBytes ?? v1.sizeBytes,
          versionRemark: item.versionRemark || v1.versionRemark,
          uploadTime: item.uploadTime || v1.uploadTime,
          latestDraftVersionId:
            item.latestDraftVersionId ?? v1.latestDraftVersionId,
          importJobId: item.importJobId ?? v1.importJobId,
        };
      });
    } else if (!list.length) {
      list = v1List;
      total = inner?.total ?? list.length;
    }
  }

  if (list.length) {
    return { data: list, total };
  }

  if (v2Result.status === 'rejected' && v1Result.status === 'rejected') {
    throw v2Result.reason;
  }

  return { data: [], total: 0 };
}

/** V2 训练方案候选：不回退未按规格筛选的旧接口，并取回全部服务端分页。 */
export async function fetchTrainingDatasetCandidates(
  artifactSpecIds: string[],
) {
  const normalizedSpecIds = [
    ...new Set(artifactSpecIds.map((value) => value.trim())),
  ].filter(Boolean);
  if (!normalizedSpecIds.length) return { data: [], total: 0 };
  return collectPaginatedCandidates<DatasetListItem>(
    async (current, pageSize) => {
      const raw = await getV2DatasetList({
        current,
        pageSize,
        artifactSpecIds: normalizedSpecIds.join(','),
      });
      const page = normalizeV2ListPage(raw);
      if (!page) throw new Error('数据集候选列表响应格式无效');
      return {
        data: page.data.map(mapV2DatasetToListItem),
        total: page.total,
      };
    },
    { keyOf: (item) => item.versionId, pageSize: 200 },
  );
}

export async function fetchAllDatasetList() {
  return collectPaginatedCandidates<DatasetListItem>(
    (current, pageSize) => fetchDatasetList({ current, pageSize }),
    { keyOf: (item) => item.versionId, pageSize: 200 },
  );
}

/** 主体与版本保持原入口；草稿/导入信息按资产编号查询。 */
export async function fetchDatasetDetail(
  id: string,
  options?: { [key: string]: unknown },
) {
  const [assetRes, versionRes] = await Promise.all([
    getDatasetAsset(id, options),
    listDatasetVersions(id, options),
  ]);
  const asset = assetRes?.data;
  if (!asset) {
    return { data: undefined };
  }
  const summary = await request<{
    success: boolean;
    data?: DatasetListItem;
    errorMessage?: string;
  }>(`/dataset/${encodeURIComponent(id)}/summary`, { method: 'GET', ...options });
  const row = summary?.data;
  if (summary?.success === false || !row || (row.assetId || row.id) !== asset.id) {
    throw new Error(summary?.errorMessage || '数据集详情状态读取失败');
  }
  const listLatestVersionId = row.versionId;
  const currentVersionId = row.versionId;
  const importMeta: Pick<
    DatasetListItem,
    | 'latestDraftVersionId'
    | 'importJobId'
    | 'importStatus'
    | 'importProgress'
    | 'importErrorMessage'
    | 'displayStatus'
    | 'editSessionId'
    | 'workspaceId'
    | 'workspaceRevision'
    | 'hasDraft'
  > = {
    latestDraftVersionId: row.latestDraftVersionId,
    importJobId: row.importJobId,
    importStatus: row.importStatus,
    importProgress: row.importProgress,
    importErrorMessage: row.importErrorMessage,
    displayStatus: row.displayStatus,
    editSessionId: row.editSessionId,
    workspaceId: row.workspaceId,
    workspaceRevision: row.workspaceRevision,
    hasDraft: row.hasDraft,
  };

  const versions = normalizeDatasetVersionList(versionRes?.data)
    .map((version) => mapDatasetVersion(version, asset.id))
    .filter((v) => !!v.id)
    .sort((left, right) =>
      left.createdAt && right.createdAt
        ? right.createdAt.localeCompare(left.createdAt)
        : 0,
    );

  const pickDefaultVersionId = (): string | undefined => {
    for (const v of versions) {
      const vid = resolveDatasetVersionId(v, asset.id);
      if (vid) {
        return vid;
      }
    }
    if (listLatestVersionId && listLatestVersionId !== asset.id) {
      return listLatestVersionId;
    }
    return undefined;
  };

  const defaultVersionId = pickDefaultVersionId();
  const latestVersion =
    versions.find((v) => v.id === defaultVersionId) ?? versions[0];

  return {
    data: {
      id: asset.id,
      name: asset.name,
      type: asset.type as DatasetType,
      remark: asset.remark,
      createdAt: asset.createdAt,
      updatedAt: asset.updatedAt,
      uploadTime: latestVersion?.createdAt ?? asset.createdAt,
      latestVersion,
      versions,
      /** 版本列表选出的默认版本；currentVersionId 为后台当前版本。 */
      defaultVersionId,
      currentVersionId,
      ...importMeta,
    } as API.DatasetDetail & {
      defaultVersionId?: string;
      currentVersionId?: string;
      latestDraftVersionId?: string | null;
      importJobId?: string | null;
      importStatus?: string | null;
      importProgress?: number | null;
      importErrorMessage?: string | null;
    },
  };
}
