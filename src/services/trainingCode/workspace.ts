/** 本人工作区编辑及发布编排。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { isTrainingCodeAutoApproveEnabled } from '@/constants/trainingCode';
import { downloadAuthFile } from '@/utils/authFileDownload';
import { upsertPendingCodeVersion } from '@/utils/pendingCodeVersions';
import {
  abandonV2CodeWorkspace,
  deleteV2CodeWorkspaceFile,
  errorMessageFromV2,
  getV2CodeWorkspace,
  getV2CodeWorkspaceFileMetadata,
  listV2CodeWorkspaces,
  moveV2CodeWorkspaceFile,
  openV2CodeWorkspace,
  publishV2CodeWorkspace,
  upsertV2CodeWorkspaceFile,
  validateV2CodeWorkspace,
  type V2CodeValidationResult,
} from '../codeV2';
import { autoApproveCodeVersionIfEnabled } from './approval';
import {
  isOpenWorkspace,
  mapCodeWorkspaceConflictMessage,
  suggestNextCodeVersionLabel,
} from './common';
import { type SaveCodeVersionFileResult } from './types';


async function ensureEditableCodeWorkspace(
  assetId: string,
  baseVersionId: string,
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  const pickMatchingOpen = (
    listed: Awaited<ReturnType<typeof listV2CodeWorkspaces>> | undefined,
  ) => {
    const openList = Array.isArray(listed)
      ? listed.filter((ws) => isOpenWorkspace(ws) && ws.id)
      : [];
    const matched = openList.find((ws) => ws.baseVersionId === baseVersionId);
    if (matched?.id) {
      return { matched, openList };
    }
    return { matched: undefined, openList };
  };

  try {
    const listed = await listV2CodeWorkspaces(assetId, opts);
    const { matched, openList } = pickMatchingOpen(listed);
    if (matched) {
      return matched;
    }
    // 不可复用「其它基线版本」的打开工作区，否则从旧版再发易触发资产/revision 冲突
    if (openList.length > 0) {
      const otherLabel =
        openList[0]?.baseVersionId || openList[0]?.id || '未知版本';
      throw new Error(
        `该代码资产已有基于其他版本的编辑工作区（${otherLabel}）。请先在该工作区发布或放弃后，再从当前版本编辑；或打开对应版本继续编辑。`,
      );
    }
  } catch (error: any) {
    if (
      typeof error?.message === 'string' &&
      error.message.includes('已有基于其他版本的编辑工作区')
    ) {
      throw error;
    }
    // 列表失败时继续尝试新建
  }

  try {
    return await openV2CodeWorkspace(
      assetId,
      { baseVersionId },
      opts,
    );
  } catch (error: any) {
    // 已有打开工作区时，仅复用「同一 baseVersionId」的工作区
    const listed = await listV2CodeWorkspaces(assetId, opts).catch(
      () => undefined,
    );
    const { matched, openList } = pickMatchingOpen(listed);
    if (matched?.id) {
      return matched;
    }
    if (openList.length > 0) {
      const otherLabel =
        openList[0]?.baseVersionId || openList[0]?.id || '未知版本';
      throw new Error(
        `该代码资产已有基于其他版本的编辑工作区（${otherLabel}）。请先在该工作区发布或放弃后，再从当前版本编辑；或打开对应版本继续编辑。`,
      );
    }
    throw error;
  }
}

/**
 * 编辑不可变 code version：打开/复用工作区 → 写入文件 → 发布为新版本。
 * @see module2-api-doc §18.7
 */
export async function saveCodeVersionFileAndPublish(
  params: {
    codeAssetId: string;
    baseVersionId: string;
    path: string;
    content: string;
    currentVersionLabel?: string;
    nextVersionLabel?: string;
  },
  options?: { [key: string]: any },
): Promise<{ success: true; data: SaveCodeVersionFileResult }> {
  const assetId = params.codeAssetId?.trim();
  const baseVersionId = params.baseVersionId?.trim();
  const path = params.path?.trim();
  if (!assetId || !baseVersionId || !path) {
    throw new Error('缺少 codeAssetId / baseVersionId / path，无法保存');
  }

  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const workspace = await ensureEditableCodeWorkspace(
      assetId,
      baseVersionId,
      opts,
    );
    const workspaceId = workspace.id;
    if (!workspaceId) {
      throw new Error('未能打开代码编辑工作区');
    }

    const metadata = await getV2CodeWorkspaceFileMetadata(
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
      metadata?.workspaceRevision ?? workspace.revision;
    if (expectedWorkspaceRevision == null) {
      throw new Error('缺少 workspaceRevision，无法保存');
    }

    const upsertBody: {
      content: string;
      expectedWorkspaceRevision: number;
      expectedContentHash?: string;
    } = {
      content: params.content,
      expectedWorkspaceRevision,
    };
    if (metadata?.contentHash) {
      upsertBody.expectedContentHash = metadata.contentHash;
    }

    const upserted = await upsertV2CodeWorkspaceFile(
      workspaceId,
      path,
      upsertBody,
      opts,
    );

    let publishRevision = upserted?.workspaceRevision;
    if (publishRevision == null) {
      const refreshed = await getV2CodeWorkspace(workspaceId, opts).catch(
        () => undefined,
      );
      publishRevision =
        refreshed?.revision ??
        (await getV2CodeWorkspaceFileMetadata(workspaceId, path, opts).catch(
          () => undefined,
        ))?.workspaceRevision;
    }
    if (publishRevision == null) {
      publishRevision = expectedWorkspaceRevision + 1;
    }

    const nextVersion =
      params.nextVersionLabel?.trim() ||
      suggestNextCodeVersionLabel(params.currentVersionLabel);

    const published = await publishV2CodeWorkspace(
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

    try {
      await autoApproveCodeVersionIfEnabled(publishedVersionId, opts);
    } catch {
      // 发布已成功；自动审核失败时保留 PENDING，可走人工待审
    }

    if (!isTrainingCodeAutoApproveEnabled()) {
      // 人工审核模式：立刻写入待审本地队列，避免管理员只能等后端审核任务延迟出现
      upsertPendingCodeVersion({
        codeVersionId: publishedVersionId,
        codeAssetId: assetId,
        approvalStatus: 'PENDING',
        source: 'publish',
      });
    }

    return {
      success: true,
      data: {
        workspaceId,
        publishedVersionId,
        publishedVersion: published?.versionLabel || published?.version || nextVersion,
        path,
      },
    };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    const tip =
      mapCodeWorkspaceConflictMessage(msg) || msg || '保存训练代码失败';
    const err = new Error(tip);
    (err as any).cause = error;
    throw err;
  }
}

/**
 * 将已有工作区草稿直接发布为新版本（用于仅增删改文件、未改当前预览内容的场景）。
 */
export async function publishCodeWorkspaceDraft(
  params: {
    codeAssetId: string;
    baseVersionId: string;
    currentVersionLabel?: string;
    nextVersionLabel?: string;
  },
  options?: { [key: string]: any },
): Promise<{ success: true; data: SaveCodeVersionFileResult }> {
  const assetId = params.codeAssetId?.trim();
  const baseVersionId = params.baseVersionId?.trim();
  if (!assetId || !baseVersionId) {
    throw new Error('缺少 codeAssetId / baseVersionId，无法发布');
  }
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const workspace = await ensureEditableCodeWorkspace(
      assetId,
      baseVersionId,
      opts,
    );
    const workspaceId = workspace.id;
    if (!workspaceId) {
      throw new Error('未能打开代码编辑工作区');
    }
    const refreshed = await getV2CodeWorkspace(workspaceId, opts).catch(
      () => undefined,
    );
    const publishRevision = refreshed?.revision ?? workspace.revision;
    if (publishRevision == null) {
      throw new Error('缺少 workspaceRevision，无法发布');
    }
    const nextVersion =
      params.nextVersionLabel?.trim() ||
      suggestNextCodeVersionLabel(params.currentVersionLabel);
    const published = await publishV2CodeWorkspace(
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
    try {
      await autoApproveCodeVersionIfEnabled(publishedVersionId, opts);
    } catch {
      // 发布已成功；自动审核失败时保留 PENDING，可走人工待审
    }
    if (!isTrainingCodeAutoApproveEnabled()) {
      upsertPendingCodeVersion({
        codeVersionId: publishedVersionId,
        codeAssetId: assetId,
        approvalStatus: 'PENDING',
        source: 'publish',
      });
    }
    return {
      success: true,
      data: {
        workspaceId,
        publishedVersionId,
        publishedVersion:
          published?.versionLabel || published?.version || nextVersion,
        path: '',
      },
    };
  } catch (error: any) {
    const msg = await errorMessageFromV2(error);
    const tip =
      mapCodeWorkspaceConflictMessage(msg) || msg || '发布工作区草稿失败';
    const err = new Error(tip);
    (err as any).cause = error;
    throw err;
  }
}

/** 工作区：新建文件（无 expectedContentHash） */
export async function createCodeWorkspaceFile(
  params: {
    codeAssetId: string;
    baseVersionId: string;
    path: string;
    content?: string;
  },
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const workspace = await ensureEditableCodeWorkspace(
      params.codeAssetId,
      params.baseVersionId,
      opts,
    );
    const workspaceId = workspace.id!;
    const revision = workspace.revision;
    if (revision == null) throw new Error('缺少 workspaceRevision');
    await upsertV2CodeWorkspaceFile(
      workspaceId,
      params.path,
      {
        content: params.content ?? '',
        expectedWorkspaceRevision: revision,
      },
      opts,
    );
    return { success: true, data: { workspaceId } };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '新建文件失败');
  }
}

/** 工作区：删除文件 */
export async function deleteCodeWorkspaceFile(
  params: {
    codeAssetId: string;
    baseVersionId: string;
    path: string;
  },
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const workspace = await ensureEditableCodeWorkspace(
      params.codeAssetId,
      params.baseVersionId,
      opts,
    );
    const workspaceId = workspace.id!;
    const metadata = await getV2CodeWorkspaceFileMetadata(
      workspaceId,
      params.path,
      opts,
    );
    const revision = metadata?.workspaceRevision ?? workspace.revision;
    if (revision == null) throw new Error('缺少 workspaceRevision');
    await deleteV2CodeWorkspaceFile(
      workspaceId,
      params.path,
      {
        expectedWorkspaceRevision: revision,
        ...(metadata?.contentHash
          ? { expectedContentHash: metadata.contentHash }
          : {}),
      },
      opts,
    );
    return { success: true, data: { workspaceId } };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '删除文件失败');
  }
}

/** 工作区：重命名/移动文件 */
export async function moveCodeWorkspaceFile(
  params: {
    codeAssetId: string;
    baseVersionId: string;
    sourcePath: string;
    targetPath: string;
  },
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const workspace = await ensureEditableCodeWorkspace(
      params.codeAssetId,
      params.baseVersionId,
      opts,
    );
    const workspaceId = workspace.id!;
    const metadata = await getV2CodeWorkspaceFileMetadata(
      workspaceId,
      params.sourcePath,
      opts,
    );
    const revision = metadata?.workspaceRevision ?? workspace.revision;
    if (revision == null) throw new Error('缺少 workspaceRevision');
    await moveV2CodeWorkspaceFile(
      workspaceId,
      {
        sourcePath: params.sourcePath,
        targetPath: params.targetPath,
        expectedWorkspaceRevision: revision,
        ...(metadata?.contentHash
          ? { expectedContentHash: metadata.contentHash }
          : {}),
      },
      opts,
    );
    return { success: true, data: { workspaceId } };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '移动文件失败');
  }
}

/** 放弃打开的代码工作区草稿 */
export async function abandonCodeWorkspace(
  codeAssetId: string,
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const listed = await listV2CodeWorkspaces(codeAssetId, opts);
    const openList = Array.isArray(listed)
      ? listed.filter((ws) => isOpenWorkspace(ws) && ws.id)
      : [];
    if (!openList.length) {
      return { success: true, data: { abandoned: false } };
    }
    for (const ws of openList) {
      if (ws.id == null || ws.revision == null) continue;
      await abandonV2CodeWorkspace(
        ws.id,
        { expectedWorkspaceRevision: ws.revision },
        opts,
      );
    }
    return { success: true, data: { abandoned: true, count: openList.length } };
  } catch (error: any) {
    throw new Error((await errorMessageFromV2(error)) || '放弃工作区失败');
  }
}

/** 用户校验当前工作区草稿 */
export async function validateCodeWorkspaceDraft(
  workspaceId: string,
  options?: { [key: string]: any },
): Promise<{ success: true; data: V2CodeValidationResult }> {
  const id = workspaceId?.trim();
  if (!id) throw new Error('缺少 workspaceId');
  const opts = { skipErrorHandler: true, ...(options || {}) };
  try {
    const ws = await getV2CodeWorkspace(id, opts);
    if (ws?.revision == null) throw new Error('缺少 workspaceRevision');
    const data = await validateV2CodeWorkspace(
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

/** 用户下载工作区单文件 */
export async function downloadCodeWorkspaceFile(
  workspaceId: string,
  path: string,
  fileName?: string,
  options?: { [key: string]: any },
): Promise<{ success: true }> {
  try {
    await downloadAuthFile({
      url: `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/files/download?path=${encodeURIComponent(path)}`,
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
