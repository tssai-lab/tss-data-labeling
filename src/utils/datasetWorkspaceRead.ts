import type { V2DatasetWorkspace } from '@/services/datasetV2';

/** 裸 404 也可能是网关故障；只有明确不可用或访问拒绝才清理本地编辑引用。 */
export function workspaceReadFailure(error: unknown) {
  const value = error as {
    status?: number;
    errorCode?: string;
    info?: { status?: number; errorCode?: string };
    response?: { status?: number; data?: { errorCode?: string } };
  };
  const status = Number(value?.response?.status ?? value?.info?.status ?? value?.status);
  const code = value?.response?.data?.errorCode ?? value?.info?.errorCode ?? value?.errorCode;
  const inaccessible = status === 401 || status === 403;
  const unavailable = code === 'DATASET_WORKSPACE_NOT_FOUND';
  return {
    clearLocal: inaccessible || unavailable,
    message: inaccessible
      ? '当前无法访问工作区，请确认登录和权限后重试。'
      : unavailable
        ? '工作区已关闭、不存在或当前账号不可见，请重试刷新详情。'
        : '工作区暂时读取失败，尚不能判断草稿状态；请重试。',
  };
}

/** 校验当前读取回执；不把错资产、未知状态或无效 revision 变成编辑权限。 */
export function requireMatchingWorkspace(
  workspace: V2DatasetWorkspace,
  workspaceId: string,
  datasetId: string,
): void {
  if (!workspace || workspace.workspaceId !== workspaceId || workspace.datasetId !== datasetId ||
      !Number.isSafeInteger(workspace.workspaceRevision) || workspace.workspaceRevision < 0 ||
      (workspace.status != null && workspace.status !== 'DRAFT')) {
    throw new Error('工作区回执不完整或归属不匹配，请重试');
  }
}
