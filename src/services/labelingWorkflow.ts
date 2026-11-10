import { labelingJson, labelingZip, operationKey, post } from './labeling';

export const downloadClassificationExport = (
  exportId: string,
  signal?: AbortSignal,
) => labelingZip(`/exports/${encodeURIComponent(exportId)}/download`, signal);

export type WorkflowCall = <T>(
  method: 'GET' | 'POST',
  path: string,
  body?: unknown,
  key?: string,
) => Promise<T>;
export type SourceKind = 'HUMAN' | 'AI' | 'OWNER' | 'AGGREGATION';
export type ResultRow = {
  sampleId: string;
  sourcePath: string;
  specVersionId: string;
  resultId: string | null;
  result: { labelId?: string } | null;
  sourceKind: SourceKind | null;
  sourceActor: string | null;
  packageId?: string | null;
  assignmentId?: string | null;
  roundId?: string | null;
  executionId?: string | null;
  modelVersion: string | null;
  confidence: number | null;
  reviewStatus: string;
  reviewRevision: number;
  hasResult: boolean;
  selectedResultId?: string | null;
  candidateCount?: number;
  requiresResultSelection?: boolean;
  activeProcessing: boolean;
  reviewable: boolean;
  errorCode?: string | null;
};
export type Selection = {
  selectionId: string;
  selectionHash: string;
  count: number;
  expiresAt: string;
  purpose: string;
  reviewAction?: string | null;
};
export type SelectionItem = {
  sampleId: string;
  resultId: string | null;
  reviewRevision: number;
};
export type Agent = {
  agentId: string;
  displayName: string;
  modelVersion: string;
  available: boolean;
  supportsReference: boolean;
  supportedLabels: string[];
  confidenceDescription: string;
};
export type Execution = {
  executionId: string;
  roundId: string;
  status: string;
  sampleCount: number;
  successCount: number;
  failedCount: number;
  pendingCount: number;
  modelVersion: string;
  revision: number;
  resultSnapshotId: string | null;
  errorSummary?: string;
};
export type FinalSnapshot = {
  finalSnapshotId: string;
  name: string;
  sampleCount: number;
  specVersionId: string;
  scopeHash: string;
  createdAt: string;
};
export type ExportJob = {
  exportId: string;
  status: string;
  sampleCount: number;
  errorCode?: string;
  expiresAt: string;
};
export type WorkflowPage<T> = { items: T[]; nextCursor: string | null };

/** fafefca only updates documentation; these routes still require verified backend integration. */
export const workflowBackendReady = false;
export const realWorkflowCall: WorkflowCall = (method, path, body, key) =>
  method === 'GET'
    ? labelingJson(path)
    : post(path, body, key || operationKey());

export function workflowClient(call: WorkflowCall, projectId: string) {
  const p = `/projects/${encodeURIComponent(projectId)}`;
  const write = <T>(path: string, body: unknown, key = operationKey()) =>
    call<T>('POST', path, body, key);
  return {
    results: (filter: Record<string, string> = {}) =>
      call<WorkflowPage<ResultRow>>(
        'GET',
        `${p}/results?${new URLSearchParams({ limit: '20', ...filter })}`,
      ),
    statistics: (filter: Record<string, string> = {}) =>
      call<Record<string, number>>(
        'GET',
        `${p}/result-statistics?${new URLSearchParams(filter)}`,
      ),
    history: (sampleId: string) =>
      call<WorkflowPage<ResultRow>>(
        'GET',
        `${p}/samples/${encodeURIComponent(sampleId)}/results`,
      ),
    select: (body: unknown, key?: string) =>
      write<Selection>(`${p}/result-selections`, body, key),
    selectionItems: (id: string, cursor?: string | null) =>
      call<WorkflowPage<SelectionItem>>(
        'GET',
        `/result-selections/${encodeURIComponent(id)}/items?${new URLSearchParams({ limit: '20', ...(cursor ? { cursor } : {}) })}`,
      ),
    selectionStatistics: (id: string) =>
      call<Record<string, number>>(
        'GET',
        `/result-selections/${encodeURIComponent(id)}/statistics`,
      ),
    agents: () => call<Agent[]>('GET', '/agents?taskType=IMAGE_CLASSIFICATION'),
    execute: (body: unknown, key?: string) =>
      write<Execution>(`${p}/ai-executions`, body, key),
    execution: (id: string) =>
      call<Execution>('GET', `/ai-executions/${encodeURIComponent(id)}`),
    executionResults: (id: string) =>
      call<
        WorkflowPage<ResultRow> & {
          provisional: boolean;
          reviewable: boolean;
          snapshotId: string | null;
        }
      >('GET', `/ai-executions/${encodeURIComponent(id)}/results`),
    cancel: (id: string, baseRevision: number, reason: string, key?: string) =>
      write<Execution>(
        `/ai-executions/${encodeURIComponent(id)}/cancel`,
        { baseRevision, reason },
        key,
      ),
    retry: (id: string, body: unknown, key?: string) =>
      write<Execution>(
        `/ai-executions/${encodeURIComponent(id)}/retries`,
        body,
        key,
      ),
    policy: (roundId: string, body: unknown, key?: string) =>
      write<{ policyId: string }>(
        `/rounds/${encodeURIComponent(roundId)}/confidence-policies`,
        body,
        key,
      ),
    review: (body: unknown, key?: string) =>
      write<{ affectedCount: number; reviewBatchId: string }>(
        `${p}/reviews`,
        body,
        key,
      ),
    revise: (id: string, body: unknown, key?: string) =>
      write<{ resultId: string }>(
        `${p}/results/${encodeURIComponent(id)}/revisions`,
        body,
        key,
      ),
    plan: (body: unknown, key?: string) =>
      write<{
        planId: string;
        selectionHash: string;
        sampleCount: number;
        packageSampleCounts: number[];
        acceptedSampleCount: number;
      }>(`${p}/package-plans`, body, key),
    publish: (id: string, selectionHash: string, key?: string) =>
      write<{
        packages: { packageId: string; roundId: string; sampleCount: number }[];
      }>(
        `/package-plans/${encodeURIComponent(id)}/publish`,
        { selectionHash },
        key,
      ),
    algorithms: () =>
      call<{ algorithmId: string; description: string }[]>(
        'GET',
        '/aggregation-algorithms?taskType=IMAGE_CLASSIFICATION',
      ),
    aggregate: (body: unknown, key?: string) =>
      write<{ aggregationId: string }>(`${p}/aggregations`, body, key),
    aggregation: (id: string) =>
      call<{ status: string }>(
        'GET',
        `/aggregations/${encodeURIComponent(id)}`,
      ),
    aggregationItems: (id: string) =>
      call<
        WorkflowPage<{
          sampleId: string;
          suggestedResultId: string | null;
          status: string;
          voteCounts: Record<string, number>;
          validVoteCount: number;
          voteSupport: number | null;
        }>
      >('GET', `/aggregations/${encodeURIComponent(id)}/items`),
    finalize: (body: unknown, key?: string) =>
      write<FinalSnapshot>(`${p}/final-snapshots`, body, key),
    finals: () =>
      call<WorkflowPage<FinalSnapshot>>('GET', `${p}/final-snapshots`),
    export: (
      body: {
        finalSnapshotIds: string[];
        format: 'classification-csv-zip';
        includeOriginals: boolean;
      },
      key?: string,
    ) => write<ExportJob>(`${p}/exports`, body, key),
    exportStatus: (id: string) =>
      call<ExportJob>('GET', `/exports/${encodeURIComponent(id)}`),
  };
}
