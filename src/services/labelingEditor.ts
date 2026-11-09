import { operationKey, post } from './labeling';
import type { Draft } from './labelingTasks';

export type EditorContext = {
  assignmentId: string;
  sampleId: string;
  actorId: string;
  specVersionId: string;
  projectId?: string;
};
export type EditorSession = EditorContext & {
  projectId: string;
  editorSessionId: string;
  editorUrl: string;
  mode: string;
  launchExpiresAt: string;
  expiresAt: string;
  draft: Draft;
};
const keys = [
  'assignmentId',
  'projectId',
  'sampleId',
  'actorId',
  'specVersionId',
] as const;
export function editorLaunch(
  session: EditorSession,
  expected: EditorContext,
  origin: string,
): string {
  if (
    !session.editorSessionId ||
    !session.projectId ||
    session.mode !== 'ASSIGNMENT_IMAGE_CLASSIFICATION' ||
    keys.some((k) => expected[k] !== undefined && session[k] !== expected[k])
  )
    throw new Error('编辑器会话身份或上下文不匹配');
  const url = new URL(session.editorUrl, origin);
  if (
    url.origin !== origin ||
    url.username ||
    url.password ||
    url.hash ||
    url.pathname !==
      `/api/labeling/v1/editor-ui/${session.editorSessionId}/launch` ||
    !url.searchParams.get('ticket') ||
    [...url.searchParams.keys()].some((k) => k !== 'ticket')
  )
    throw new Error('编辑器入口未通过同源与会话路径校验');
  if (
    Date.parse(session.launchExpiresAt) <= Date.now() ||
    !Number.isFinite(Date.parse(session.launchExpiresAt)) ||
    !Number.isFinite(Date.parse(session.expiresAt)) ||
    Date.parse(session.expiresAt) <= Date.now()
  )
    throw new Error('编辑器入口已过期，请重新打开');
  return url.href;
}
export function validEditorMessage(
  data: unknown,
  session: EditorSession,
): data is Record<string, unknown> {
  if (!data || typeof data !== 'object') return false;
  const value = data as Record<string, unknown>;
  if (
    value.editorSessionId !== session.editorSessionId ||
    typeof value.type !== 'string' ||
    ![
      'labeling:ready',
      'labeling:state',
      'labeling:saved',
      'labeling:save-failed',
      'labeling:load-failed',
      'labeling:discarded',
    ].includes(value.type)
  )
    return false;
  const partial =
    value.type === 'labeling:load-failed' ||
    (value.type === 'labeling:state' && value.ready === false);
  return keys.every((k) =>
    partial && value[k] === undefined ? true : value[k] === session[k],
  );
}
export const editor = {
  open: (assignmentId: string, sampleId: string) =>
    post<EditorSession>(
      `/assignments/${encodeURIComponent(assignmentId)}/editor-sessions`,
      { sampleId },
      operationKey(),
    ),
  sync: (assignmentId: string, sampleId: string) =>
    post<Draft>(
      `/assignments/${encodeURIComponent(assignmentId)}/editor-sync`,
      { sampleId },
      operationKey(),
    ),
};
