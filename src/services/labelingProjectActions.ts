import { LabelingError, type Project } from './labeling';
import { ownerApi } from './labelingOwner';

export function deleteDraftProject(
  project: Project,
  reason: string,
  key: string,
) {
  if (project.status !== 'DRAFT')
    throw new LabelingError('只有草稿项目可以删除', 409, 'PROJECT_NOT_DRAFT');
  if (!reason.trim())
    throw new LabelingError('填写删除原因', 422, 'REASON_REQUIRED');
  return ownerApi.state(
    project.projectId,
    'delete',
    { baseRevision: project.revision, reason: reason.trim() },
    key,
  );
}

export function projectEntryPath(
  project: Pick<Project, 'projectId' | 'status'>,
) {
  const root = `/labeling/projects/${encodeURIComponent(project.projectId)}`;
  return project.status === 'DRAFT'
    ? `${root}?panel=publish`
    : `${root}/workbench`;
}
