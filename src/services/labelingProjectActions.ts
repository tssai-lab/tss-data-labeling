import { LabelingError, labelingJson, type Project } from './labeling';

/** 后端尚无删除契约。此适配器只返回预览回执，不发送猜测的 DELETE 请求。 */
export async function previewDraftDeletion(project: Project) {
  if (project.status !== 'DRAFT') {
    throw new LabelingError('只有草稿项目可以删除', 409, 'PROJECT_NOT_DRAFT');
  }
  // 重新校验 owner 访问权、状态和版本，不能使用列表中的过期快照。
  const current = await labelingJson<Project>(
    `/projects/${encodeURIComponent(project.projectId)}`,
  );
  if (current.projectId !== project.projectId) {
    throw new LabelingError(
      '项目响应不匹配，请刷新列表',
      409,
      'PROJECT_MISMATCH',
    );
  }
  if (current.status !== 'DRAFT') {
    throw new LabelingError(
      '项目已不再是草稿，请刷新列表',
      409,
      'PROJECT_NOT_DRAFT',
    );
  }
  if (current.revision !== project.revision) {
    throw new LabelingError(
      '项目已发生变化，请刷新后重新确认',
      409,
      'REVISION_CONFLICT',
    );
  }
  return {
    projectId: current.projectId,
    revision: current.revision,
    mode: 'preview' as const,
  };
}

export function projectEntryPath(
  project: Pick<Project, 'projectId' | 'status'>,
) {
  const root = `/labeling/projects/${encodeURIComponent(project.projectId)}`;
  return project.status === 'DRAFT'
    ? `${root}?panel=publish`
    : `${root}/workbench`;
}
