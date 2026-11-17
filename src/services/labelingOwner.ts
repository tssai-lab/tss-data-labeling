import {
  labelingJson,
  labelingZip,
  type Page,
  pageQuery,
  post,
} from './labeling';
import type { ClassificationResult, Plan } from './labelingTasks';
import type { ResultRow, Selection, SelectionItem } from './labelingWorkflow';

/** Real contracts verified against backend f68b8a8 (2026-11-17). */
export type LiveResult = ResultRow & { submissionId?: string | null };
export type ResultStatistics = {
  total: number;
  hasResultCount: number;
  noResultCount: number;
  needsRevisionCount: number;
  acceptedCount: number;
  activeCount: number;
};
export type ResultFilter = Partial<
  Record<
    | 'specVersionId'
    | 'packageId'
    | 'roundId'
    | 'executionId'
    | 'sourceKind'
    | 'reviewStatus'
    | 'hasResult'
    | 'confidencePolicyId'
    | 'band',
    string
  >
>;
export type LiveAgent = {
  agentId: string;
  displayName: string;
  taskType: string;
  modelVersion: string;
  supportsReference: boolean;
  confidenceDescription: string;
  enabled: boolean;
};
export type LiveExecution = {
  executionId: string;
  roundId: string;
  projectId: string;
  specVersionId: string;
  agentId: string;
  modelVersion: string;
  status: string;
  revision: number;
  generation: number;
  sampleCount: number;
  successCount: number;
  failedCount: number;
  pendingCount: number;
  cancelledCount: number;
  resultSnapshotId: string | null;
  errorSummary?: {
    failedCount: number;
    byCode: { errorCode: string; count: number }[];
  };
};
export type AiResult = {
  sampleId: string;
  status: string;
  resultId: string | null;
  result: ClassificationResult | null;
  confidence: number | null;
  errorCode?: string;
  provisional: boolean;
  reviewable: boolean;
  snapshotId: string | null;
};
export type ExecutionResults = Page<AiResult> & {
  snapshotId: string | null;
  provisional: boolean;
};
export type ConfidencePolicy = {
  policyId: string;
  executionId: string;
  snapshotId: string;
  modelVersion: string;
  lowerThreshold: number;
  upperThreshold: number;
  revision: number;
  createdAt: string;
};
export type HumanReview = {
  submissionId: string;
  projectId: string;
  status: string;
  sampleCount: number;
  items: {
    sampleId: string;
    action: string;
    result: ClassificationResult | null;
    reason: string | null;
    reviewedBy: string;
    reviewedAt: string;
  }[];
};
export type ReviewAudit = {
  auditId: string;
  sampleId: string;
  actorId: string;
  action: string;
  beforeResult: ClassificationResult | null;
  afterResult: ClassificationResult | null;
  reason: string | null;
  createdAt: string;
};
export type SampleReviewInput = {
  sampleId: string;
  specVersionId: string;
  action: 'ACCEPT' | 'NEEDS_REVISION';
  sourceKind?: 'HUMAN' | 'AI';
  submissionId?: string;
  executionId?: string;
  reason?: string;
};
export type SelectedSample = {
  sampleId: string;
  referenceResultId: string | null;
};
export type Round = {
  roundId: string;
  projectId: string;
  packageId: string | null;
  specVersionId: string;
  mode: string;
  status: string;
  roundNo: number;
  revision: number;
  instructions: string;
  sampleCount: number;
  submittedCount: number;
};
export type SnapshotSummary = {
  finalSnapshotId: string;
  name: string;
  specVersionId: string;
  scopeHash: string;
  createdAt: string;
};
export type LiveSnapshot = SnapshotSummary & {
  projectId: string;
  sampleCount: number;
  items: {
    sampleId: string;
    sourceKind: string;
    submissionId: string | null;
    executionId: string | null;
    result: ClassificationResult;
    reviewRevision: number;
    sourcePath: string;
    contentHash: string;
  }[];
};
const id = encodeURIComponent;
const query = (filter: ResultFilter) =>
  new URLSearchParams(
    Object.entries(filter).filter(([, value]) => Boolean(value)) as [
      string,
      string,
    ][],
  ).toString();
export const executionTerminal = (status: string) =>
  ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(status);

export type ScopeInput = {
  specVersionId: string;
  selectionId: string;
  referenceMode: 'NONE' | 'SELECTED';
  reopenAccepted: boolean;
  instructions: string;
};
export type ReviewInput = {
  action: 'ACCEPT' | 'NEEDS_REVISION';
  reason: string;
} & (
  | { selectionId: string }
  | {
      specVersionId: string;
      items: {
        sampleId: string;
        resultId: string | null;
        baseReviewRevision: number;
      }[];
    }
);
export type ExportJob = {
  exportId: string;
  projectId: string;
  format: string;
  status: 'QUEUED' | 'SUCCEEDED' | 'FAILED';
  errorCode: string | null;
  sha256: string | null;
  createdAt: string;
  expiresAt: string;
  downloadUrl: string | null;
};
export type Aggregation = {
  aggregationId: string;
  projectId: string;
  specVersionId: string;
  algorithmId: string;
  status: string;
  createdAt: string;
};
export type AggregationItem = {
  sampleId: string;
  resultId: string | null;
  outcome: string;
  votes: Record<string, number>;
  result: ClassificationResult | null;
};
export type HistoryRow = {
  projectId: string;
  specVersionId?: string;
  status: string;
  createdAt: string;
  batchId?: string;
  filename?: string;
  validSampleCount?: number;
  planId?: string;
  sampleCount?: number;
  roundId?: string;
  mode?: string;
  executionId?: string;
  modelVersion?: string;
  retryOfExecutionId?: string;
  successCount?: number;
  failedCount?: number;
  pendingCount?: number;
};
export type OperationReceipt = {
  resourceId: string;
  operation: string;
  idempotencyKey: string;
  response: Record<string, unknown>;
  createdAt: string;
};
export type RegisteredTemplate = {
  templateId: string;
  templateVersion: string;
  taskType: string;
  inputFormat: string;
  resultFormat: string;
  inputSchemaId?: string;
  resultSchemaId?: string;
  editorKey: string;
  enabled: boolean;
};
export const ownerApi = {
  templates: (signal?: AbortSignal) =>
    labelingJson<RegisteredTemplate[]>('/task-templates', { signal }),
  historyList: (
    project: string,
    kind: 'imports' | 'rounds' | 'ai-executions' | 'package-plans',
    cursor?: string | null,
    signal?: AbortSignal,
  ) =>
    labelingJson<Page<HistoryRow>>(
      `/projects/${id(project)}/${kind}?${pageQuery(cursor)}`,
      { signal },
    ),
  receipts: (cursor?: string | null, signal?: AbortSignal) =>
    labelingJson<Page<OperationReceipt>>(
      `/me/operation-receipts?${pageQuery(cursor)}`,
      { signal },
    ),
  state: (
    project: string,
    action: 'pause' | 'resume' | 'archive' | 'delete',
    body: { baseRevision: number; reason: string },
    key: string,
  ) =>
    labelingJson<{
      projectId: string;
      status: string;
      revision: number;
      deleted: boolean;
    }>(`/projects/${id(project)}${action === 'delete' ? '' : '/' + action}`, {
      method: action === 'delete' ? 'DELETE' : 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
      body: JSON.stringify(body),
    }),
  atomicReview: (project: string, body: ReviewInput, key: string) =>
    post<{ projectId: string; reviewedCount: number }>(
      `/projects/${id(project)}/reviews`,
      body,
      key,
    ),
  revise: (
    project: string,
    result: string,
    body: {
      specVersionId: string;
      sampleId: string;
      baseReviewRevision: number;
      resultFormat?: string;
      result: ClassificationResult;
      reason: string;
    },
    key: string,
  ) =>
    post<{
      resultId: string;
      parentResultId: string;
      sourceKind: 'OWNER';
      reviewRevision: number;
    }>(`/projects/${id(project)}/results/${id(result)}/revisions`, body, key),
  planSelection: (
    project: string,
    body: ScopeInput & { packageSize: number; annotatorsPerSample: 1 },
    key: string,
  ) => post<Plan>(`/projects/${id(project)}/package-plans`, body, key),
  executeSelection: (
    project: string,
    body: ScopeInput & { agentId: string },
    key: string,
  ) => post<LiveExecution>(`/projects/${id(project)}/ai-executions`, body, key),
  retry: (
    execution: string,
    body: { baseRevision: number; sampleIds?: string[]; instructions?: string },
    key: string,
  ) =>
    post<LiveExecution>(`/ai-executions/${id(execution)}/retries`, body, key),
  finalizeSelection: (
    project: string,
    body: { name: string; specVersionId: string; selectionId: string },
    key: string,
  ) =>
    post<LiveSnapshot>(`/projects/${id(project)}/final-snapshots`, body, key),
  algorithms: (signal?: AbortSignal) =>
    labelingJson<
      {
        algorithmId: string;
        taskType: string;
        minimumIndependentSources: number;
        description: string;
      }[]
    >('/aggregation-algorithms', { signal }),
  aggregate: (
    project: string,
    body: {
      algorithmId: string;
      specVersionId: string;
      items: { sampleId: string; resultIds: string[] }[];
    },
    key: string,
  ) => post<Aggregation>(`/projects/${id(project)}/aggregations`, body, key),
  aggregation: (value: string, signal?: AbortSignal) =>
    labelingJson<Aggregation>(`/aggregations/${id(value)}`, { signal }),
  aggregationItems: (
    value: string,
    cursor?: string | null,
    signal?: AbortSignal,
  ) =>
    labelingJson<Page<AggregationItem>>(
      `/aggregations/${id(value)}/items?${pageQuery(cursor)}`,
      { signal },
    ),
  export: (
    project: string,
    body: {
      finalSnapshotIds: string[];
      format: 'classification-csv-zip' | 'energy-jsonl-zip';
      includeOriginals: false;
    },
    key: string,
  ) => post<ExportJob>(`/projects/${id(project)}/exports`, body, key),
  exportStatus: (value: string, signal?: AbortSignal) =>
    labelingJson<ExportJob>(`/exports/${id(value)}`, { signal }),
  downloadExport: (value: string, signal?: AbortSignal) =>
    labelingZip(`/exports/${id(value)}/download`, signal),
  sampleData: (project: string, sample: string, signal?: AbortSignal) =>
    labelingJson<import('./labelingEnergy.mjs').EnergyInput>(
      `/projects/${id(project)}/samples/${id(sample)}/data`,
      { signal },
    ),
  assignmentData: (assignment: string, sample: string, signal?: AbortSignal) =>
    labelingJson<import('./labelingEnergy.mjs').EnergyInput>(
      `/assignments/${id(assignment)}/samples/${id(sample)}/data`,
      { signal },
    ),
  results: (
    project: string,
    filter: ResultFilter,
    cursor?: string | null,
    signal?: AbortSignal,
  ) =>
    labelingJson<Page<LiveResult>>(
      `/projects/${id(project)}/results?${query(filter)}&${pageQuery(cursor)}`,
      { signal },
    ),
  statistics: (project: string, filter: ResultFilter, signal?: AbortSignal) =>
    labelingJson<ResultStatistics>(
      `/projects/${id(project)}/result-statistics?${query(filter)}`,
      { signal },
    ),
  history: (
    project: string,
    sample: string,
    spec: string,
    cursor?: string | null,
    signal?: AbortSignal,
  ) =>
    labelingJson<Page<LiveResult>>(
      `/projects/${id(project)}/samples/${id(sample)}/results?specVersionId=${id(spec)}&${pageQuery(cursor)}`,
      { signal },
    ),
  select: (
    project: string,
    body: {
      specVersionId: string;
      purpose: 'PUBLISH' | 'REVIEW' | 'FINALIZE';
      reviewAction?: 'ACCEPT' | 'NEEDS_REVISION';
      filter?: ResultFilter;
      excludeSampleIds?: string[];
      items?: SelectionItem[];
    },
    key: string,
  ) => post<Selection>(`/projects/${id(project)}/result-selections`, body, key),
  selectionItems: (
    selection: string,
    cursor?: string | null,
    signal?: AbortSignal,
  ) =>
    labelingJson<Page<SelectionItem>>(
      `/result-selections/${id(selection)}/items?${pageQuery(cursor)}`,
      { signal },
    ),
  selectionStatistics: (selection: string, signal?: AbortSignal) =>
    labelingJson<ResultStatistics>(
      `/result-selections/${id(selection)}/statistics`,
      { signal },
    ),
  review: (project: string, items: SampleReviewInput[], key: string) =>
    post<{ projectId: string; reviewedCount: number }>(
      `/projects/${id(project)}/sample-reviews`,
      { items },
      key,
    ),
  humanReview: (submission: string, signal?: AbortSignal) =>
    labelingJson<HumanReview>(`/submissions/${id(submission)}/review`, {
      signal,
    }),
  reviewItems: (
    submission: string,
    items: {
      sampleId: string;
      action: string;
      reason?: string;
      result?: ClassificationResult;
    }[],
    key: string,
  ) =>
    labelingJson<HumanReview>(`/submissions/${id(submission)}/review/items`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
      body: JSON.stringify({ items }),
    }),
  decide: (
    submission: string,
    body: { action: 'ACCEPT' | 'REJECT' | 'CORRECTION'; reason: string },
    key: string,
  ) =>
    post<HumanReview>(
      `/submissions/${id(submission)}/review/decide`,
      body,
      key,
    ),
  audit: (submission: string, cursor?: string | null, signal?: AbortSignal) =>
    labelingJson<Page<ReviewAudit>>(
      `/submissions/${id(submission)}/review/audit?${pageQuery(cursor)}`,
      { signal },
    ),
  planSamples: (
    project: string,
    body: {
      specVersionId: string;
      packageSize: number;
      annotatorsPerSample: 1;
      samples: SelectedSample[];
      instructions: string;
    },
    key: string,
  ) => post<Plan>(`/projects/${id(project)}/package-plans`, body, key),
  agents: (signal?: AbortSignal, taskType = 'IMAGE_CLASSIFICATION') =>
    labelingJson<LiveAgent[]>(`/agents?taskType=${id(taskType)}`, {
      signal,
    }),
  registerAgent: (
    body: {
      taskType: 'IMAGE_CLASSIFICATION' | 'TIMESERIES_ANNOTATION';
      displayName: string;
      modelVersion: string;
      supportsReference: boolean;
      confidenceDescription: string;
      workerKey: string;
    },
    key: string,
  ) => post<{ agentId: string; actorId: string }>('/admin/agents', body, key),
  execute: (
    project: string,
    body: {
      specVersionId: string;
      agentId: string;
      samples: SelectedSample[];
      instructions: string;
    },
    key: string,
  ) => post<LiveExecution>(`/projects/${id(project)}/ai-executions`, body, key),
  execution: (execution: string, signal?: AbortSignal) =>
    labelingJson<LiveExecution>(`/ai-executions/${id(execution)}`, { signal }),
  executionResults: (
    execution: string,
    cursor?: string | null,
    signal?: AbortSignal,
  ) =>
    labelingJson<ExecutionResults>(
      `/ai-executions/${id(execution)}/results?${pageQuery(cursor)}`,
      { signal },
    ),
  cancelExecution: (
    execution: string,
    body: { baseRevision: number; reason: string },
    key: string,
  ) => post<LiveExecution>(`/ai-executions/${id(execution)}/cancel`, body, key),
  policies: (round: string, signal?: AbortSignal) =>
    labelingJson<ConfidencePolicy[]>(
      `/rounds/${id(round)}/confidence-policies`,
      { signal },
    ),
  policy: (
    round: string,
    body: {
      snapshotId: string;
      lowerThreshold: number;
      upperThreshold: number;
    },
    key: string,
  ) =>
    post<ConfidencePolicy>(
      `/rounds/${id(round)}/confidence-policies`,
      body,
      key,
    ),
  round: (round: string, signal?: AbortSignal) =>
    labelingJson<Round>(`/rounds/${id(round)}`, { signal }),
  reRound: (pkg: string, instructions: string, key: string) =>
    post<Round>(`/packages/${id(pkg)}/rounds`, { instructions }, key),
  cancelRound: (
    round: string,
    body: { baseRevision: number; reason: string },
    key: string,
  ) => post<Round>(`/rounds/${id(round)}/cancel`, body, key),
  revoke: (
    assignment: string,
    body: { baseRevision: number; reason: string },
    key: string,
  ) =>
    post<{ assignmentId: string; status: string; revision: number }>(
      `/admin/assignments/${id(assignment)}/revoke`,
      body,
      key,
    ),
  snapshots: (project: string, cursor?: string | null, signal?: AbortSignal) =>
    labelingJson<Page<SnapshotSummary>>(
      `/projects/${id(project)}/final-snapshots?${pageQuery(cursor)}`,
      { signal },
    ),
  snapshot: (snapshot: string, signal?: AbortSignal) =>
    labelingJson<LiveSnapshot>(`/final-snapshots/${id(snapshot)}`, { signal }),
  finalize: (
    project: string,
    body: { name: string; specVersionId: string; sampleIds: string[] },
    key: string,
  ) =>
    post<LiveSnapshot>(`/projects/${id(project)}/final-snapshots`, body, key),
};

/** Cursor loops/duplicate identifiers must never quietly turn into a partial scope. */
export async function readAllPages<T>(
  fetchPage: (cursor: string | null) => Promise<Page<T>>,
  key: (item: T) => string,
): Promise<T[]> {
  const cursors = new Set<string>(),
    ids = new Set<string>(),
    result: T[] = [];
  let cursor: string | null = null;
  do {
    const page = await fetchPage(cursor);
    if (!Array.isArray(page.items)) throw new Error('分页响应异常');
    for (const item of page.items) {
      const value = key(item);
      if (!value || ids.has(value))
        throw new Error('分页数据重复或缺少编号，请重新读取');
      ids.add(value);
      result.push(item);
    }
    if (result.length > 10000)
      throw new Error('一次最多处理 10000 个样本，请缩小范围');
    cursor = page.nextCursor;
    if (cursor && cursors.has(cursor))
      throw new Error('分页游标重复，请重新读取');
    if (cursor) cursors.add(cursor);
  } while (cursor);
  return result;
}

export const selectionItem = (row: LiveResult): SelectionItem => ({
  sampleId: row.sampleId,
  resultId: row.resultId,
  reviewRevision: row.reviewRevision,
});
export function assertChosen(rows: LiveResult[], spec: string) {
  if (!rows.length || rows.length > 10000)
    throw new Error('请选择 1 至 10000 个样本');
  if (new Set(rows.map((row) => row.sampleId)).size !== rows.length)
    throw new Error('样本清单重复');
  if (rows.some((row) => row.specVersionId !== spec || row.activeProcessing))
    throw new Error('样本规范不一致或仍在处理中，请刷新后重新选择');
}

/** A resultId is never sent as referenceResultId: this backend expects a frozen submissionId. */
export async function resolveSources(
  project: string,
  rows: LiveResult[],
  spec: string,
  purpose: 'review' | 'reference' = 'review',
): Promise<LiveResult[]> {
  assertChosen(rows, spec);
  const resolved: LiveResult[] = [];
  for (const row of rows) {
    if (!row.resultId || !row.reviewable || row.requiresResultSelection)
      throw new Error('请从来源历史明确选择可审核的候选答案');
    const history = await readAllPages(
      (cursor) => ownerApi.history(project, row.sampleId, spec, cursor),
      (item) => item.resultId || '',
    );
    const source = history.find((item) => item.resultId === row.resultId);
    if (
      !source ||
      source.sampleId !== row.sampleId ||
      source.specVersionId !== spec ||
      !source.reviewable ||
      source.activeProcessing ||
      source.reviewRevision !== row.reviewRevision
    )
      throw new Error('候选或审核版本已变化，请刷新后重新选择');
    if (
      (source.sourceKind === 'HUMAN' || source.sourceKind === 'OWNER') &&
      source.submissionId
    ) {
      if (purpose === 'reference') {
        if (source.sourceKind !== 'HUMAN')
          throw new Error('参考答案只能选择原始人工提交');
        resolved.push({ ...source, requiresResultSelection: false });
        continue;
      }
      const review = await ownerApi.humanReview(source.submissionId);
      if (
        review.projectId !== project ||
        review.submissionId !== source.submissionId
      )
        throw new Error('提交审核来源不匹配');
      const current = review.items.find(
        (item) => item.sampleId === source.sampleId,
      );
      if (source.sourceKind === 'OWNER' && current?.action !== 'MODIFY')
        throw new Error('此需求方修订已不再是当前答案，请重新核对');
      if (
        current?.action === 'MODIFY' &&
        current.result?.labelId !== source.result?.labelId
      )
        throw new Error('此人工答案已被修订，请查看提交审核中的当前答案');
    } else if (source.sourceKind !== 'AI' || !source.executionId) {
      throw new Error('当前后端只支持明确的人工提交或 AI 执行来源');
    }
    resolved.push({ ...source, requiresResultSelection: false });
  }
  return resolved;
}
export function reviewInputs(
  rows: LiveResult[],
  action: 'ACCEPT' | 'NEEDS_REVISION',
  reason: string,
): SampleReviewInput[] {
  if (action === 'NEEDS_REVISION' && !reason.trim())
    throw new Error('请填写待修正原因');
  return rows.map((row) => ({
    sampleId: row.sampleId,
    specVersionId: row.specVersionId,
    action,
    reason: reason.trim(),
    ...(action === 'ACCEPT'
      ? (row.sourceKind === 'HUMAN' || row.sourceKind === 'OWNER') &&
        row.submissionId
        ? { sourceKind: 'HUMAN' as const, submissionId: row.submissionId }
        : row.sourceKind === 'AI' && row.executionId
          ? { sourceKind: 'AI' as const, executionId: row.executionId }
          : (() => {
              throw new Error('缺少明确的审核来源');
            })()
      : {}),
  }));
}
export function publishSamples(
  rows: LiveResult[],
  reference: boolean,
): SelectedSample[] {
  return rows.map((row) => {
    if (reference && (row.sourceKind !== 'HUMAN' || !row.submissionId))
      throw new Error(
        '当前参考答案接口仅支持人工冻结提交，请关闭参考或改选人工答案',
      );
    return {
      sampleId: row.sampleId,
      referenceResultId: reference ? row.submissionId || null : null,
    };
  });
}
