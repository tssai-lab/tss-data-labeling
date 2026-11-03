/** 本人版本及管理员待审核目录读取。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { isLegacyEndpointUnavailable } from '@/utils/apiCompatibility.mjs';
import { listPendingCodeVersions } from '@/utils/pendingCodeVersions';
import { request } from '@umijs/max';
import {
  getAdminCodeAsset,
  getAdminCodeReviewTaskDetail,
  isInternalGeneratedCodeAssetName,
  listAdminCodeAssets,
  listAdminCodeAssetVersions,
  listAdminCodeReviewTasks,
  listV2CodeAssets,
  listV2CodeAssetVersions,
  mapAdminReviewTaskDetailToCodeVersionDetail,
  mapAdminReviewTaskToListItem,
  mapV2CodeVersionToLegacy,
  normalizeAdminCodeAssetPage,
  normalizeAdminReviewTaskPage,
} from '../codeV2';
import { enrichCodeVersionDisplayFields, pickDisplayCodeName } from './display';
import { type CodeVersionListItem } from './types';


/** 训练代码版本列表（当前用户可见版本；现网 OpenAPI 无查询参数） */
export async function fetchCodeVersionList(
  params?: {
    approvalStatus?: string;
    codeName?: string;
    current?: number;
    pageSize?: number;
  },
  options?: { [key: string]: any },
  strictResponse = false,
) {
  // 不向现网 list 传空 codeName / 未声明分页参数，避免后端按空值过滤成 0 条
  const query: Record<string, string | number> = {};
  const codeName = params?.codeName?.trim();
  if (codeName) {
    query.codeName = codeName;
  }
  if (params?.approvalStatus) {
    query.approvalStatus = params.approvalStatus;
  }

  return request<{
    success: boolean;
    data: CodeVersionListItem[] | { data?: CodeVersionListItem[]; total?: number };
    total?: number;
    errorMessage?: string;
  }>('/code/version/list', {
    method: 'GET',
    params: Object.keys(query).length ? query : undefined,
    ...(options || {}),
  }).then(async (res) => {
    const raw = res?.data;
    let list: CodeVersionListItem[] = [];
    let total = 0;
    if (Array.isArray(raw)) {
      list = raw;
      total = res.total ?? raw.length;
    } else if (raw && typeof raw === 'object' && Array.isArray(raw.data)) {
      list = raw.data;
      total = raw.total ?? res.total ?? raw.data.length;
    } else {
      if (strictResponse) throw new Error('训练代码列表响应格式异常');
      return { ...res, data: [] as CodeVersionListItem[], total: 0 };
    }
    // legacy list 不含 validationStatus/riskLevel；V2 版本常不含 trainingProfile
    const enriched = await Promise.all(
      list.map((item) =>
        enrichCodeVersionDisplayFields(item, {
          ...(options || {}),
          enrichRisk: true,
        }),
      ),
    );
    return { ...res, data: enriched, total };
  });
}

/** 已审核、可用于 K8s 训练的训练代码版本列表 */
export async function fetchApprovedCodeVersions(options?: { [key: string]: any }) {
  const res = await fetchCodeVersionList(undefined, options);
  if (!res?.data) {
    return res;
  }
  return {
    ...res,
    data: res.data.filter((item) => item.approvalStatus === 'APPROVED'),
  };
}

function unwrapAssetList(payload: unknown): import('../codeV2').V2CodeAsset[] {
  if (Array.isArray(payload)) return payload;
  if (payload && typeof payload === 'object') {
    const obj = payload as Record<string, unknown>;
    if (obj.errorCode || obj.success === false) {
      throw new Error('训练代码资产列表响应异常');
    }
    if (Array.isArray(obj.items)) {
      return obj.items as import('../codeV2').V2CodeAsset[];
    }
    if (Array.isArray(obj.data)) {
      return obj.data as import('../codeV2').V2CodeAsset[];
    }
    const nested = obj.data;
    if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
      const inner = nested as Record<string, unknown>;
      if (Array.isArray(inner.items)) {
        return inner.items as import('../codeV2').V2CodeAsset[];
      }
    }
  }
  throw new Error('训练代码资产列表响应格式异常');
}

/** 本地待审登记补进列表，避免 PENDING/REJECTED 被 legacy 列表滤掉后看不到记录。
 * 仅合并「当前用户上传/发布」类登记；管理员待审产生的空壳不得混进本人列表。
 */
function mergeLocalPendingRows(
  rows: CodeVersionListItem[],
): CodeVersionListItem[] {
  const remoteIds = new Set(rows.map((item) => item.codeVersionId));
  const ownerSources = new Set(['upload', 'publish', 'manual', 'api']);
  const extras: CodeVersionListItem[] = listPendingCodeVersions()
    .filter((item) => {
      const id = item.codeVersionId?.trim();
      if (!id || remoteIds.has(id)) return false;
      const source = item.source;
      if (source && !ownerSources.has(source)) return false;
      // 无名称/文件名/资产 ID 的空壳（常见于管理员拒绝时误写入）一律丢弃
      if (
        !item.codeAssetName?.trim() &&
        !item.fileName?.trim() &&
        !item.codeAssetId?.trim()
      ) {
        return false;
      }
      return true;
    })
    .map((item) => ({
      codeVersionId: item.codeVersionId,
      codeAssetId: item.codeAssetId?.trim() || '',
      codeName: item.codeAssetName,
      codeAssetName: item.codeAssetName || item.codeVersionId,
      version: '',
      fileName: item.fileName || '',
      trainingProfile: item.trainingProfile || '',
      approvalStatus: item.approvalStatus || 'PENDING',
      status: '',
      createdAt: item.uploadedAt,
      submittedAt: item.uploadedAt,
    }));
  return extras.length ? [...extras, ...rows] : rows;
}

/**
 * 当前用户全部代码版本（含 PENDING / REJECTED / REVOKED），供训练代码列表看审核状态。
 * legacy /code/version/list 只返回 READY+APPROVED，不能用来展示待审/拒绝记录。
 */
export async function fetchOwnerCodeVersionInventory(options?: {
  [key: string]: any;
}): Promise<{
  success: boolean;
  data: CodeVersionListItem[];
  total: number;
  errorMessage?: string;
  incomplete?: boolean;
  warningMessage?: string;
}> {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  let assets: import('../codeV2').V2CodeAsset[];
  try {
    assets = unwrapAssetList(await listV2CodeAssets(opts));
  } catch (error) {
    // 只有资产入口确实不支持 V2 才降级；单资产失败不能把整个列表变为旧接口结果。
    if (!isLegacyEndpointUnavailable(error)) throw error;
    const res = await fetchCodeVersionList(undefined, options, true);
    if (res.success === false) {
      throw new Error(res.errorMessage || '训练代码列表加载失败');
    }
    const list = Array.isArray(res?.data) ? res.data : [];
    const merged = mergeLocalPendingRows(list);
    return { ...res, data: merged, total: merged.length };
  }

  let failedAssets = 0;
  const assetRows = await Promise.all(
    assets.map(async (asset): Promise<CodeVersionListItem[]> => {
      try {
        const assetId = String(asset?.id || '').trim();
        if (!assetId) throw new Error('训练代码资产缺少标识');
        const versions = unwrapVersionList(
          await listV2CodeAssetVersions(assetId, opts), true,
        );
        return versions.map((version) => {
          const mapped = mapV2CodeVersionToLegacy(version);
          if (!mapped.codeVersionId?.trim()) throw new Error('训练代码版本缺少标识');
          const displayName =
            (asset.name && !isInternalGeneratedCodeAssetName(asset.name)
              ? asset.name.trim()
              : undefined) || mapped.codeName || mapped.codeAssetName;
          return {
            ...mapped,
            codeAssetId: mapped.codeAssetId || assetId,
            codeName: displayName || mapped.codeName,
            codeAssetName: displayName || mapped.codeAssetName,
            trainingProfile: mapped.trainingProfile || asset.trainingProfile || '',
          };
        });
      } catch {
        // 允许展示其它资产，但不得把缺失记录伪装成完整列表。
        failedAssets += 1;
        return [];
      }
    }),
  );
  if (assets.length > 0 && failedAssets === assets.length) {
    throw new Error('训练代码版本列表加载失败，请刷新重试；不能据此判断代码已丢失');
  }
  const enriched = await Promise.all(
    assetRows.flat().map((item) =>
      enrichCodeVersionDisplayFields(item, { ...opts, enrichRisk: true }),
    ),
  );
  const merged = mergeLocalPendingRows(enriched);
  return {
    success: true, data: merged, total: merged.length,
    incomplete: failedAssets > 0,
    warningMessage: failedAssets > 0
      ? `${assets.length} 个代码资产中有 ${failedAssets} 个版本列表加载失败，当前列表不完整，请刷新重试。`
      : undefined,
  };
}

/** 管理员待审核队列：走 V2 `/api/v2/admin/code-review-tasks` */
export async function fetchPendingCodeReviewTasks(
  params?: {
    approvalStatus?: string;
    riskLevel?: string;
    ownerUserId?: number;
    keyword?: string;
    submittedFrom?: string;
    submittedTo?: string;
    sortBy?: string;
    sortDirection?: 'ASC' | 'DESC';
    current?: number;
    pageSize?: number;
  },
  options?: { [key: string]: any },
) {
  const approvalStatus = params?.approvalStatus ?? 'PENDING';
  const keyword = params?.keyword?.trim() || undefined;
  const current = params?.current ?? 1;
  const pageSize = params?.pageSize ?? 20;
  const payload = await listAdminCodeReviewTasks(
    {
      approvalStatus,
      riskLevel: params?.riskLevel,
      ownerUserId: params?.ownerUserId,
      keyword,
      submittedFrom: params?.submittedFrom?.trim() || undefined,
      submittedTo: params?.submittedTo?.trim() || undefined,
      sortBy: params?.sortBy,
      sortDirection: params?.sortDirection,
      page: Math.max(0, current - 1),
      pageSize,
    },
    options,
  );
  // 仅此读取链路开启严格检查，不改变其它旧调用方的兼容行为。
  const page = normalizeAdminReviewTaskPage(payload, true);
  const mapped = page.items.map(mapAdminReviewTaskToListItem);
  if (mapped.some((item) => !item.codeVersionId?.trim())) {
    throw new Error('管理员待审核列表缺少版本编号');
  }
  const enriched = await Promise.all(
    mapped.map((item) => enrichAdminReviewListItem(item, options)),
  );

  const remoteIds = new Set(enriched.map((item) => item.codeVersionId));
  const shouldFallback =
    current === 1 &&
    !params?.riskLevel &&
    !params?.submittedFrom &&
    !params?.submittedTo &&
    (enriched.length === 0 || Boolean(keyword));
  const extra = shouldFallback
    ? await fallbackPendingFromAdminAssets(
        {
          approvalStatus,
          keyword,
          ownerUserId: params?.ownerUserId,
        },
        remoteIds,
        options,
      )
    : { items: [], incomplete: false, warningMessage: undefined };

  // 无可展示的远端记录且补查未完成时，不能以空列表证明“没有待审核代码”。
  if (extra.incomplete && enriched.length === 0 && extra.items.length === 0) {
    throw new Error(extra.warningMessage || '待审核代码补查未完成，请重试');
  }

  return {
    success: true,
    data: [...extra.items, ...enriched],
    total: (page.totalElements ?? enriched.length) + extra.items.length,
    incomplete: extra.incomplete,
    warningMessage: extra.warningMessage,
  };
}

/** 审核队列条目补用户可见名称（避免只展示内部 code-asset-xxx） */
async function enrichAdminReviewListItem(
  item: CodeVersionListItem,
  options?: { [key: string]: any },
): Promise<CodeVersionListItem> {
  let next = { ...item };
  const needName = !pickDisplayCodeName(next);
  const needFile = !next.fileName?.trim();
  const needProfile = !next.trainingProfile?.trim();
  if (!needName && !needFile && !needProfile) return next;

  if (next.codeVersionId && (needFile || needProfile || needName)) {
    try {
      const detail = await getAdminCodeReviewTaskDetail(next.codeVersionId, {
        skipErrorHandler: true,
      ...(options || {}),
      });
      const legacy = mapAdminReviewTaskDetailToCodeVersionDetail(detail);
      next = {
        ...next,
        codeAssetId: next.codeAssetId || legacy.codeAssetId,
        fileName: next.fileName?.trim() || legacy.fileName || '',
        trainingProfile:
          next.trainingProfile?.trim() || legacy.trainingProfile || '',
        codeName: next.codeName || legacy.codeName,
        codeAssetName:
          pickDisplayCodeName(next) ||
          pickDisplayCodeName(legacy) ||
          next.codeAssetName ||
          legacy.codeAssetName,
      };
    } catch {
      // 详情失败时再尝试资产接口
    }
  }

  if (!pickDisplayCodeName(next) && next.codeAssetId) {
    try {
      const asset = await getAdminCodeAsset(next.codeAssetId, {
        skipErrorHandler: true,
        ...(options || {}),
      });
      const name = asset?.name?.trim();
      if (name) {
        next = {
          ...next,
          codeName: isInternalGeneratedCodeAssetName(name)
            ? next.codeName
            : name,
          codeAssetName: isInternalGeneratedCodeAssetName(name)
            ? next.codeAssetName || name
            : name,
          trainingProfile: next.trainingProfile || asset.trainingProfile || '',
        };
      }
    } catch {
      // 跨 owner 资产名拿不到时保持原值
    }
  }
  return next;
}

function unwrapVersionList(
  payload: unknown,
  strict = false,
): import('../codeV2').V2CodeVersion[] {
  if (Array.isArray(payload)) return payload;
  if (payload && typeof payload === 'object') {
    const obj = payload as Record<string, unknown>;
    if (strict && (obj.errorCode || obj.success === false || (obj.code !== undefined && obj.code !== 200))) {
      throw new Error('训练代码版本列表响应异常');
    }
    if (Array.isArray(obj.items)) {
      return obj.items as import('../codeV2').V2CodeVersion[];
    }
    if (Array.isArray(obj.data)) {
      return obj.data as import('../codeV2').V2CodeVersion[];
    }
  }
  if (strict) throw new Error('训练代码版本列表响应格式异常');
  return [];
}

/** 审核任务队列为空或按名称搜不到时，从管理员资产版本兜底 */
async function fallbackPendingFromAdminAssets(
  params: {
    approvalStatus?: string;
    keyword?: string;
    ownerUserId?: number;
  },
  existingIds: Set<string>,
  options?: { [key: string]: any },
): Promise<{ items: CodeVersionListItem[]; incomplete: boolean; warningMessage?: string }> {
  const wanted = String(params.approvalStatus || 'PENDING').toUpperCase();
  try {
    const res = await listAdminCodeAssets(
      {
        page: 0,
        pageSize: 50,
        keyword: params.keyword,
        ownerUserId:
          params.ownerUserId != null ? String(params.ownerUserId) : undefined,
        sortBy: 'UPDATED_AT',
        sortDirection: 'DESC',
      },
      { skipErrorHandler: true, ...(options || {}) },
    );
    const page = normalizeAdminCodeAssetPage(res, true);
    const assets = page.items;
    const extras: CodeVersionListItem[] = [];
    let failedAssets = 0;
    await Promise.all(
      assets.map(async (asset) => {
        try {
          const assetId = (asset.id || asset.assetId || '').trim();
          if (!assetId) throw new Error('资产缺少编号');
          const versions = unwrapVersionList(
            await listAdminCodeAssetVersions(assetId, {
              skipErrorHandler: true,
              ...(options || {}),
            }),
            true,
          );
          // 先确认版本编号完整，再加入补查结果，避免缺失编号的响应冒充完整资产。
          if (versions.some((version) => !(version.versionId || version.codeVersionId || version.id || '').trim())) {
            throw new Error('版本缺少编号');
          }
          versions.forEach((version) => {
            const versionId = (
              version.versionId ||
              version.codeVersionId ||
              version.id ||
              ''
            ).trim();
            if (!versionId || existingIds.has(versionId)) return;
            if (String(version.approvalStatus || '').toUpperCase() !== wanted) {
              return;
            }
            const displayName =
              (asset.name && !isInternalGeneratedCodeAssetName(asset.name)
                ? asset.name
                : undefined) ||
              version.codeName ||
              version.codeAssetName ||
              version.assetName ||
              asset.name ||
              '';
            extras.push({
              codeVersionId: versionId,
              codeAssetId: assetId,
              codeName: displayName || undefined,
              codeAssetName: displayName,
              version: version.versionLabel || version.version || '',
              fileName: version.fileName || '',
              trainingProfile:
                version.trainingProfile || asset.trainingProfile || '',
              approvalStatus: version.approvalStatus || wanted,
              status: version.status || 'READY',
              validationStatus: version.validationStatus,
              riskLevel: version.riskLevel,
              riskStatus: version.riskStatus,
              submittedAt: version.publishedAt || version.createdAt,
              ownerUserId: asset.ownerUserId,
            });
            existingIds.add(versionId);
          });
        } catch {
          failedAssets += 1;
        }
      }),
    );
    const warnings = [
      failedAssets > 0 ? `${failedAssets} 个资产的版本列表补查失败` : '',
      page.total > assets.length ? `资产补查仅覆盖当前 ${assets.length} 项，未覆盖全部范围` : '',
    ].filter(Boolean);
    return {
      items: extras,
      incomplete: warnings.length > 0,
      warningMessage: warnings.length > 0
        ? `${warnings.join('；')}，当前待审核列表可能不完整，请重试或到代码资产管理核查。`
        : undefined,
    };
  } catch {
    return { items: [], incomplete: true, warningMessage: '管理员代码资产补查失败，当前待审核列表可能不完整，请重试。' };
  }
}
