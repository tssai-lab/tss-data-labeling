// Synthetic fixtures only. No fetch, token, external model or LS service. Never accepts real resource IDs.
export class WorkflowMockError extends Error {
  constructor(status, code, message) {
    super(message);
    Object.assign(this, {
      status,
      code,
      requestId: `demo-request-${crypto.randomUUID()}`,
    });
  }
}
export function confidenceBand(confidence, lower, upper) {
  if (
    !Number.isFinite(lower) ||
    !Number.isFinite(upper) ||
    lower < 0 ||
    lower >= upper ||
    upper > 1
  )
    throw new WorkflowMockError(
      422,
      'INVALID_THRESHOLDS',
      '须手动设置 0 ≤ L < H ≤ 1',
    );
  if (!Number.isFinite(confidence) || confidence < 0 || confidence > 1)
    return 'UNKNOWN';
  return confidence >= upper ? 'HIGH' : confidence >= lower ? 'MEDIUM' : 'LOW';
}
export function majorityVote(results) {
  const actors = new Set();
  const counts = {};
  for (const r of results) {
    if (
      !r?.result?.labelId ||
      !r.sourceActor ||
      r.parentResultId ||
      r.referenceResultId ||
      !['HUMAN', 'AI'].includes(r.sourceKind) ||
      actors.has(r.sourceActor)
    )
      throw new WorkflowMockError(
        422,
        'NON_INDEPENDENT_INPUT',
        '重复或派生来源不能计为独立选票',
      );
    actors.add(r.sourceActor);
    counts[r.result.labelId] = (counts[r.result.labelId] || 0) + 1;
  }
  if (results.length < 2)
    return {
      status: 'INSUFFICIENT_INPUT',
      voteCounts: counts,
      validVoteCount: results.length,
      labelId: null,
      voteSupport: null,
    };
  const winner = Object.keys(counts).find(
    (k) => counts[k] > results.length / 2,
  );
  return {
    status: winner ? 'CONSENSUS' : 'NO_CONSENSUS',
    voteCounts: counts,
    validVoteCount: results.length,
    labelId: winner || null,
    voteSupport: winner ? counts[winner] / results.length : null,
  };
}

export function createWorkflowMock({
  enabled = false,
  production = true,
  now = () => Date.now(),
} = {}) {
  let db;
  let sequence = 0;
  const receipts = new Map();
  const clone = (value) => structuredClone(value);
  const fail = (status, code, message) => {
    throw new WorkflowMockError(status, code, message);
  };
  const check = () => {
    if (!enabled || production)
      fail(503, 'MOCK_DISABLED', '待后端接入，开发演示未启用');
  };
  const uid = (type) => `demo-new-${type}-${++sequence}`;
  const scopeFingerprint = (value) => {
    let hash = 2166136261;
    for (const c of JSON.stringify(value))
      hash = Math.imul(hash ^ c.charCodeAt(0), 16777619);
    return `synthetic-fnv32-${(hash >>> 0).toString(16).padStart(8, '0')}`;
  };
  function reset() {
    check();
    sequence = 0;
    receipts.clear();
    db = {
      project: {
        projectId: 'demo-project',
        name: '图片分类合成项目',
        status: 'OPEN',
        revision: 1,
      },
      specVersionId: 'demo-spec-v1',
      labels: [
        { id: 'normal', name: '正常' },
        { id: 'defect', name: '缺陷' },
        { id: 'none', name: '无目标' },
      ],
      samples: [],
      results: [],
      selections: [],
      executions: [],
      policies: [],
      plans: [],
      packages: [],
      finals: [],
      exports: [],
      aggregations: [],
      audit: [],
    };
    const scores = [0, 0.59, 0.6, 0.899, 0.9, null];
    for (let i = 0; i < 12; i++) {
      const sampleId = `demo-sample-${i + 1}`;
      const s = {
        sampleId,
        batchId: 'demo-batch',
        sourcePath: `未标注合成图片/长目录与文件名用于移动端检查/${'sample-'.repeat(8)}${i + 1}.png`,
        currentResultId: null,
        reviewStatus: 'PENDING',
        reviewRevision: 0,
        activeProcessing: false,
        errorCode: i === 6 || i === 7 ? 'MODEL_OUTPUT_INVALID' : null,
      };
      db.samples.push(s);
      if (i < 6 || i >= 10) {
        const result = {
          resultId: `demo-result-${i + 1}`,
          sampleId,
          specVersionId: db.specVersionId,
          result: { labelId: i === 5 ? 'none' : i % 2 ? 'defect' : 'normal' },
          sourceKind: i < 6 ? 'AI' : 'HUMAN',
          sourceActor: i < 6 ? 'demo-agent' : 'demo-human-a',
          modelVersion: i < 6 ? 'synthetic-v1' : null,
          confidence: i < 6 ? scores[i] : null,
          executionId: i < 6 ? 'demo-execution-seed' : null,
          roundId: i < 6 ? 'demo-round-seed' : 'demo-human-round',
          packageId: i < 6 ? null : 'demo-old-package',
          assignmentId: i < 6 ? null : 'demo-old-assignment',
          reviewable: true,
          parentResultId: null,
        };
        db.results.push(result);
        s.currentResultId = result.resultId;
      }
    }
    // Independent historical answers for strict-majority and duplicate-source checks.
    db.results.push(
      {
        ...db.results[0],
        resultId: 'demo-vote-human-a',
        sourceKind: 'HUMAN',
        sourceActor: 'demo-human-a',
        confidence: null,
        modelVersion: null,
        executionId: null,
        result: { labelId: 'normal' },
      },
      {
        ...db.results[0],
        resultId: 'demo-vote-human-b',
        sourceKind: 'HUMAN',
        sourceActor: 'demo-human-b',
        confidence: null,
        modelVersion: null,
        executionId: null,
        result: { labelId: 'defect' },
      },
    );
    db.executions.push({
      executionId: 'demo-execution-seed',
      roundId: 'demo-round-seed',
      status: 'PARTIAL',
      sampleIds: db.samples.slice(0, 8).map((s) => s.sampleId),
      resultIds: db.results
        .filter(
          (r) =>
            r.executionId === 'demo-execution-seed' && r.sourceKind === 'AI',
        )
        .map((r) => r.resultId),
      resultSnapshotId: 'demo-result-snapshot-seed',
      modelVersion: 'synthetic-v1',
      sampleCount: 8,
      successCount: 6,
      failedCount: 2,
      pendingCount: 0,
      revision: 3,
      errorSummary: '2 个合成失败样本，没有答案',
    });
    return snapshot();
  }
  const sample = (id) => {
    const s = db.samples.find((v) => v.sampleId === id);
    if (!s) fail(404, 'RESOURCE_NOT_FOUND', '样本不存在或不属于演示项目');
    return s;
  };
  const result = (id) => {
    const r = db.results.find((v) => v.resultId === id);
    if (!r) fail(404, 'RESOURCE_NOT_FOUND', '结果版本不存在');
    return r;
  };
  const row = (s) => {
    const candidates = db.results.filter(
      (r) => r.sampleId === s.sampleId && r.reviewable,
    );
    const display = s.currentResultId
      ? result(s.currentResultId)
      : candidates.length === 1
        ? candidates[0]
        : null;
    return {
      ...s,
      ...(display
        ? display
        : {
            resultId: null,
            result: null,
            sourceKind: null,
            sourceActor: null,
            modelVersion: null,
            confidence: null,
            reviewable: false,
          }),
      specVersionId: db.specVersionId,
      hasResult: candidates.length > 0,
      selectedResultId: s.currentResultId,
      candidateCount: candidates.length,
      requiresResultSelection: !s.currentResultId && candidates.length > 1,
    };
  };
  function snapshot() {
    check();
    if (!db) return reset();
    return clone({ ...db, rows: db.samples.map(row) });
  }
  function reviewItem(s, id = row(s).resultId) {
    return {
      sampleId: s.sampleId,
      resultId: id,
      reviewRevision: s.reviewRevision,
    };
  }
  const validSpec = (spec) => {
    if (spec !== db.specVersionId) fail(422, 'SPEC_INCOMPATIBLE', '规范不匹配');
  };
  const requireReason = (reason) => {
    if (
      typeof reason !== 'string' ||
      !reason.trim() ||
      reason.trim().length > 500
    )
      fail(400, 'INVALID_REQUEST', '请填写 1–500 字原因');
  };
  function selection(id, purpose) {
    const s = db.selections.find((v) => v.selectionId === id);
    if (!s) fail(404, 'RESOURCE_NOT_FOUND', '选择快照不存在');
    if (s.expiry <= now())
      fail(410, 'SELECTION_EXPIRED', '选择已过期，请重新预览');
    if (purpose && s.purpose !== purpose)
      fail(422, 'SELECTION_PURPOSE_MISMATCH', '选择用途不匹配');
    return s;
  }
  function validateItems(
    items,
    { needsResult = false, reviewable = false, inactive = false } = {},
  ) {
    if (
      !Array.isArray(items) ||
      !items.length ||
      new Set(items.map((i) => i.sampleId)).size !== items.length
    )
      fail(422, 'EMPTY_OR_DUPLICATE_SELECTION', '选择须非空且无重复样本');
    return items.map((i) => {
      const s = sample(i.sampleId);
      if (i.reviewRevision !== s.reviewRevision)
        fail(409, 'REVIEW_CONFLICT', '审核版本变化，整个操作拒绝');
      if (inactive && s.activeProcessing)
        fail(409, 'SAMPLE_BUSY', '范围有活动处理，须调整选择');
      if (needsResult && !i.resultId)
        fail(422, 'FINAL_RESULTS_INCOMPLETE', '范围包含没有答案的样本');
      if (i.resultId) {
        const r = result(i.resultId);
        if (r.sampleId !== s.sampleId)
          fail(404, 'RESOURCE_NOT_FOUND', '结果不属于该样本');
        if (reviewable && !r.reviewable)
          fail(409, 'AI_RESULTS_NOT_READY', '运行中输出不可审核');
      }
      return { s, i };
    });
  }
  function filtered(query) {
    const supported = [
      'specVersionId',
      'packageId',
      'roundId',
      'executionId',
      'sourceKind',
      'reviewStatus',
      'hasResult',
      'confidencePolicyId',
      'band',
      'limit',
      'cursor',
    ];
    if ([...query.keys()].some((key) => !supported.includes(key)))
      fail(422, 'INVALID_FILTER', '未知筛选字段，不能静默忽略');
    const policy = query.get('confidencePolicyId')
      ? db.policies.find((p) => p.policyId === query.get('confidencePolicyId'))
      : null;
    if (query.get('confidencePolicyId') && !policy)
      fail(404, 'RESOURCE_NOT_FOUND', '阈值策略不存在');
    const executionId = query.get('executionId') || policy?.executionId;
    let rows = db.samples.map(row);
    if (executionId) {
      const execution = db.executions.find(
        (e) => e.executionId === executionId,
      );
      if (!execution) fail(404, 'RESOURCE_NOT_FOUND', '执行不存在');
      rows = execution.sampleIds.map((id) => {
        const s = sample(id),
          r = execution.resultIds.map(result).find((r) => r.sampleId === id);
        return r
          ? {
              ...row(s),
              ...r,
              hasResult: true,
              candidateCount: 1,
              requiresResultSelection: false,
              reviewStatus:
                s.currentResultId === r.resultId ? s.reviewStatus : 'PENDING',
            }
          : {
              ...row(s),
              resultId: null,
              result: null,
              hasResult: false,
              candidateCount: 0,
              requiresResultSelection: false,
              sourceKind: 'AI',
              executionId,
              roundId: execution.roundId,
              modelVersion: execution.modelVersion,
              confidence: null,
              reviewable: false,
            };
      });
    }
    for (const key of [
      'specVersionId',
      'packageId',
      'roundId',
      'executionId',
      'sourceKind',
      'reviewStatus',
    ])
      if (query.get(key)) rows = rows.filter((r) => r[key] === query.get(key));
    if (query.get('hasResult'))
      rows = rows.filter(
        (r) => r.hasResult === (query.get('hasResult') === 'true'),
      );
    if (policy) {
      rows = rows.filter(
        (r) =>
          r.executionId === policy.executionId &&
          r.modelVersion === policy.modelVersion &&
          r.hasResult,
      );
      if (query.get('band'))
        rows = rows.filter(
          (r) =>
            confidenceBand(
              r.confidence,
              policy.lowerThreshold,
              policy.upperThreshold,
            ) === query.get('band'),
        );
    } else if (query.get('band'))
      fail(422, 'INVALID_THRESHOLDS', '先手动设置绑定执行快照的阈值');
    return rows;
  }
  function page(items, query) {
    const limit = Number(query.get('limit') || 20),
      cursor = query.get('cursor');
    const offset = cursor ? Number(cursor.replace('demo-page-', '')) : 0;
    if (
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > 100 ||
      !Number.isInteger(offset) ||
      offset < 0
    )
      fail(400, 'INVALID_CURSOR', '分页参数错误');
    return {
      items: items.slice(offset, offset + limit),
      nextCursor:
        offset + limit < items.length ? `demo-page-${offset + limit}` : null,
    };
  }
  function createSelection(body) {
    validSpec(body.specVersionId);
    if (!['PUBLISH', 'REVIEW', 'FINALIZE'].includes(body.purpose))
      fail(400, 'INVALID_REQUEST', '未知选择用途');
    if (
      body.purpose === 'REVIEW' &&
      !['ACCEPT', 'NEEDS_REVISION'].includes(body.reviewAction)
    )
      fail(422, 'SELECTION_PURPOSE_MISMATCH', '审核快照必须固定审核动作');
    if (body.purpose !== 'REVIEW' && body.reviewAction)
      fail(422, 'SELECTION_PURPOSE_MISMATCH', '仅审核快照可携带审核动作');
    if (body.items && (body.filter || body.excludeSampleIds))
      fail(400, 'INVALID_REQUEST', '明确选择与筛选输入互斥');
    const exclude = new Set(body.excludeSampleIds || []);
    for (const id of exclude) sample(id);
    const items =
      body.items ||
      filtered(new URLSearchParams(body.filter || {}))
        .filter((r) => !exclude.has(r.sampleId))
        .map((r) => reviewItem(sample(r.sampleId), r.resultId));
    validateItems(items, {
      needsResult: body.purpose === 'REVIEW' && body.reviewAction === 'ACCEPT',
      reviewable: body.purpose === 'REVIEW',
    });
    const s = {
      selectionId: uid('selection'),
      selectionHash: scopeFingerprint({
        projectId: 'demo-project',
        specVersionId: db.specVersionId,
        purpose: body.purpose,
        reviewAction: body.reviewAction || null,
        items: [...items].sort((a, b) => a.sampleId.localeCompare(b.sampleId)),
      }),
      purpose: body.purpose,
      reviewAction: body.reviewAction || null,
      statistics: {
        total: items.length,
        hasResultCount: items.filter((i) => i.resultId).length,
        noResultCount: items.filter((i) => !i.resultId).length,
        acceptedCount: items.filter(
          (i) => sample(i.sampleId).reviewStatus === 'ACCEPTED',
        ).length,
        needsRevisionCount: items.filter(
          (i) => sample(i.sampleId).reviewStatus === 'NEEDS_REVISION',
        ).length,
        activeCount: items.filter((i) => sample(i.sampleId).activeProcessing)
          .length,
      },
      items: clone(items),
      count: items.length,
      expiry: now() + 1800000,
      expiresAt: new Date(now() + 1800000).toISOString(),
    };
    db.selections.push(s);
    return s;
  }
  function publishInput(body) {
    validSpec(body.specVersionId);
    if (!['NONE', 'SELECTED'].includes(body.referenceMode))
      fail(400, 'INVALID_REQUEST', '参考模式必填');
    const s = selection(body.selectionId, 'PUBLISH');
    const entries = validateItems(s.items, { inactive: true });
    if (
      entries.some((e) => e.s.reviewStatus === 'ACCEPTED') &&
      body.reopenAccepted !== true
    )
      fail(409, 'REOPEN_ACCEPTED_REQUIRED', '重发已确认样本须明确确认失效影响');
    return entries;
  }
  function occupy(entries) {
    for (const { s } of entries) {
      s.activeProcessing = true;
      if (s.reviewStatus === 'ACCEPTED') {
        s.reviewStatus = 'PENDING';
        s.reviewRevision++;
      }
    }
  }
  function startAI(body, retryOfExecutionId = null) {
    if (body.agentId !== 'demo-agent')
      fail(422, 'AGENT_INCOMPATIBLE', '只支持合成 Agent，非真实模型');
    if (body.referenceMode === 'SELECTED')
      fail(422, 'REFERENCE_UNSUPPORTED', '此合成 Agent 不支持参考，不自动降级');
    const entries = publishInput(body);
    const id = uid('execution');
    const execution = {
      executionId: id,
      roundId: uid('round'),
      retryOfExecutionId,
      sampleIds: entries.map((e) => e.s.sampleId),
      resultIds: [],
      resultSnapshotId: null,
      status: 'QUEUED',
      modelVersion: 'synthetic-v1',
      sampleCount: entries.length,
      successCount: 0,
      failedCount: 0,
      pendingCount: entries.length,
      revision: 1,
    };
    occupy(entries);
    db.executions.push(execution);
    return execution;
  }
  function advance(execution) {
    if (execution.status === 'QUEUED') {
      execution.status = 'RUNNING';
      execution.revision++;
      return;
    }
    if (execution.status !== 'RUNNING') return;
    for (const id of execution.sampleIds) {
      const s = sample(id);
      s.activeProcessing = false;
      if (Number(id.split('-').at(-1)) % 4 === 0) {
        s.errorCode = 'SYNTHETIC_MODEL_FAILURE';
        execution.failedCount++;
        continue;
      }
      const r = {
        resultId: uid('result'),
        sampleId: id,
        specVersionId: db.specVersionId,
        result: { labelId: 'normal' },
        sourceKind: 'AI',
        sourceActor: 'demo-agent',
        executionId: execution.executionId,
        roundId: execution.roundId,
        modelVersion: 'synthetic-v1',
        confidence: 0.75,
        reviewable: true,
        parentResultId: null,
      };
      db.results.push(r);
      execution.resultIds.push(r.resultId);
      if (!s.currentResultId) s.reviewStatus = 'PENDING';
      s.errorCode = null;
      execution.successCount++;
    }
    execution.pendingCount = 0;
    execution.status = execution.failedCount
      ? execution.successCount
        ? 'PARTIAL'
        : 'FAILED'
      : 'SUCCEEDED';
    execution.resultSnapshotId = uid('result-snapshot');
    execution.revision++;
  }
  function request(
    method,
    path,
    { actor = 'demo-owner', body = {}, key } = {},
  ) {
    check();
    if (!db) reset();
    const url = new URL(path, 'https://synthetic.invalid');
    const route = url.pathname
      .replace(/^\/api\/labeling\/v1/, '')
      .split('/')
      .filter(Boolean);
    if (actor !== 'demo-owner')
      fail(
        403,
        'FORBIDDEN',
        '只有合成需求方可读写演示项目；管理员无原图或审核特权',
      );
    if (route[0] === 'projects' && route[1] !== 'demo-project')
      fail(404, 'RESOURCE_NOT_FOUND', '不接受真实项目 ID');
    if (
      method === 'POST' &&
      db.project.status !== 'OPEN' &&
      !['exports', 'final-snapshots'].includes(route[2]) &&
      !['resume', 'archive', 'cancel'].includes(route[2])
    )
      fail(409, 'PROJECT_PAUSED', '项目不可写');
    const receiptKey = `${actor}:${method}:${url.pathname}:${key}`;
    if (method === 'POST') {
      if (!key || key.length > 200) fail(400, 'INVALID_REQUEST', '缺少幂等键');
      const prior = receipts.get(receiptKey);
      if (prior) {
        if (prior.body !== JSON.stringify(body))
          fail(409, 'IDEMPOTENCY_CONFLICT', '同键不同体');
        return clone(prior.response);
      }
    }
    let data;
    if (route[0] === 'projects') {
      const action = route[2];
      if (method === 'GET' && action === 'results')
        data = page(filtered(url.searchParams), url.searchParams);
      else if (method === 'GET' && action === 'result-statistics') {
        const rows = filtered(url.searchParams);
        data = {
          total: rows.length,
          acceptedCount: rows.filter((r) => r.reviewStatus === 'ACCEPTED')
            .length,
          needsRevisionCount: rows.filter(
            (r) => r.reviewStatus === 'NEEDS_REVISION',
          ).length,
          hasResultCount: rows.filter((r) => r.hasResult).length,
          noResultCount: rows.filter((r) => !r.hasResult).length,
          activeCount: rows.filter((r) => r.activeProcessing).length,
        };
      } else if (
        method === 'GET' &&
        action === 'samples' &&
        route[4] === 'results'
      )
        data = page(
          db.results
            .filter((r) => r.sampleId === sample(route[3]).sampleId)
            .map((r) => ({ ...row(sample(r.sampleId)), ...r })),
          url.searchParams,
        );
      else if (method === 'POST' && action === 'result-selections')
        data = createSelection(body);
      else if (method === 'POST' && action === 'ai-executions')
        data = startAI(body);
      else if (method === 'POST' && action === 'reviews') {
        if (
          !['ACCEPT', 'NEEDS_REVISION'].includes(body.action) ||
          Boolean(body.selectionId) === Boolean(body.items)
        )
          fail(400, 'INVALID_REQUEST', '审核动作或互斥输入无效');
        if (body.action === 'NEEDS_REVISION') requireReason(body.reason);
        if (
          body.selectionId &&
          selection(body.selectionId, 'REVIEW').reviewAction !== body.action
        )
          fail(422, 'SELECTION_PURPOSE_MISMATCH', '审核动作与已预览快照不匹配');
        const items = body.selectionId
          ? selection(body.selectionId, 'REVIEW').items
          : body.items.map((i) => ({
              ...i,
              reviewRevision: i.baseReviewRevision,
            }));
        const entries = validateItems(items, {
          needsResult: body.action === 'ACCEPT',
          reviewable: true,
          inactive: true,
        });
        if (body.confidencePolicyId) {
          const p = db.policies.find(
            (p) => p.policyId === body.confidencePolicyId,
          );
          if (
            !p ||
            entries.some(
              (e) =>
                !e.i.resultId ||
                result(e.i.resultId).executionId !== p.executionId ||
                result(e.i.resultId).modelVersion !== p.modelVersion,
            )
          )
            fail(409, 'REVIEW_CONFLICT', '阈值与执行快照不匹配');
        }
        const batchId = uid('review');
        for (const { s, i } of entries) {
          if (i.resultId) s.currentResultId = i.resultId;
          s.reviewStatus =
            body.action === 'ACCEPT' ? 'ACCEPTED' : 'NEEDS_REVISION';
          s.reviewRevision++;
          s.reviewedBy = actor;
          s.reviewBatchId = batchId;
        }
        data = { affectedCount: entries.length, reviewBatchId: batchId };
      } else if (
        method === 'POST' &&
        action === 'results' &&
        route[4] === 'revisions'
      ) {
        const original = result(route[3]),
          s = sample(original.sampleId);
        validateItems(
          [
            {
              sampleId: s.sampleId,
              resultId: original.resultId,
              reviewRevision: body.baseReviewRevision,
            },
          ],
          { inactive: true },
        );
        requireReason(body.reason);
        if (
          body.resultFormat !== 'image-classification-v1' ||
          !db.labels.some((l) => l.id === body.result?.labelId)
        )
          fail(422, 'INVALID_RESULT', '请选择规范内单个标签');
        const r = {
          ...original,
          resultId: uid('result'),
          result: clone(body.result),
          sourceKind: 'OWNER',
          sourceActor: actor,
          parentResultId: original.resultId,
          confidence: null,
          modelVersion: null,
          executionId: null,
          packageId: null,
          assignmentId: null,
          roundId: null,
          reviewable: true,
        };
        db.results.push(r);
        s.currentResultId = r.resultId;
        s.reviewStatus = 'PENDING';
        s.reviewRevision++;
        data = {
          resultId: r.resultId,
          parentResultId: r.parentResultId,
          reviewStatus: 'PENDING',
          reviewRevision: s.reviewRevision,
        };
      } else if (method === 'POST' && action === 'package-plans') {
        if (
          body.batchId ||
          body.annotatorsPerSample !== 1 ||
          !Number.isInteger(body.packageSize) ||
          body.packageSize < 1 ||
          body.packageSize > 1000
        )
          fail(422, 'INVALID_PLAN', '新版选样不能混 batchId，首期每包一人');
        const entries = publishInput(body),
          count = entries.length;
        data = {
          planId: uid('plan'),
          selectionHash: uid('plan-hash'),
          sampleCount: count,
          packageSampleCounts: Array.from(
            { length: Math.ceil(count / body.packageSize) },
            (_, i) => Math.min(body.packageSize, count - i * body.packageSize),
          ),
          acceptedSampleCount: entries.filter(
            (e) => e.s.reviewStatus === 'ACCEPTED',
          ).length,
          input: clone(body),
          items: clone(selection(body.selectionId).items),
          status: 'DRAFT',
          packages: [],
        };
        db.plans.push(data);
      } else if (method === 'POST' && action === 'final-snapshots') {
        const chosen = selection(body.selectionId, 'FINALIZE'),
          entries = validateItems(chosen.items, {
            needsResult: true,
            reviewable: true,
            inactive: true,
          });
        if (
          entries.some(
            (e) =>
              e.s.reviewStatus !== 'ACCEPTED' ||
              e.s.currentResultId !== e.i.resultId,
          )
        )
          fail(
            422,
            'FINAL_RESULTS_INCOMPLETE',
            '选择范围须全部已确认；不能静默排除缺项',
          );
        data = {
          finalSnapshotId: uid('final'),
          name: body.name || '合成成果',
          sampleCount: entries.length,
          specVersionId: db.specVersionId,
          scopeHash: chosen.selectionHash,
          createdAt: new Date(now()).toISOString(),
          items: entries.map((e) => ({ ...clone(row(e.s)), ...clone(e.i) })),
        };
        db.finals.push(data);
      } else if (method === 'GET' && action === 'final-snapshots')
        data = page(db.finals, url.searchParams);
      else if (method === 'POST' && action === 'exports') {
        if (
          body.format !== 'classification-csv-zip' ||
          body.includeOriginals !== false ||
          body.scope ||
          body.submissionIds ||
          !Array.isArray(body.finalSnapshotIds) ||
          !body.finalSnapshotIds.length
        )
          fail(
            422,
            'UNSUPPORTED_FORMAT',
            '只导出明确成果快照，CSV＋manifest；合成演示不含原图',
          );
        const finals = body.finalSnapshotIds.map((id) => {
          const f = db.finals.find((f) => f.finalSnapshotId === id);
          if (!f) fail(404, 'RESOURCE_NOT_FOUND', '成果不存在');
          return f;
        });
        const rows = new Map();
        for (const f of finals)
          for (const i of f.items) {
            if (
              rows.has(i.sampleId) &&
              rows.get(i.sampleId).resultId !== i.resultId
            )
              fail(422, 'CONFLICTING_SNAPSHOTS', '重叠样本有不同答案');
            rows.set(i.sampleId, clone(i));
          }
        data = {
          exportId: uid('export'),
          status: 'QUEUED',
          sampleCount: rows.size,
          rows: [...rows.values()],
          finalSnapshotIds: [...body.finalSnapshotIds],
          expiresAt: new Date(now() + 60000).toISOString(),
        };
        db.exports.push(data);
      } else if (method === 'POST' && action === 'aggregations') {
        validSpec(body.specVersionId);
        if (
          body.algorithmId !== 'majority-vote-v1' ||
          !Array.isArray(body.inputs) ||
          !body.inputs.length
        )
          fail(422, 'INVALID_ALGORITHM', '需要固定样本及独立答案');
        const items = body.inputs.map((input) => {
          sample(input.sampleId);
          if (
            !Array.isArray(input.resultIds) ||
            new Set(input.resultIds).size !== input.resultIds.length
          )
            fail(422, 'NON_INDEPENDENT_INPUT', '重复结果');
          const answers = input.resultIds.map((id) => result(id));
          if (
            answers.some((r) => r.sampleId !== input.sampleId || !r.reviewable)
          )
            fail(422, 'INVALID_RESULT', '结果样本或状态不匹配');
          return {
            sampleId: input.sampleId,
            ...majorityVote(answers),
            suggestedResultId: null,
          };
        });
        data = { aggregationId: uid('aggregation'), status: 'QUEUED', items };
        db.aggregations.push(data);
      } else if (
        method === 'POST' &&
        ['pause', 'resume', 'archive'].includes(action)
      ) {
        if (db.project.revision !== body.baseRevision)
          fail(409, 'REVISION_CONFLICT', '项目版本冲突');
        requireReason(body.reason);
        if (
          db.project.status === 'ARCHIVED' ||
          (action === 'pause' && db.project.status !== 'OPEN') ||
          (action === 'resume' && db.project.status !== 'PAUSED')
        )
          fail(409, 'PROJECT_STATE_CONFLICT', '状态转换无效');
        if (action === 'archive' && db.samples.some((s) => s.activeProcessing))
          fail(409, 'RESOURCE_BUSY', '活动处理未结束');
        db.project.status = {
          pause: 'PAUSED',
          resume: 'OPEN',
          archive: 'ARCHIVED',
        }[action];
        db.project.revision++;
        data = db.project;
      } else
        fail(404, 'RESOURCE_NOT_FOUND', '演示未提供该能力，不回退真实服务');
    } else if (route[0] === 'result-selections' && method === 'GET')
      data =
        route[2] === 'statistics'
          ? selection(route[1]).statistics
          : page(selection(route[1]).items, url.searchParams);
    else if (route[0] === 'agents' && method === 'GET')
      data = [
        {
          agentId: 'demo-agent',
          displayName: '合成 Agent（不运行模型）',
          modelVersion: 'synthetic-v1',
          available: true,
          supportsReference: false,
          supportedLabels: db.labels.map((l) => l.id),
          confidenceDescription:
            '人为注入的测试分数，仅验证交互，不是模型置信度证据',
        },
      ];
    else if (route[0] === 'ai-executions') {
      const e = db.executions.find((e) => e.executionId === route[1]);
      if (!e) fail(404, 'RESOURCE_NOT_FOUND', '执行不存在');
      if (method === 'GET' && route.length === 2) {
        if (db.project.status === 'OPEN') advance(e);
        data = e;
      } else if (method === 'GET' && route[2] === 'results')
        data = {
          ...page(
            e.sampleIds.map((id) => {
              const r = e.resultIds.map(result).find((r) => r.sampleId === id);
              return r
                ? { ...row(sample(id)), ...r, hasResult: true }
                : {
                    ...row(sample(id)),
                    resultId: null,
                    result: null,
                    hasResult: false,
                    confidence: null,
                    reviewable: false,
                    errorCode: e.resultSnapshotId
                      ? sample(id).errorCode || 'CANCELLED_INCOMPLETE'
                      : null,
                  };
            }),
            url.searchParams,
          ),
          provisional: !e.resultSnapshotId,
          reviewable: Boolean(e.resultSnapshotId),
          snapshotId: e.resultSnapshotId,
        };
      else if (method === 'POST' && route[2] === 'cancel') {
        requireReason(body.reason);
        if (e.revision !== body.baseRevision)
          fail(409, 'REVISION_CONFLICT', '执行版本冲突');
        if (!['QUEUED', 'RUNNING'].includes(e.status))
          fail(409, 'EXECUTION_CLOSED', '执行已结束');
        e.status = 'CANCELLED';
        e.resultSnapshotId = uid('result-snapshot');
        e.revision++;
        for (const id of e.sampleIds) sample(id).activeProcessing = false;
        data = e;
      } else if (method === 'POST' && route[2] === 'retries') {
        if (
          e.revision !== body.baseRevision ||
          !['PARTIAL', 'FAILED', 'CANCELLED'].includes(e.status)
        )
          fail(409, 'REVISION_CONFLICT', '仅终结执行可创建新重试');
        const sel = selection(body.selectionId, 'PUBLISH');
        const successful = new Set(
          e.resultIds.map((id) => result(id).sampleId),
        );
        if (
          sel.items.some(
            (i) =>
              !e.sampleIds.includes(i.sampleId) || successful.has(i.sampleId),
          )
        )
          fail(
            422,
            'INVALID_RETRY_SCOPE',
            '重试仅接受该执行失败/取消未完成范围',
          );
        data = startAI(
          {
            ...body,
            agentId: 'demo-agent',
            specVersionId: db.specVersionId,
            referenceMode: 'NONE',
            reopenAccepted: false,
          },
          e.executionId,
        );
      } else fail(404, 'RESOURCE_NOT_FOUND', '未知执行操作');
    } else if (
      route[0] === 'rounds' &&
      route[2] === 'confidence-policies' &&
      method === 'POST'
    ) {
      const e = db.executions.find(
        (e) => e.executionId === body.executionId && e.roundId === route[1],
      );
      if (!e?.resultSnapshotId || e.resultSnapshotId !== body.resultSnapshotId)
        fail(409, 'AI_RESULTS_NOT_READY', '只可针对已终结执行快照设置阈值');
      confidenceBand(null, body.lowerThreshold, body.upperThreshold);
      data = {
        policyId: uid('policy'),
        ...clone(body),
        modelVersion: e.modelVersion,
        revision: db.policies.length + 1,
      };
      db.policies.push(data);
    } else if (
      route[0] === 'package-plans' &&
      route[2] === 'publish' &&
      method === 'POST'
    ) {
      const plan = db.plans.find((p) => p.planId === route[1]);
      if (!plan) fail(404, 'RESOURCE_NOT_FOUND', '计划不存在');
      if (plan.selectionHash !== body.selectionHash)
        fail(409, 'SELECTION_CONFLICT', '预览摘要变化');
      if (plan.status === 'PUBLISHED') data = plan;
      else {
        const entries = publishInput(plan.input);
        occupy(entries);
        let offset = 0;
        plan.packages = plan.packageSampleCounts.map((count) => {
          const members = plan.items.slice(offset, offset + count);
          offset += count;
          const p = {
            packageId: uid('package'),
            roundId: uid('round'),
            sampleCount: count,
            status: 'OPEN',
            referenceMode: plan.input.referenceMode,
            members: members.map((i) => ({
              sampleId: i.sampleId,
              ...(plan.input.referenceMode === 'SELECTED'
                ? { referenceResultId: i.resultId }
                : {}),
            })),
          };
          db.packages.push(p);
          return p;
        });
        plan.status = 'PUBLISHED';
        data = plan;
      }
    } else if (route[0] === 'aggregation-algorithms' && method === 'GET')
      data = [
        {
          algorithmId: 'majority-vote-v1',
          description:
            '严格过半；至少两份独立答案；voteSupport不是模型confidence；建议不自动审核',
        },
      ];
    else if (route[0] === 'aggregations' && method === 'GET') {
      const a = db.aggregations.find((a) => a.aggregationId === route[1]);
      if (!a) fail(404, 'RESOURCE_NOT_FOUND', '集成不存在');
      if (a.status === 'QUEUED') {
        a.status = 'SUCCEEDED';
        for (const i of a.items)
          if (i.status === 'CONSENSUS') {
            const r = {
              resultId: uid('result'),
              sampleId: i.sampleId,
              specVersionId: db.specVersionId,
              result: { labelId: i.labelId },
              sourceKind: 'AGGREGATION',
              sourceActor: 'demo-aggregation',
              confidence: null,
              modelVersion: null,
              reviewable: true,
            };
            db.results.push(r);
            i.suggestedResultId = r.resultId;
          }
      }
      data =
        route[2] === 'items'
          ? page(a.items, url.searchParams)
          : { aggregationId: a.aggregationId, status: a.status };
    } else if (route[0] === 'exports' && method === 'GET') {
      const job = db.exports.find((e) => e.exportId === route[1]);
      if (!job) fail(404, 'RESOURCE_NOT_FOUND', '导出不存在');
      if (route[2] === 'download') {
        if (job.status !== 'SUCCEEDED')
          fail(409, 'EXPORT_NOT_READY', '导出未完成');
        if (Date.parse(job.expiresAt) <= now())
          fail(410, 'EXPORT_EXPIRED', '导出产物已过期');
        const fields = [
          'sampleId',
          'sourcePath',
          'labelId',
          'labelName',
          'resultId',
          'sourceKind',
          'sourceActor',
          'modelVersion',
          'reviewedBy',
          'reviewBatchId',
          'reviewRevision',
          'specVersionId',
        ];
        const csvEscape = (v) => `"${String(v ?? '').replaceAll('"', '""')}"`;
        data = {
          'classifications.csv':
            '\uFEFF' +
            fields.join(',') +
            '\r\n' +
            job.rows
              .map((r) =>
                fields
                  .map((f) =>
                    csvEscape(
                      f === 'labelId'
                        ? r.result.labelId
                        : f === 'labelName'
                          ? db.labels.find((l) => l.id === r.result.labelId)
                              ?.name
                          : r[f],
                    ),
                  )
                  .join(','),
              )
              .join('\r\n'),
          'manifest.json': JSON.stringify(
            {
              synthetic: true,
              projectId: 'demo-project',
              finalSnapshotIds: job.finalSnapshotIds,
              sampleCount: job.sampleCount,
              scope: '固定范围，不代表全项目完成',
              originalsIncluded: false,
              labels: db.labels,
              records: job.rows,
            },
            null,
            2,
          ),
        };
      } else {
        job.status = job.fail
          ? 'FAILED'
          : job.status === 'QUEUED'
            ? 'RUNNING'
            : 'SUCCEEDED';
        job.errorCode = job.fail ? 'SYNTHETIC_STORAGE_FAILURE' : undefined;
        data = job;
      }
    } else fail(404, 'RESOURCE_NOT_FOUND', '不模拟已有身份/草稿/提交接口');
    if (method === 'POST')
      db.audit.unshift({
        operation: `${method} ${url.pathname}`,
        actorId: actor,
        createdAt: new Date(now()).toISOString(),
      });
    const response = { data: clone(data), requestId: uid('request') };
    if (method === 'POST')
      receipts.set(receiptKey, {
        body: JSON.stringify(body),
        response: clone(response),
      });
    return response;
  }
  return {
    request,
    snapshot,
    reset,
    setExportFault(id, { failed = false, expired = false } = {}) {
      check();
      const job = db.exports.find((e) => e.exportId === id);
      if (!job) fail(404, 'RESOURCE_NOT_FOUND', '任务不存在');
      job.fail = failed;
      if (expired) job.expiresAt = new Date(now() - 1).toISOString();
    },
    finishHumanPackage(id) {
      check();
      const p = db.packages.find((p) => p.packageId === id);
      if (p?.status !== 'OPEN') fail(409, 'ASSIGNMENT_CLOSED', '包不可交回');
      if (db.project.status !== 'OPEN')
        fail(409, 'PROJECT_PAUSED', '项目不可写');
      // Explicit test control, not a browser call to a claimed backend API.
      const assignmentId = uid('assignment');
      for (const item of p.members) {
        const s = sample(item.sampleId);
        const r = {
          resultId: uid('human-result'),
          sampleId: s.sampleId,
          specVersionId: db.specVersionId,
          result: { labelId: 'normal' },
          sourceKind: 'HUMAN',
          sourceActor: 'demo-human-a',
          packageId: p.packageId,
          assignmentId,
          roundId: p.roundId,
          confidence: null,
          modelVersion: null,
          reviewable: true,
          referenceResultId: item.referenceResultId || null,
        };
        db.results.push(r);
        s.activeProcessing = false;
        s.reviewStatus = 'PENDING';
      }
      p.status = 'SUBMITTED';
      return clone(p);
    },
  };
}
