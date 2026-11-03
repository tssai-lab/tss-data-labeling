/** 管理员工作区编辑及发布编排。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { isTrainingCodeAutoApproveEnabled } from '@/constants/trainingCode';
import { downloadAuthFile } from '@/utils/authFileDownload';
import { upsertPendingCodeVersion } from '@/utils/pendingCodeVersions';
import {
  abandonAdminCodeWorkspace,
  deleteAdminCodeWorkspaceFile,
  errorMessageFromV2,
  getAdminCodeWorkspace,
  getAdminCodeWorkspaceFileMetadata,
  listAdminCodeAssetWorkspaces,
  moveAdminCodeWorkspaceFile,
  openAdminCodeAssetWorkspace,
  publishAdminCodeWorkspace,
  upsertAdminCodeWorkspaceFile,
  validateAdminCodeWorkspace,
  type V2CodeValidationResult,
  type V2CodeWorkspace,
} from '../codeV2';
import { autoApproveCodeVersionIfEnabled } from './approval';
import {
  mapCodeWorkspaceConflictMessage,
  suggestNextCodeVersionLabel,
} from './common';
import {
  type AdminCodeWorkspacePublishResult,
  type AdminCodeWorkspaceSaveResult,
} from './types';


/** 管理员写入工作区文件（不发布） */
export async function saveAdminCodeWorkspaceFile(
  params: { workspaceId: string; path: string; content: string },
  options?: { [key: string]: any },
): Promise<{ success: true; data: AdminCodeWorkspaceSaveResult }> {
  const workspaceId = params.workspaceId?.trim();
  const path = params.path?.trim();
  if (!workspaceId || !path) {
    throw new Error('缺少 workspaceId / path，无法保存');
  }
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const metadata = await getAdminCodeWorkspaceFileMetadata(
      workspaceId,
      path,
      opts,
    );
    if (metadata?.editable === false || metadata?.readOnly === true) {
      throw new Error(
        metadata.reasonCode
          ? `该文件不可编辑（${metadata.reasonCode}）`
          : '该文件不可在线编辑（可能超过 1MB 限制）',
      );
    }
    const expectedWorkspaceRevision =
      metadata?.workspaceRevision ??
      (await getAdminCodeWorkspace(workspaceId, opts).catch(() => undefined))
        ?.revision;
    if (expectedWorkspaceRevision == null) {
      throw new Error('缺少 workspaceRevision，无法保存');
    }
    const body: {
      content: string;
      expectedWorkspaceRevision: number;
      expectedContentHash?: string;
    } = {
      content: params.content,
      expectedWorkspaceRevision,
    };
    if (metadata?.contentHash) {
      body.expectedContentHash = metadata.contentHash;
    }
    const upserted = await upsertAdminCodeWorkspaceFile(
      workspaceId,
      path,
      body,
      opts,
    );
    let workspaceRevision = upserted?.workspaceRevision;
    if (workspaceRevision == null) {
      workspaceRevision =
        (await getAdminCodeWorkspace(workspaceId, opts).catch(() => undefined))
          ?.revision ?? expectedWorkspaceRevision + 1;
    }
    return {
      success: true,
      data: { workspaceId, workspaceRevision, path },
    };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    const tip =
      mapCodeWorkspaceConflictMessage(msg) || msg || '保存工作区文件失败';
    throw new Error(tip);
  }
}

/** 管理员发布工作区草稿为新版本（可选先写入当前文件） */
export async function publishAdminCodeWorkspaceDraft(
  params: {
    workspaceId: string;
    path?: string;
    content?: string;
    currentVersionLabel?: string;
    nextVersionLabel?: string;
    pendingMeta?: {
      codeAssetName?: string;
      fileName?: string;
      trainingProfile?: string;
    };
  },
  options?: { [key: string]: any },
): Promise<{ success: true; data: AdminCodeWorkspacePublishResult }> {
  const workspaceId = params.workspaceId?.trim();
  if (!workspaceId) {
    throw new Error('缺少 workspaceId，无法发布');
  }
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const path = params.path?.trim();
    if (path && params.content != null) {
      await saveAdminCodeWorkspaceFile(
        { workspaceId, path, content: params.content },
        opts,
      );
    }
    const refreshed = await getAdminCodeWorkspace(workspaceId, opts);
    const publishRevision = refreshed?.revision;
    if (publishRevision == null) {
      throw new Error('缺少 workspaceRevision，无法发布');
    }
    const nextVersion =
      params.nextVersionLabel?.trim() ||
      suggestNextCodeVersionLabel(params.currentVersionLabel);
    const published = await publishAdminCodeWorkspace(
      workspaceId,
      {
        expectedWorkspaceRevision: publishRevision,
        version: nextVersion,
      },
      opts,
    );
    const publishedVersionId =
      published?.versionId ||
      published?.codeVersionId ||
      published?.id ||
      '';
    if (!publishedVersionId) {
      throw new Error('发布成功但未返回新版本 ID');
    }
    await reconcileAdminPublishedVersion(publishedVersionId, {
      trainingProfile: params.pendingMeta?.trainingProfile,
      codeAssetName: params.pendingMeta?.codeAssetName,
      fileName: params.pendingMeta?.fileName,
      ...(options || {}),
    });
    return {
      success: true,
      data: {
        workspaceId,
        publishedVersionId,
        publishedVersion:
          published?.versionLabel || published?.version || nextVersion,
        path: path || '',
      },
    };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    const tip =
      mapCodeWorkspaceConflictMessage(msg) || msg || '发布工作区草稿失败';
    throw new Error(tip);
  }
}

/** 管理员放弃工作区草稿 */
export async function abandonAdminCodeWorkspaceDraft(
  workspaceId: string,
  options?: { [key: string]: any },
): Promise<{ success: true }> {
  const id = workspaceId?.trim();
  if (!id) throw new Error('缺少 workspaceId');
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const ws = await getAdminCodeWorkspace(id, opts);
    if (ws?.revision == null) {
      throw new Error('缺少 workspaceRevision，无法放弃');
    }
    await abandonAdminCodeWorkspace(
      id,
      { expectedWorkspaceRevision: ws.revision },
      opts,
    );
    return { success: true };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    const tip =
      mapCodeWorkspaceConflictMessage(msg) || msg || '放弃工作区失败';
    throw new Error(tip);
  }
}

/** 管理员从工作区删除文件 */
export async function removeAdminCodeWorkspaceFile(
  params: {
    workspaceId: string;
    path: string;
    expectedContentHash?: string;
  },
  options?: { [key: string]: any },
): Promise<{ success: true; workspaceRevision: number }> {
  const workspaceId = params.workspaceId?.trim();
  const path = params.path?.trim();
  if (!workspaceId || !path) {
    throw new Error('缺少 workspaceId / path');
  }
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const metadata = await getAdminCodeWorkspaceFileMetadata(
      workspaceId,
      path,
      opts,
    );
    const expectedWorkspaceRevision =
      metadata?.workspaceRevision ??
      (await getAdminCodeWorkspace(workspaceId, opts).catch(() => undefined))
        ?.revision;
    if (expectedWorkspaceRevision == null) {
      throw new Error('缺少 workspaceRevision，无法删除');
    }
    const body: {
      expectedWorkspaceRevision: number;
      expectedContentHash?: string;
    } = { expectedWorkspaceRevision };
    const hash = params.expectedContentHash || metadata?.contentHash;
    if (hash) body.expectedContentHash = hash;
    const res = await deleteAdminCodeWorkspaceFile(
      workspaceId,
      path,
      body,
      opts,
    );
    const workspaceRevision =
      res?.workspaceRevision ??
      (await getAdminCodeWorkspace(workspaceId, opts).catch(() => undefined))
        ?.revision ??
      expectedWorkspaceRevision + 1;
    return { success: true, workspaceRevision };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    const tip =
      mapCodeWorkspaceConflictMessage(msg) || msg || '删除工作区文件失败';
    throw new Error(tip);
  }
}

async function reconcileAdminPublishedVersion(
  publishedVersionId: string,
  options?: { [key: string]: any } & {
    codeAssetId?: string;
    codeAssetName?: string;
    fileName?: string;
    trainingProfile?: string;
  },
) {
  const id = publishedVersionId?.trim();
  if (!id) return;
  const {
    codeAssetId,
    codeAssetName,
    fileName,
    trainingProfile,
    ...rest
  } = options || {};
  const opts = { skipErrorHandler: true, ...rest, trainingProfile };
  try {
    await autoApproveCodeVersionIfEnabled(id, opts);
  } catch {
    // 发布已成功；自动审核失败时保留 PENDING
  }
  if (!isTrainingCodeAutoApproveEnabled()) {
    upsertPendingCodeVersion({
      codeVersionId: id,
      codeAssetId,
      codeAssetName,
      fileName,
      trainingProfile,
      approvalStatus: 'PENDING',
      source: 'publish',
    });
  }
}

function isAdminOpenWorkspace(ws?: V2CodeWorkspace): boolean {
  if (!ws?.id) return false;
  if (ws.readOnly) return false;
  if (ws.closedAt || ws.closedVersionId) return false;
  const status = String(ws.status || '').toUpperCase();
  if (
    status === 'CLOSED' ||
    status === 'ABANDONED' ||
    status === 'PUBLISHED' ||
    status === 'COMMITTED'
  ) {
    return false;
  }
  if (!status) return true;
  return status === 'OPEN' || status === 'ACTIVE';
}

/** 优先复用 OPEN 工作区，否则 POST 打开；指定 baseVersionId 时不复用其它基线 */
export async function ensureAdminCodeAssetWorkspace(
  assetId: string,
  options?: { [key: string]: any } & { baseVersionId?: string },
): Promise<V2CodeWorkspace> {
  const id = assetId?.trim();
  if (!id) throw new Error('缺少 assetId');
  const { baseVersionId, ...rest } = options || {};
  const opts = { skipErrorHandler: true, ...rest };
  const wanted = baseVersionId?.trim();

  const pickOpen = (
    listed: Awaited<ReturnType<typeof listAdminCodeAssetWorkspaces>> | undefined,
  ) => {
    const openList = (Array.isArray(listed) ? listed : []).filter(
      (ws) => isAdminOpenWorkspace(ws) && ws.id,
    );
    if (!wanted) {
      return { matched: openList[0], openList };
    }
    const matched = openList.find((ws) => ws.baseVersionId === wanted);
    return { matched, openList };
  };

  const conflictError = (openList: V2CodeWorkspace[]) => {
    const other = openList[0];
    const label = other?.baseVersionId || other?.id || '未知版本';
    const err = new Error(
      `该代码资产已有基于其他版本的编辑工作区（基线 ${label}）。请先打开该工作区并「发布」或「放弃」后，再从当前版本编辑。`,
    ) as Error & {
      reasonCode: string;
      existingWorkspace?: V2CodeWorkspace;
    };
    err.reasonCode = 'WORKSPACE_BASE_CONFLICT';
    err.existingWorkspace = other;
    return err;
  };

  try {
    const listed = await listAdminCodeAssetWorkspaces(id, opts);
    const { matched, openList } = pickOpen(listed);
    if (matched?.id) return matched;
    // 不可复用其它基线的 OPEN 工作区（否则 POST 会 409 WORKSPACE_BASE_CONFLICT）
    if (wanted && openList.length > 0) {
      throw conflictError(openList);
    }
  } catch (error: any) {
    if (
      error?.reasonCode === 'WORKSPACE_BASE_CONFLICT' ||
      (typeof error?.message === 'string' &&
        error.message.includes('已有基于其他版本的编辑工作区'))
    ) {
      throw error;
    }
    // 列表失败时继续尝试打开
  }

  try {
    return await openAdminCodeAssetWorkspace(
      id,
      wanted ? { baseVersionId: wanted } : undefined,
      opts,
    );
  } catch (error: any) {
    const listed = await listAdminCodeAssetWorkspaces(id, opts).catch(
      () => undefined,
    );
    const { matched, openList } = pickOpen(listed);
    if (matched?.id) return matched;
    if (wanted && openList.length > 0) {
      throw conflictError(openList);
    }
    throw error;
  }
}

/** 管理员在工作区新建包内文件（新 path 不传 contentHash） */
export async function createAdminCodeWorkspaceFile(
  params: { workspaceId: string; path: string; content?: string },
  options?: { [key: string]: any },
): Promise<{ success: true; workspaceRevision: number }> {
  const workspaceId = params.workspaceId?.trim();
  const path = params.path?.trim();
  if (!workspaceId || !path) {
    throw new Error('缺少 workspaceId / path');
  }
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const existing = await getAdminCodeWorkspaceFileMetadata(
      workspaceId,
      path,
      opts,
    ).catch(() => undefined);
    if (existing?.contentHash) {
      throw new Error('该路径已存在，请直接编辑或使用「移动文件」');
    }
    const ws = await getAdminCodeWorkspace(workspaceId, opts);
    if (ws?.revision == null) throw new Error('缺少 workspaceRevision');
    const upserted = await upsertAdminCodeWorkspaceFile(
      workspaceId,
      path,
      {
        content: params.content ?? '',
        expectedWorkspaceRevision: ws.revision,
      },
      opts,
    );
    const workspaceRevision =
      upserted?.workspaceRevision ??
      (await getAdminCodeWorkspace(workspaceId, opts).catch(() => undefined))
        ?.revision ??
      ws.revision + 1;
    return { success: true, workspaceRevision };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    throw new Error(
      mapCodeWorkspaceConflictMessage(msg) || msg || '新建文件失败',
    );
  }
}

/** 管理员移动/重命名工作区文件 */
export async function moveAdminCodeWorkspaceFileByPath(
  params: {
    workspaceId: string;
    sourcePath: string;
    targetPath: string;
  },
  options?: { [key: string]: any },
): Promise<{ success: true; workspaceRevision: number }> {
  const workspaceId = params.workspaceId?.trim();
  const sourcePath = params.sourcePath?.trim();
  const targetPath = params.targetPath?.trim();
  if (!workspaceId || !sourcePath || !targetPath) {
    throw new Error('缺少 workspaceId / sourcePath / targetPath');
  }
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const metadata = await getAdminCodeWorkspaceFileMetadata(
      workspaceId,
      sourcePath,
      opts,
    );
    const revision =
      metadata?.workspaceRevision ??
      (await getAdminCodeWorkspace(workspaceId, opts).catch(() => undefined))
        ?.revision;
    if (revision == null) throw new Error('缺少 workspaceRevision');
    const moved = await moveAdminCodeWorkspaceFile(
      workspaceId,
      {
        sourcePath,
        targetPath,
        expectedWorkspaceRevision: revision,
        ...(metadata?.contentHash
          ? { expectedContentHash: metadata.contentHash }
          : {}),
      },
      opts,
    );
    const workspaceRevision =
      moved?.workspaceRevision ??
      (await getAdminCodeWorkspace(workspaceId, opts).catch(() => undefined))
        ?.revision ??
      revision + 1;
    return { success: true, workspaceRevision };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    throw new Error(
      mapCodeWorkspaceConflictMessage(msg) || msg || '移动文件失败',
    );
  }
}

/** 管理员校验当前工作区 */
export async function validateAdminCodeWorkspaceDraft(
  workspaceId: string,
  options?: { [key: string]: any },
): Promise<{ success: true; data: V2CodeValidationResult }> {
  const id = workspaceId?.trim();
  if (!id) throw new Error('缺少 workspaceId');
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const ws = await getAdminCodeWorkspace(id, opts);
    if (ws?.revision == null) throw new Error('缺少 workspaceRevision');
    const data = await validateAdminCodeWorkspace(
      id,
      { expectedWorkspaceRevision: ws.revision },
      opts,
    );
    return { success: true, data };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    throw new Error(
      mapCodeWorkspaceConflictMessage(msg) || msg || '工作区校验失败',
    );
  }
}

/** 管理员下载工作区单文件 */
export async function downloadAdminCodeWorkspaceFile(
  workspaceId: string,
  path: string,
  fileName?: string,
  options?: { [key: string]: any },
): Promise<{ success: true }> {
  try {
    await downloadAuthFile({
      url: `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/files/download?path=${encodeURIComponent(path)}`,
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
