import assert from 'node:assert/strict';
import test from 'node:test';
import {
  confidenceBand,
  createWorkflowMock,
  majorityVote,
} from './labelingWorkflowMock.mjs';
const prefix = '/api/labeling/v1';
function setup(options = {}) {
  const mock = createWorkflowMock({
    enabled: true,
    production: false,
    ...options,
  });
  const get = (path, actor) =>
    mock.request('GET', prefix + path, { actor }).data;
  const post = (path, body, key = crypto.randomUUID(), actor) =>
    mock.request('POST', prefix + path, { body, key, actor }).data;
  const rows = () => get('/projects/demo-project/results').items;
  const select = (purpose, ids, extra = {}) =>
    post('/projects/demo-project/result-selections', {
      purpose,
      specVersionId: 'demo-spec-v1',
      ...(ids
        ? {
            items: rows()
              .filter((r) => ids.includes(r.sampleId))
              .map((r) => ({
                sampleId: r.sampleId,
                resultId: r.resultId,
                reviewRevision: r.reviewRevision,
              })),
          }
        : { filter: {} }),
      ...extra,
    });
  const review = (ids) =>
    post('/projects/demo-project/reviews', {
      action: 'ACCEPT',
      selectionId: select('REVIEW', ids).selectionId,
    });
  const finalize = (ids) =>
    post('/projects/demo-project/final-snapshots', {
      selectionId: select('FINALIZE', ids).selectionId,
      name: '测试范围成果',
    });
  const exp = (ids) =>
    post('/projects/demo-project/exports', {
      finalSnapshotIds: ids,
      format: 'classification-csv-zip',
      includeOriginals: false,
    });
  return { mock, get, post, rows, select, review, finalize, exp };
}
test('development isolation, production rejection, unknown real IDs, admin lacks owner access', () => {
  for (const mock of [
    createWorkflowMock(),
    createWorkflowMock({ enabled: true, production: true }),
  ])
    assert.throws(
      () => mock.reset(),
      (e) => e.code === 'MOCK_DISABLED',
    );
  const { get } = setup();
  assert.throws(
    () => get('/projects/real-uuid/results'),
    (e) => e.status === 404,
  );
  assert.throws(
    () => get('/projects/demo-project/results', 'demo-admin'),
    (e) => e.status === 403,
  );
  assert.throws(
    () => get('/me'),
    (e) => e.status === 404,
  );
});
test('manual thresholds, exact boundaries, unknown scores; saving policies never accepts results', () => {
  assert.deepEqual(
    [0, 0.59, 0.6, 0.899, 0.9, 1, null].map((c) => confidenceBand(c, 0.6, 0.9)),
    ['LOW', 'LOW', 'MEDIUM', 'MEDIUM', 'HIGH', 'HIGH', 'UNKNOWN'],
  );
  for (const [l, h] of [
    [null, 0.9],
    [0.6, null],
    [-0.1, 0.9],
    [0.9, 0.9],
    [0.9, 0.6],
    [0, 1.1],
  ])
    assert.throws(
      () => confidenceBand(0.7, l, h),
      (e) => e.code === 'INVALID_THRESHOLDS',
    );
  const { post, get, rows } = setup();
  const policy = post('/rounds/demo-round-seed/confidence-policies', {
    executionId: 'demo-execution-seed',
    resultSnapshotId: 'demo-result-snapshot-seed',
    lowerThreshold: 0.6,
    upperThreshold: 0.9,
  });
  assert.equal(
    get(
      `/projects/demo-project/results?confidencePolicyId=${policy.policyId}&band=HIGH`,
    ).items.length,
    1,
  );
  assert.equal(
    get(
      `/projects/demo-project/results?confidencePolicyId=${policy.policyId}&band=UNKNOWN`,
    ).items.length,
    1,
  );
  assert.equal(rows().filter((r) => r.reviewStatus === 'ACCEPTED').length, 0);
  assert.throws(
    () =>
      post('/rounds/demo-round-seed/confidence-policies', {
        executionId: 'demo-execution-seed',
        resultSnapshotId: 'wrong',
        lowerThreshold: 0,
        upperThreshold: 1,
      }),
    (e) => e.code === 'AI_RESULTS_NOT_READY',
  );
});
test('selection exclusion is frozen; stale review rejects the whole batch; expiry is explicit', () => {
  let clock = 0;
  const { post, get, select, review, rows } = setup({ now: () => clock });
  const chosen = select('REVIEW', null, {
    filter: { sourceKind: 'AI' },
    excludeSampleIds: ['demo-sample-1'],
  });
  assert.equal(chosen.count, 5);
  assert.equal(
    get(`/result-selections/${chosen.selectionId}/items`).items.some(
      (i) => i.sampleId === 'demo-sample-1',
    ),
    false,
  );
  review(['demo-sample-2']);
  assert.throws(
    () =>
      post('/projects/demo-project/reviews', {
        action: 'ACCEPT',
        selectionId: chosen.selectionId,
      }),
    (e) => e.code === 'REVIEW_CONFLICT',
  );
  assert.equal(rows().filter((r) => r.reviewStatus === 'ACCEPTED').length, 1);
  const fresh = select('REVIEW', ['demo-sample-1']);
  clock = 300001;
  assert.throws(
    () => get(`/result-selections/${fresh.selectionId}/items`),
    (e) => e.status === 410,
  );
});
test('owner revisions preserve original AI confidence, make new score null, require explicit acceptance', () => {
  const { post, get, rows } = setup();
  const revised = post(
    '/projects/demo-project/results/demo-result-1/revisions',
    {
      baseReviewRevision: 0,
      resultFormat: 'image-classification-v1',
      result: { labelId: 'defect' },
      reason: '人工核查更正',
    },
  );
  const current = rows()[0];
  assert.equal(current.resultId, revised.resultId);
  assert.equal(current.sourceKind, 'OWNER');
  assert.equal(current.confidence, null);
  assert.equal(current.reviewStatus, 'PENDING');
  const old = get(
    '/projects/demo-project/samples/demo-sample-1/results',
  ).items.find((r) => r.resultId === 'demo-result-1');
  assert.equal(old.result.labelId, 'normal');
  assert.equal(old.confidence, 0);
  assert.throws(
    () =>
      post('/projects/demo-project/results/demo-result-1/revisions', {
        baseReviewRevision: 0,
        resultFormat: 'image-classification-v1',
        result: { labelId: 'normal' },
        reason: '过期修改',
      }),
    (e) => e.code === 'REVIEW_CONFLICT',
  );
});

test('confidence selection remains bound to the immutable AI output after owner revisions', () => {
  const { post, get } = setup();
  const p = post('/rounds/demo-round-seed/confidence-policies', {
    executionId: 'demo-execution-seed',
    resultSnapshotId: 'demo-result-snapshot-seed',
    lowerThreshold: 0.6,
    upperThreshold: 0.9,
  });
  post('/projects/demo-project/results/demo-result-1/revisions', {
    baseReviewRevision: 0,
    resultFormat: 'image-classification-v1',
    result: { labelId: 'defect' },
    reason: 'owner creates separate candidate',
  });
  const filter = { confidencePolicyId: p.policyId, band: 'LOW' };
  const scoped = get(
    `/projects/demo-project/results?${new URLSearchParams(filter)}`,
  ).items;
  assert.equal(scoped.length, 2);
  assert.equal(
    scoped.find((r) => r.sampleId === 'demo-sample-1').resultId,
    'demo-result-1',
  );
  const s = post('/projects/demo-project/result-selections', {
    purpose: 'REVIEW',
    specVersionId: 'demo-spec-v1',
    filter,
  });
  const selected = get(`/result-selections/${s.selectionId}/items`).items;
  assert.equal(
    selected.find((r) => r.sampleId === 'demo-sample-1').resultId,
    'demo-result-1',
  );
  assert.equal(
    selected.find((r) => r.sampleId === 'demo-sample-1').reviewRevision,
    1,
  );
});
test('final scope requires all accepted and inactive; AI-only final export has CSV + manifest and immutable rows', () => {
  const { select, finalize, review, rows, exp, get, post } = setup();
  assert.throws(
    () => finalize(rows().map((r) => r.sampleId)),
    (e) => e.code === 'FINAL_RESULTS_INCOMPLETE',
  );
  assert.throws(
    () => finalize(['demo-sample-1']),
    (e) => e.code === 'FINAL_RESULTS_INCOMPLETE',
  );
  review(['demo-sample-1']);
  const final = finalize(['demo-sample-1']);
  assert.equal(final.sampleCount, 1);
  assert.equal(final.items[0].packageId, null);
  assert.equal(final.items[0].assignmentId, null);
  post('/projects/demo-project/results/demo-result-1/revisions', {
    baseReviewRevision: 1,
    resultFormat: 'image-classification-v1',
    result: { labelId: 'defect' },
    reason: '新的修订不改旧成果',
  });
  const job = exp([final.finalSnapshotId]);
  assert.throws(
    () => get(`/exports/${job.exportId}/download`),
    (e) => e.status === 409,
  );
  assert.equal(get(`/exports/${job.exportId}`).status, 'RUNNING');
  assert.equal(get(`/exports/${job.exportId}`).status, 'SUCCEEDED');
  const files = get(`/exports/${job.exportId}/download`);
  assert.deepEqual(Object.keys(files), [
    'classifications.csv',
    'manifest.json',
  ]);
  const manifest = JSON.parse(files['manifest.json']);
  assert.equal(manifest.sampleCount, 1);
  assert.equal(manifest.records[0].result.labelId, 'normal');
  assert.equal(manifest.records[0].reviewStatus, 'ACCEPTED');
  assert.match(files['classifications.csv'], /reviewedBy/);
  assert.equal(select('FINALIZE', ['demo-sample-1']).count, 1);
});
test('12 samples make independent 10+2 human packages; handback is pending, other package remains active', () => {
  const { select, post, mock, rows } = setup();
  const scope = select('PUBLISH');
  const plan = post('/projects/demo-project/package-plans', {
    selectionId: scope.selectionId,
    specVersionId: 'demo-spec-v1',
    packageSize: 10,
    annotatorsPerSample: 1,
    referenceMode: 'NONE',
    reopenAccepted: false,
  });
  assert.deepEqual(plan.packageSampleCounts, [10, 2]);
  const published = post(`/package-plans/${plan.planId}/publish`, {
    selectionHash: plan.selectionHash,
  });
  assert.equal(published.packages.length, 2);
  assert.notEqual(published.packages[0].roundId, published.packages[1].roundId);
  assert.equal(
    published.packages.some((p) =>
      p.members.some((i) => Object.hasOwn(i, 'referenceResultId')),
    ),
    false,
  );
  mock.finishHumanPackage(published.packages[0].packageId);
  assert.equal(rows().filter((r) => r.activeProcessing).length, 2);
  assert.equal(rows().filter((r) => r.reviewStatus === 'ACCEPTED').length, 0);
  assert.equal(mock.snapshot().packages[1].status, 'OPEN');
});
test('accepted re-publication requires explicit reopen; preview does not invalidate, publish does', () => {
  const { review, select, post, rows } = setup();
  review(['demo-sample-1']);
  const scope = select('PUBLISH', ['demo-sample-1']);
  const body = {
    selectionId: scope.selectionId,
    specVersionId: 'demo-spec-v1',
    packageSize: 10,
    annotatorsPerSample: 1,
    referenceMode: 'SELECTED',
    reopenAccepted: false,
  };
  assert.throws(
    () => post('/projects/demo-project/package-plans', body),
    (e) => e.code === 'REOPEN_ACCEPTED_REQUIRED',
  );
  const plan = post('/projects/demo-project/package-plans', {
    ...body,
    reopenAccepted: true,
  });
  assert.equal(rows()[0].reviewStatus, 'ACCEPTED');
  const published = post(`/package-plans/${plan.planId}/publish`, {
    selectionHash: plan.selectionHash,
  });
  assert.equal(rows()[0].reviewStatus, 'PENDING');
  assert.equal(
    published.packages[0].members[0].referenceResultId,
    'demo-result-1',
  );
});
test('AI works without human package; running outputs not reviewable, partial failures have no answer, retry is new', () => {
  const { select, post, get, mock } = setup();
  const chosen = select('PUBLISH', [
    'demo-sample-7',
    'demo-sample-8',
    'demo-sample-9',
    'demo-sample-10',
  ]);
  const body = {
    selectionId: chosen.selectionId,
    specVersionId: 'demo-spec-v1',
    agentId: 'demo-agent',
    referenceMode: 'NONE',
    reopenAccepted: false,
  };
  assert.throws(
    () =>
      post('/projects/demo-project/ai-executions', {
        ...body,
        referenceMode: 'SELECTED',
      }),
    (e) => e.code === 'REFERENCE_UNSUPPORTED',
  );
  const e = post('/projects/demo-project/ai-executions', body);
  assert.equal(e.status, 'QUEUED');
  assert.equal(e.packageId, undefined);
  assert.throws(
    () => post('/projects/demo-project/ai-executions', body),
    (e) => e.code === 'SAMPLE_BUSY',
  );
  get(`/ai-executions/${e.executionId}`);
  const running = get(`/ai-executions/${e.executionId}/results`);
  assert.equal(running.provisional, true);
  assert.equal(
    running.items.some((r) => r.reviewable),
    false,
  );
  const finished = get(`/ai-executions/${e.executionId}`);
  assert.equal(finished.status, 'PARTIAL');
  assert.equal(finished.successCount, 3);
  assert.equal(finished.failedCount, 1);
  const output = get(`/ai-executions/${e.executionId}/results`);
  const failed = output.items.find((r) => !r.hasResult);
  assert.equal(failed.resultId, null);
  assert.equal(failed.result, null);
  assert.ok(failed.errorCode);
  const retryScope = select('PUBLISH', [failed.sampleId]);
  const retry = post(`/ai-executions/${e.executionId}/retries`, {
    selectionId: retryScope.selectionId,
    baseRevision: finished.revision,
  });
  assert.notEqual(retry.executionId, e.executionId);
  assert.equal(retry.retryOfExecutionId, e.executionId);
  assert.equal(
    mock.snapshot().executions.find((v) => v.executionId === e.executionId)
      .resultSnapshotId,
    finished.resultSnapshotId,
  );
  const cancelled = post(`/ai-executions/${retry.executionId}/cancel`, {
    baseRevision: retry.revision,
    reason: '取消合成重试',
  });
  assert.equal(get(`/ai-executions/${retry.executionId}`).status, 'CANCELLED');
  assert.equal(cancelled.status, 'CANCELLED');
  assert.throws(
    () => post(`/ai-executions/${retry.executionId}/results`, {}),
    (e) => e.status === 404,
  );
});
test('strict majority excludes duplicate actors and derived answers; suggestion is not automatic acceptance', () => {
  const vote = (actor, label, parent) => ({
    sourceKind: 'HUMAN',
    sourceActor: actor,
    result: { labelId: label },
    parentResultId: parent,
  });
  assert.equal(
    majorityVote([vote('a', 'normal')]).status,
    'INSUFFICIENT_INPUT',
  );
  assert.equal(
    majorityVote([vote('a', 'normal'), vote('b', 'defect')]).status,
    'NO_CONSENSUS',
  );
  assert.equal(
    majorityVote([
      vote('a', 'normal'),
      vote('b', 'normal'),
      vote('c', 'defect'),
    ]).voteSupport,
    2 / 3,
  );
  assert.throws(
    () => majorityVote([vote('a', 'normal'), vote('a', 'normal')]),
    (e) => e.code === 'NON_INDEPENDENT_INPUT',
  );
  assert.throws(
    () => majorityVote([vote('a', 'normal', 'parent'), vote('b', 'normal')]),
    (e) => e.code === 'NON_INDEPENDENT_INPUT',
  );
  const { post, get, rows } = setup();
  const job = post('/projects/demo-project/aggregations', {
    algorithmId: 'majority-vote-v1',
    specVersionId: 'demo-spec-v1',
    inputs: [
      {
        sampleId: 'demo-sample-1',
        resultIds: ['demo-result-1', 'demo-vote-human-a', 'demo-vote-human-b'],
      },
    ],
  });
  const items = get(`/aggregations/${job.aggregationId}/items`).items;
  assert.equal(items[0].status, 'CONSENSUS');
  assert.equal(rows()[0].reviewStatus, 'PENDING');
  post('/projects/demo-project/reviews', {
    action: 'ACCEPT',
    items: [
      {
        sampleId: 'demo-sample-1',
        resultId: items[0].suggestedResultId,
        baseReviewRevision: 0,
      },
    ],
  });
  assert.equal(rows()[0].sourceKind, 'AGGREGATION');
  assert.equal(rows()[0].confidence, null);
  assert.equal(rows()[0].reviewStatus, 'ACCEPTED');
});
test('export refuses unreviewed submission scope, originals, conflicting snapshots and handles failed/expired jobs', () => {
  const { post, review, finalize, exp, get, mock } = setup();
  assert.throws(
    () =>
      post('/projects/demo-project/exports', {
        scope: 'ALL_SUBMITTED',
        format: 'native-json',
        includeOriginals: false,
      }),
    (e) => e.status === 422,
  );
  review(['demo-sample-1']);
  const first = finalize(['demo-sample-1']);
  assert.throws(
    () =>
      post('/projects/demo-project/exports', {
        finalSnapshotIds: [first.finalSnapshotId],
        format: 'classification-csv-zip',
        includeOriginals: true,
      }),
    (e) => e.status === 422,
  );
  const revised = post(
    '/projects/demo-project/results/demo-result-1/revisions',
    {
      baseReviewRevision: 1,
      resultFormat: 'image-classification-v1',
      result: { labelId: 'defect' },
      reason: '修正',
    },
  );
  review(['demo-sample-1']);
  const second = finalize(['demo-sample-1']);
  assert.ok(revised.resultId);
  assert.throws(
    () => exp([first.finalSnapshotId, second.finalSnapshotId]),
    (e) => e.code === 'CONFLICTING_SNAPSHOTS',
  );
  const failed = exp([first.finalSnapshotId]);
  mock.setExportFault(failed.exportId, { failed: true });
  assert.equal(get(`/exports/${failed.exportId}`).status, 'FAILED');
  const expired = exp([second.finalSnapshotId]);
  get(`/exports/${expired.exportId}`);
  get(`/exports/${expired.exportId}`);
  mock.setExportFault(expired.exportId, { expired: true });
  assert.throws(
    () => get(`/exports/${expired.exportId}/download`),
    (e) => e.status === 410,
  );
});
test('write receipts replay original frozen resources and reject changed request bodies', () => {
  const { post } = setup();
  const path = '/projects/demo-project/result-selections',
    body = { purpose: 'PUBLISH', specVersionId: 'demo-spec-v1', filter: {} };
  const original = post(path, body, 'stable');
  assert.deepEqual(post(path, body, 'stable'), original);
  assert.throws(
    () => post(path, { ...body, purpose: 'FINALIZE' }, 'stable'),
    (e) => e.code === 'IDEMPOTENCY_CONFLICT',
  );
});
