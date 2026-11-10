import type { Label, Page } from './labeling';
import { labelingJson, pageQuery, post } from './labeling';

export type LabelingIdentity = {
  actorId: string;
  labelingAdmin: boolean;
  canCreateProject: boolean;
  canAnnotate: boolean;
};
export type Package = {
  packageId: string;
  projectId: string;
  projectName: string;
  taskType: string;
  sampleCount: number;
  requiredCount: number;
  remainingSlots: number;
};
export type Assignment = {
  assignmentId: string;
  packageId: string;
  projectId?: string;
  slotNo: number;
  status: string;
  revision: number;
};
export type Plan = {
  planId: string;
  status: string;
  selectionHash: string;
  sampleCount: number;
  packageSampleCounts: number[];
  totalWorkload: number;
  packages: {
    packageId: string;
    sequenceNo: number;
    sampleCount: number;
    requiredCount: number;
  }[];
};
export type ClassificationResult = { labelId?: string };
export type WorkSample = {
  sampleId: string;
  sourcePath: string;
  ordinal: number;
  mediaType: string;
  referenceResultId?: string;
  referenceResult?: ClassificationResult;
  sourceKind?: 'HUMAN' | 'AI' | 'OWNER' | 'AGGREGATION';
  modelVersion?: string;
  confidence?: number | null;
  draftRevision: number | null;
  explicitDone: boolean | null;
  resultFormat: string | null;
  result: ClassificationResult | null;
};
export type WorkPage = Page<WorkSample> & {
  assignmentId: string;
  packageId: string;
  status: string;
  readOnly: boolean;
  specification: {
    specVersionId: string;
    labels: Label[];
    instructions: string;
  };
};
export type Draft = {
  assignmentId: string;
  sampleId: string;
  revision: number;
  savedAt: string | null;
  explicitDone: boolean;
  resultFormat: 'image-classification-v1';
  result: ClassificationResult;
};
export type RevisionItem = { sampleId: string; revision: number };
export type SubmissionReceipt = {
  submissionId: string;
  assignmentId: string;
  status: 'SUBMITTED';
  sampleCount: number;
};
export type SubmissionSummary = {
  submissionId: string;
  assignmentId: string;
  packageId: string;
  submittedBy: string;
  submittedAt: string;
};
export type Submission = SubmissionSummary & {
  projectId: string;
  specVersionId: string;
  contentHash: string;
  items: {
    sampleId: string;
    revision: number;
    resultFormat: string;
    result: ClassificationResult;
  }[];
};

export const tasks = {
  me: (signal?: AbortSignal) =>
    labelingJson<LabelingIdentity>('/me', { signal }),
  pool: (cursor?: string | null, signal?: AbortSignal) =>
    labelingJson<Page<Package>>(
      `/task-pool?taskType=IMAGE_CLASSIFICATION&${pageQuery(cursor)}`,
      { signal },
    ),
  mine: (cursor?: string | null, signal?: AbortSignal) =>
    labelingJson<Page<Assignment>>(
      `/assignments?scope=mine&${pageQuery(cursor)}`,
      { signal },
    ),
  claim: (id: string, key: string) =>
    post<Assignment>(`/packages/${encodeURIComponent(id)}/claims`, {}, key),
  plan: (
    projectId: string,
    body: {
      batchId: string;
      specVersionId: string;
      packageSize: number;
      annotatorsPerSample: number;
    },
    key: string,
  ) =>
    post<Plan>(
      `/projects/${encodeURIComponent(projectId)}/package-plans`,
      body,
      key,
    ),
  getPlan: (id: string, signal?: AbortSignal) =>
    labelingJson<Plan>(`/package-plans/${encodeURIComponent(id)}`, { signal }),
  publish: (plan: Plan, key: string) =>
    post<Plan>(
      `/package-plans/${encodeURIComponent(plan.planId)}/publish`,
      { selectionHash: plan.selectionHash },
      key,
    ),
  draft: (assignmentId: string, sampleId: string, signal?: AbortSignal) =>
    labelingJson<Draft>(
      `/assignments/${encodeURIComponent(assignmentId)}/drafts/${encodeURIComponent(sampleId)}`,
      { signal },
    ),
  save: (
    assignmentId: string,
    sampleId: string,
    baseRevision: number,
    labelId: string,
  ) =>
    labelingJson<Draft>(
      `/assignments/${encodeURIComponent(assignmentId)}/drafts/${encodeURIComponent(sampleId)}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          baseRevision,
          explicitDone: Boolean(labelId),
          resultFormat: 'image-classification-v1',
          result: labelId ? { labelId } : {},
        }),
      },
    ),
  submit: (assignmentId: string, items: RevisionItem[], key: string) =>
    post<SubmissionReceipt>(
      `/assignments/${encodeURIComponent(assignmentId)}/submissions`,
      { items },
      key,
    ),
  submissions: (
    projectId: string,
    cursor?: string | null,
    signal?: AbortSignal,
  ) =>
    labelingJson<Page<SubmissionSummary>>(
      `/projects/${encodeURIComponent(projectId)}/submissions?${pageQuery(cursor)}`,
      { signal },
    ),
  submission: (id: string, signal?: AbortSignal) =>
    labelingJson<Submission>(`/submissions/${encodeURIComponent(id)}`, {
      signal,
    }),
};

/** Load the entire package, including all pages, before preparing a submission. */
export async function allWorkItems(
  assignmentId: string,
  signal?: AbortSignal,
): Promise<WorkPage> {
  let result: WorkPage | undefined;
  let cursor: string | null = null;
  const cursors = new Set<string>();
  const ids = new Set<string>();
  do {
    const page: WorkPage = await labelingJson<WorkPage>(
      `/assignments/${encodeURIComponent(assignmentId)}/items?${pageQuery(cursor)}`,
      { signal },
    );
    if (
      page.assignmentId !== assignmentId ||
      !Array.isArray(page.items) ||
      (result &&
        (result.packageId !== page.packageId ||
          result.specification.specVersionId !==
            page.specification.specVersionId ||
          result.status !== page.status ||
          result.readOnly !== page.readOnly))
    ) {
      throw new Error('任务包在读取期间发生变化，请重新加载');
    }
    for (const item of page.items) {
      if (ids.has(item.sampleId))
        throw new Error('任务包包含重复样本，请重新加载');
      ids.add(item.sampleId);
    }
    result = result
      ? {
          ...result,
          items: [...result.items, ...page.items],
          nextCursor: page.nextCursor,
        }
      : page;
    cursor = page.nextCursor;
    if (cursor) {
      if (cursors.has(cursor))
        throw new Error('分页游标重复，请重新加载任务包');
      cursors.add(cursor);
    }
  } while (cursor);
  if (!result) throw new Error('任务包读取失败');
  return result;
}
