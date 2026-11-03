/** 上传、资产维护及版本操作兼容入口。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { downloadAuthFile } from '@/utils/authFileDownload';
import {
  persistSuccessfulCodeUpload,
  shouldFallbackToLegacyCodeUpload,
} from '@/utils/codeUploadReceipt.mjs';
import {
  removePendingCodeVersion,
  upsertPendingCodeVersion,
} from '@/utils/pendingCodeVersions';
import { request } from '@umijs/max';
import {
  archiveAdminCodeVersion,
  archiveV2CodeVersion,
  deleteV2CodeAsset,
  deprecateAdminCodeVersion,
  deprecateV2CodeVersion,
  errorMessageFromV2,
  extractV2FileText,
  fetchAllV2CodeTreeFiles,
  getAdminCodeReviewTaskDetail,
  getAdminCodeReviewTaskFileContent,
  getAdminCodeReviewTaskTree,
  getV2CodeAsset,
  getV2CodeVersion,
  importV2CodeAssetZip,
  listAdminCodeReviewFindings,
  listV2CodeAssets,
  listV2CodeAssetVersions,
  mapAdminReviewTaskDetailToCodeVersionDetail,
  mapV2CodeVersionToLegacy,
  patchV2CodeAsset,
  rescanAdminCodeReviewTask,
  upgradeV2CodeArtifact,
  type V2AdminCodeReviewTaskDetail,
  validateAdminCodeVersion,
  validateV2CodeVersion,
} from '../codeV2';
import { type CodeUploadResult, CONSISTENCY_TRAINING_PROFILE } from './types';


/** 上传训练代码 ZIP，创建 code_asset + code_version
 * 现网 OpenAPI：metadata 为 query（codeName/trainingProfile 必填），body 仅 multipart file。
 */
export async function uploadCodeZip(
  params: {
    file: File;
    codeName: string;
    version?: string;
    trainingProfile?: string;
    remark?: string;
  },
  options?: { [key: string]: any },
): Promise<{
  success: boolean;
  data: CodeUploadResult;
  errorMessage?: string;
}> {
  const formData = new FormData();
  formData.append('file', params.file);

  const query: Record<string, string> = {
    codeName: params.codeName,
    version: params.version || 'v1',
    trainingProfile:
    params.trainingProfile || CONSISTENCY_TRAINING_PROFILE,
  };
  if (params.remark?.trim()) {
    query.remark = params.remark.trim();
  }

  const legacyUpload = () =>
    request<{
      success: boolean;
      data: CodeUploadResult;
      errorMessage?: string;
    }>('/code/upload', {
      method: 'POST',
      params: query,
      data: formData,
      headers: { 'Content-Type': undefined as unknown as string },
      timeout: 5 * 60 * 1000,
      ...(options || {}),
    });

  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const imported = await importV2CodeAssetZip(
      {
        file: params.file,
        metadata: {
          name: params.codeName,
          version: params.version || 'v1',
          trainingProfile:
            params.trainingProfile || CONSISTENCY_TRAINING_PROFILE,
          remark: params.remark?.trim(),
        },
      },
      opts,
    );
    const mapped = mapV2CodeVersionToLegacy(imported);
    const codeVersionId =
      imported.versionId ||
      imported.id ||
      imported.codeVersionId ||
      mapped.codeVersionId ||
      '';
    if (!codeVersionId) {
      throw new Error('V2 导入成功但未返回 codeVersionId');
    }
    const response = {
      success: true,
      data: {
        codeAssetId:
          imported.assetId ||
          imported.codeAssetId ||
          mapped.codeAssetId ||
          '',
        codeVersionId,
        version: mapped.version || params.version || 'v1',
        fileName: mapped.fileName || params.file.name,
        storagePath: '',
        sizeBytes: mapped.sizeBytes ?? params.file.size,
        trainingProfile:
          mapped.trainingProfile ||
          params.trainingProfile ||
          CONSISTENCY_TRAINING_PROFILE,
        status: mapped.status || 'READY',
        approvalStatus: mapped.approvalStatus || 'PENDING',
      },
    };
    persistSuccessfulCodeUpload(
      response,
      {
        codeName: params.codeName,
        fileName: params.file.name,
        trainingProfile: params.trainingProfile,
      },
      upsertPendingCodeVersion,
    );
    return response;
  } catch (error) {
    if (!shouldFallbackToLegacyCodeUpload(error)) {
      throw error;
    }
    const response = await legacyUpload();
    persistSuccessfulCodeUpload(
      response,
      {
        codeName: params.codeName,
        fileName: params.file.name,
        trainingProfile: params.trainingProfile,
      },
      upsertPendingCodeVersion,
    );
    return response;
  }
}

/**
 * 软删除训练代码资产（V2）。
 * 需先读取 assetRevision 做 CAS；存在打开工作区或被训练引用时后端会 409。
 * 若缺 codeAssetId，可传 codeVersionId，由版本详情回填资产 ID。
 */
export async function deleteCodeAsset(
  codeAssetId: string | undefined | null,
  options?: { [key: string]: any } & { codeVersionId?: string },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  const versionId =
    typeof options?.codeVersionId === 'string'
      ? options.codeVersionId.trim()
      : '';
  let assetId = (codeAssetId || '').trim();

  if (!assetId && versionId) {
    try {
      const detail = await getV2CodeVersion(versionId, opts);
      assetId = String(detail.assetId || detail.codeAssetId || '').trim();
    } catch (error: any) {
      const status = error?.response?.status ?? error?.info?.status;
      const msg = await errorMessageFromV2(error).catch(() => undefined);
      // 服务端已无该版本：清掉本地待审幽灵，避免一直删不掉
      if (
        status === 404 ||
        /not found|不存在|无权限|CODE_ASSET_NOT_FOUND/i.test(String(msg || ''))
      ) {
        removePendingCodeVersion(versionId);
        return {
          success: true,
          data: {
            codeAssetId: '',
            codeVersionId: versionId,
            deleted: true,
            localOnly: true,
          },
        };
      }
      const tip = msg || '无法解析代码资产，删除失败';
      const err = new Error(tip);
      (err as any).cause = error;
      throw err;
    }
  }

  if (!assetId) {
    if (versionId) {
      removePendingCodeVersion(versionId);
      return {
        success: true,
        data: {
          codeAssetId: '',
          codeVersionId: versionId,
          deleted: true,
          localOnly: true,
        },
      };
    }
    throw new Error('缺少代码资产标识，无法删除');
  }

  try {
    const asset = await getV2CodeAsset(assetId, opts);
    const revision = asset?.assetRevision;
    if (revision == null) {
      throw new Error('缺少 assetRevision，无法删除');
    }
    await deleteV2CodeAsset(assetId, revision, opts);
    if (versionId) {
      removePendingCodeVersion(versionId);
    }
    return {
      success: true,
      data: {
        codeAssetId: assetId,
        deleted: true,
        assetRevision: revision,
      },
    };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    const details = error?.response?.data?.details;
    const reasonCode =
      details && typeof details === 'object'
        ? (details as Record<string, unknown>).reasonCode
        : undefined;
    let tip = msg || '删除训练代码失败';
    if (reasonCode === 'OPEN_WORKSPACE_EXISTS') {
      tip = '该代码资产仍有打开的编辑工作区，请先放弃或发布工作区后再删除';
    } else if (reasonCode === 'CODE_ASSET_IN_USE') {
      tip = '该代码资产已被训练任务引用，无法删除';
    } else if (reasonCode === 'ASSET_REVISION_CONFLICT') {
      tip = '资产已被他人更新，请刷新后重试删除';
    }
    const err = new Error(tip);
    (err as any).cause = error;
    throw err;
  }
}

/** 下载训练代码完整 ZIP */
export async function downloadCodeVersionZip(
  codeVersionId: string,
  fileName?: string,
  options?: {
    onProgress?: (ratio: number | null) => void;
    [key: string]: any;
  },
) {
  try {
    await downloadAuthFile({
      url: `/v2/code-versions/${encodeURIComponent(codeVersionId)}/download`,
      fileName: fileName?.trim() || `${codeVersionId}.zip`,
      onProgress: options?.onProgress,
    });
    return { success: true };
  } catch (error: any) {
    const msg =
      (await errorMessageFromV2(error).catch(() => undefined)) ||
      error?.message ||
      '下载失败';
    throw new Error(msg);
  }
}

/** 更新代码资产元数据（改名等） */
export async function updateCodeAssetMeta(
  codeAssetId: string,
  patch: {
    name?: string;
    remark?: string;
    purpose?: string;
    runtime?: string;
    entryScript?: string;
  },
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const asset = await getV2CodeAsset(codeAssetId, opts);
    if (asset?.assetRevision == null) {
      throw new Error('缺少 assetRevision，无法更新');
    }
    const updated = await patchV2CodeAsset(
      codeAssetId,
      {
        assetRevision: asset.assetRevision,
        ...patch,
      },
      opts,
    );
    return { success: true, data: updated };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '更新代码资产失败');
  }
}

/** 列出同一资产下的代码版本 */
export async function listCodeAssetVersions(
  codeAssetId: string,
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const list = await listV2CodeAssetVersions(codeAssetId, opts);
    const rows = Array.isArray(list) ? list : [];
    return {
      success: true,
      data: rows.map((item) => mapV2CodeVersionToLegacy(item)),
    };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '版本列表加载失败');
  }
}

export async function deprecateCodeVersion(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  try {
    const data = await deprecateV2CodeVersion(codeVersionId, {
      skipErrorHandler: true,
      ...(options || {}),
    });
    return { success: true, data: mapV2CodeVersionToLegacy(data) };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '弃用失败');
  }
}

export async function archiveCodeVersion(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  try {
    const data = await archiveV2CodeVersion(codeVersionId, {
      skipErrorHandler: true,
      ...(options || {}),
    });
    return { success: true, data: mapV2CodeVersionToLegacy(data) };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '归档失败');
  }
}

export async function upgradeCodeArtifact(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  try {
    const data = await upgradeV2CodeArtifact(codeVersionId, {
      skipErrorHandler: true,
      ...(options || {}),
    });
    return { success: true, data };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '制品升级失败');
  }
}

export async function fetchAdminCodeFindings(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  try {
    const data = await listAdminCodeReviewFindings(codeVersionId, {
      skipErrorHandler: true,
      ...(options || {}),
    });
    return { success: true, data: Array.isArray(data) ? data : [] };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || 'Findings 加载失败');
  }
}

export async function rescanCodeReviewTask(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  try {
    const data = await rescanAdminCodeReviewTask(codeVersionId, {
      skipErrorHandler: true,
      ...(options || {}),
    });
    return { success: true, data };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '重扫失败');
  }
}

/** 管理员下载版本快照单文件 */
export async function downloadAdminCodeVersionFile(
  versionId: string,
  path: string,
  fileName?: string,
  options?: { [key: string]: any },
): Promise<{ success: true }> {
  try {
    await downloadAuthFile({
      url: `/v2/admin/code-versions/${encodeURIComponent(versionId)}/files/download?path=${encodeURIComponent(path)}`,
      fileName: fileName?.trim() || path.split('/').pop() || 'file',
      onProgress: options?.onProgress,
    });
    return { success: true };
  } catch (error: any) {
    const msg =
      (await errorMessageFromV2(error).catch(() => undefined)) ||
      error?.message ||
      '下载失败';
    throw new Error(msg);
  }
}

/** 管理员校验代码版本 */
export async function validateAdminCodeVersionById(
  versionId: string,
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const data = await validateAdminCodeVersion(versionId, opts);
    return { success: true, data };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '版本校验失败');
  }
}

/** 管理员弃用代码版本 */
export async function deprecateAdminCodeVersionById(
  versionId: string,
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const data = await deprecateAdminCodeVersion(versionId, opts);
    return { success: true, data: mapV2CodeVersionToLegacy(data) };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '弃用失败');
  }
}

/** 管理员归档代码版本 */
export async function archiveAdminCodeVersionById(
  versionId: string,
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const data = await archiveAdminCodeVersion(versionId, opts);
    return { success: true, data: mapV2CodeVersionToLegacy(data) };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '归档失败');
  }
}

/** 列出当前用户的 V2 代码资产 */
export async function listCodeAssets(options?: { [key: string]: any }) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const list = await listV2CodeAssets(opts);
    return { success: true, data: Array.isArray(list) ? list : [] };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '资产列表加载失败');
  }
}

/** 管理员审核详情（跨 owner） */
export async function getAdminCodeReviewDetail(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  const detail = await getAdminCodeReviewTaskDetail(codeVersionId, options);
  return {
    success: true,
    data: mapAdminReviewTaskDetailToCodeVersionDetail(detail),
    raw: detail as V2AdminCodeReviewTaskDetail,
  };
}

/** 管理员审核用只读目录树 */
export async function listAdminCodeReviewFiles(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  const data = await fetchAllV2CodeTreeFiles(
    (prefix) =>
      getAdminCodeReviewTaskTree(codeVersionId, prefix, {
        skipErrorHandler: true,
        ...(options || {}),
      }),
    options,
  );
  return { success: true, data };
}

/** 管理员审核用只读文件预览 */
export async function previewAdminCodeReviewFile(
  codeVersionId: string,
  path: string,
  options?: { [key: string]: any },
) {
  const payload = await getAdminCodeReviewTaskFileContent(
    codeVersionId,
    path,
    { skipErrorHandler: true, ...(options || {}) },
  );
  const content = extractV2FileText(payload);
  return {
    success: true,
    data: {
      content,
      path,
      fileName: path.split('/').pop() || path,
    },
  };
}

/** 用户下载版本快照单文件 */
export async function downloadCodeVersionSingleFile(
  versionId: string,
  path: string,
  fileName?: string,
  options?: { [key: string]: any },
): Promise<{ success: true }> {
  try {
    await downloadAuthFile({
      url: `/v2/code-versions/${encodeURIComponent(versionId)}/files/download?path=${encodeURIComponent(path)}`,
      fileName: fileName?.trim() || path.split('/').pop() || 'file',
      onProgress: options?.onProgress,
    });
    return { success: true };
  } catch (error: any) {
    const msg =
      (await errorMessageFromV2(error).catch(() => undefined)) ||
      error?.message ||
      '下载失败';
    throw new Error(msg);
  }
}

/** 显式校验代码版本（POST validate，非 training-check） */
export async function validateCodeVersionExplicit(
  codeVersionId: string,
  options?: { [key: string]: any } & { trainingProfile?: string },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const data = await validateV2CodeVersion(
      codeVersionId,
      options?.trainingProfile
        ? { trainingProfile: options.trainingProfile }
        : undefined,
      opts,
    );
    return { success: true, data };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '版本校验失败');
  }
}

/** 管理员下载版本完整 ZIP */
export async function downloadAdminCodeVersionZipById(
  versionId: string,
  fileName?: string,
  options?: {
    onProgress?: (ratio: number | null) => void;
    [key: string]: any;
  },
): Promise<{ success: true }> {
  try {
    await downloadAuthFile({
      url: `/v2/admin/code-versions/${encodeURIComponent(versionId)}/download`,
      fileName: fileName?.trim() || `${versionId}.zip`,
      onProgress: options?.onProgress,
    });
    return { success: true };
  } catch (error: any) {
    const msg =
      (await errorMessageFromV2(error).catch(() => undefined)) ||
      error?.message ||
      '下载失败';
    throw new Error(msg);
  }
}
