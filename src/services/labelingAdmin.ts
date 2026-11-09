import { labelingJson } from './labeling';

export type LabelingUser = {
  actorId: string;
  identityUserId: string;
  displayName: string;
  status: 'ENABLED' | 'DISABLED';
  labelingAdmin: boolean;
};
export const labelingAdmin = {
  users: (offset = 0, signal?: AbortSignal) =>
    labelingJson<LabelingUser[]>(`/admin/users?limit=20&offset=${offset}`, {
      signal,
    }),
  update: (
    actorId: string,
    body: { status?: string; labelingAdmin?: boolean; reason: string },
  ) =>
    labelingJson<null>(
      `/admin/users/${encodeURIComponent(actorId)}`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      },
      true,
    ),
};
