import type { Label } from './labeling';
import type { Draft, RevisionItem, WorkSample } from './labelingTasks';
export const classificationFormat: 'image-classification-v1';
export type PendingSubmission = {
  actorId: string;
  assignmentId: string;
  key: string;
  items: RevisionItem[];
};
export function submissionManifest(
  items: WorkSample[],
  labels: Label[],
): RevisionItem[];
export function confirmedDraft(
  draft: Draft,
  assignmentId: string,
  sampleId: string,
): Draft;
export function parsePendingSubmission(
  raw: string | null,
  actorId: string,
  assignmentId: string,
): PendingSubmission | undefined;
