import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

const plain = (value) => JSON.parse(JSON.stringify(value));
const row = (extra) => ({
  sampleId: 's',
  specVersionId: 'v',
  resultId: 'result-v1',
  result: { labelId: 'cat' },
  sourceKind: 'HUMAN',
  reviewRevision: 2,
  reviewable: true,
  activeProcessing: false,
  ...extra,
});
function setup(handle = () => ({})) {
  const calls = [];
  const capture = (method, path, body, key) => {
    calls.push({ method, path, body: plain(body ?? null), key });
    return Promise.resolve(handle(method, path, body, key));
  };
  const module = loadTypeScriptModule(
    new URL('./labelingOwner.ts', import.meta.url),
    {
      './labeling': {
        labelingJson: (path, init) =>
          capture(
            init?.method || 'GET',
            path,
            init?.body ? JSON.parse(init.body) : undefined,
            init?.headers?.['Idempotency-Key'],
          ),
        post: (path, body, key) => capture('POST', path, body, key),
        pageQuery: (cursor) =>
          new URLSearchParams({
            limit: '20',
            ...(cursor ? { cursor } : {}),
          }).toString(),
      },
    },
  );
  return { ...module, calls };
}
test('live queries bind spec and keep paging parameters out of statistics', async () => {
  const { ownerApi: api, calls } = setup();
  const filter = {
    specVersionId: 'v',
    executionId: 'e',
    confidencePolicyId: 'cp',
    band: 'UNKNOWN',
  };
  await api.results('project/a', filter, 'next');
  await api.statistics('project/a', filter);
  const result = new URL(`https://example.invalid${calls[0].path}`),
    stats = new URL(`https://example.invalid${calls[1].path}`);
  assert.equal(result.pathname, '/projects/project%2Fa/results');
  assert.equal(result.searchParams.get('cursor'), 'next');
  assert.equal(stats.searchParams.get('executionId'), 'e');
  assert.equal(stats.searchParams.has('cursor'), false);
  assert.equal(stats.searchParams.has('limit'), false);
});
test('submission reviews use PUT with an idempotency key and preserve modify payload', async () => {
  const { ownerApi: api, calls } = setup();
  const items = [
    {
      sampleId: 's',
      action: 'MODIFY',
      reason: 'fix',
      result: { labelId: 'cat' },
    },
  ];
  await api.reviewItems('submission/a', items, 'same-key');
  assert.deepEqual(calls, [
    {
      method: 'PUT',
      path: '/submissions/submission%2Fa/review/items',
      body: { items },
      key: 'same-key',
    },
  ]);
});
test('legacy compatibility routes retain explicit samples and snapshotId; current screens use the selection CAS adapter', async () => {
  const { ownerApi: api, calls } = setup();
  const samples = [{ sampleId: 's', referenceResultId: 'submission' }];
  await api.planSamples(
    'p',
    {
      specVersionId: 'v',
      packageSize: 5,
      annotatorsPerSample: 1,
      samples,
      instructions: '',
    },
    'k',
  );
  await api.execute(
    'p',
    { specVersionId: 'v', agentId: 'a', samples, instructions: '' },
    'k2',
  );
  await api.policy(
    'r',
    { snapshotId: 'snapshot', lowerThreshold: 0, upperThreshold: 0.8 },
    'k3',
  );
  await api.finalize(
    'p',
    { specVersionId: 'v', name: 'final', sampleIds: ['s'] },
    'k4',
  );
  assert.equal(calls[0].body.samples[0].referenceResultId, 'submission');
  assert.equal(calls[1].path, '/projects/p/ai-executions');
  assert.equal(calls[2].body.snapshotId, 'snapshot');
  assert.equal(calls[2].body.lowerThreshold, 0);
  assert.deepEqual(calls[3].body.sampleIds, ['s']);
  assert.equal(
    calls.some((call) => 'selectionId' in call.body),
    false,
  );
});
test('round cancel, revoke and AI cancel send the observed revision', async () => {
  const { ownerApi: api, calls } = setup();
  await api.cancelRound('r', { baseRevision: 8, reason: 'cancel' }, 'k');
  await api.revoke('a', { baseRevision: 9, reason: 'cancel' }, 'k2');
  await api.cancelExecution('e', { baseRevision: 10, reason: 'cancel' }, 'k3');
  assert.deepEqual(
    calls.map((call) => call.body.baseRevision),
    [8, 9, 10],
  );
});
test('history resolution reads all pages and binds the exact result, not page position', async () => {
  const { resolveSources, calls, reviewInputs, publishSamples } = setup(
    (_method, path) =>
      path.includes('/review')
        ? { projectId: 'p', submissionId: 'correct-submission', items: [] }
        : path.includes('cursor=next')
          ? {
              items: [row({ submissionId: 'correct-submission' })],
              nextCursor: null,
            }
          : {
              items: [
                row({ resultId: 'another', submissionId: 'wrong-submission' }),
              ],
              nextCursor: 'next',
            },
  );
  const result = await resolveSources('p', [row()], 'v');
  assert.equal(calls.length, 3);
  assert.equal(result[0].submissionId, 'correct-submission');
  assert.deepEqual(plain(reviewInputs(result, 'ACCEPT', 'ok')), [
    {
      sampleId: 's',
      specVersionId: 'v',
      action: 'ACCEPT',
      reason: 'ok',
      sourceKind: 'HUMAN',
      submissionId: 'correct-submission',
    },
  ]);
  assert.deepEqual(plain(publishSamples(result, true)), [
    { sampleId: 's', referenceResultId: 'correct-submission' },
  ]);
});
test('history cannot silently accept changed revisions, active work or wrong spec/sample bindings', async () => {
  for (const change of [
    { reviewRevision: 3 },
    { activeProcessing: true },
    { specVersionId: 'other' },
    { sampleId: 'other' },
    { reviewable: false },
  ]) {
    const { resolveSources } = setup(() => ({
      items: [row({ submissionId: 'sub', ...change })],
      nextCursor: null,
    }));
    await assert.rejects(resolveSources('p', [row()], 'v'), /已变化/);
  }
});
test('a superseded human answer is blocked rather than accepting a different current answer', async () => {
  const { resolveSources } = setup((_, path) =>
    path.endsWith('/review')
      ? {
          projectId: 'p',
          submissionId: 'sub',
          items: [
            { sampleId: 's', action: 'MODIFY', result: { labelId: 'dog' } },
          ],
        }
      : { items: [row({ submissionId: 'sub' })], nextCursor: null },
  );
  await assert.rejects(resolveSources('p', [row()], 'v'), /已被修订/);
});
test('unresolved multiple candidates and unsupported references never become implicit latest answers', async () => {
  const { resolveSources, publishSamples, reviewInputs } = setup();
  await assert.rejects(
    resolveSources('p', [row({ requiresResultSelection: true })], 'v'),
    /明确选择/,
  );
  await assert.rejects(
    resolveSources('p', [row({ resultId: null })], 'v'),
    /明确选择/,
  );
  assert.throws(
    () => publishSamples([row({ sourceKind: 'AI', executionId: 'e' })], true),
    /仅支持人工/,
  );
  assert.throws(() => reviewInputs([row()], 'ACCEPT', ''), /缺少/);
  assert.throws(() => reviewInputs([row()], 'NEEDS_REVISION', '  '), /原因/);
});
test('republish reference reads the immutable human submission even when its current review has a different correction', async () => {
  const { resolveSources, calls, publishSamples } = setup((_, path) =>
    path.endsWith('/review')
      ? {
          projectId: 'p',
          submissionId: 'sub',
          items: [
            { sampleId: 's', action: 'MODIFY', result: { labelId: 'dog' } },
          ],
        }
      : { items: [row({ submissionId: 'sub' })], nextCursor: null },
  );
  const result = await resolveSources('p', [row()], 'v', 'reference');
  assert.deepEqual(plain(publishSamples(result, true)), [
    { sampleId: 's', referenceResultId: 'sub' },
  ]);
  assert.equal(
    calls.some((call) => call.path.endsWith('/review')),
    false,
  );
  await assert.rejects(resolveSources('p', [row()], 'v'), /已被修订/);
});
test('AI acceptance keeps its execution identity and unknown confidence', async () => {
  const { resolveSources, reviewInputs } = setup(() => ({
    items: [
      row({ sourceKind: 'AI', executionId: 'execution', confidence: null }),
    ],
    nextCursor: null,
  }));
  const resolved = await resolveSources('p', [row({ sourceKind: 'AI' })], 'v');
  assert.equal(resolved[0].confidence, null);
  assert.deepEqual(plain(reviewInputs(resolved, 'ACCEPT', '')), [
    {
      sampleId: 's',
      specVersionId: 'v',
      action: 'ACCEPT',
      reason: '',
      sourceKind: 'AI',
      executionId: 'execution',
    },
  ]);
});
test('current owner revisions retain their result identity but review through the underlying human submission', async () => {
  const { resolveSources, reviewInputs, publishSamples } = setup((_, path) =>
    path.endsWith('/review')
      ? {
          projectId: 'p',
          submissionId: 'sub',
          items: [
            { sampleId: 's', action: 'MODIFY', result: { labelId: 'cat' } },
          ],
        }
      : {
          items: [row({ sourceKind: 'OWNER', submissionId: 'sub' })],
          nextCursor: null,
        },
  );
  const resolved = await resolveSources(
    'p',
    [row({ sourceKind: 'OWNER' })],
    'v',
  );
  assert.equal(resolved[0].resultId, 'result-v1');
  assert.equal(reviewInputs(resolved, 'ACCEPT', '')[0].sourceKind, 'HUMAN');
  assert.throws(() => publishSamples(resolved, true), /仅支持人工/);
});
test('fixed selection preview uses fixed items/statistics endpoints, preserving exclusions and versions', async () => {
  const { ownerApi: api, calls } = setup();
  const body = {
    specVersionId: 'v',
    purpose: 'REVIEW',
    reviewAction: 'ACCEPT',
    filter: { executionId: 'e' },
    excludeSampleIds: ['excluded'],
  };
  await api.select('p', body, 'k');
  await api.selectionItems('frozen', 'next');
  await api.selectionStatistics('frozen');
  assert.deepEqual(calls[0].body, body);
  assert.equal(
    calls[1].path,
    '/result-selections/frozen/items?limit=20&cursor=next',
  );
  assert.equal(calls[2].path, '/result-selections/frozen/statistics');
});
test('scope paging rejects loops, duplicate items, missing identity and partial oversized selections', async () => {
  const { readAllPages } = setup();
  let count = 0;
  await assert.rejects(
    readAllPages(
      async () => ({ items: [{ id: String(++count) }], nextCursor: 'loop' }),
      (item) => item.id,
    ),
    /游标重复/,
  );
  await assert.rejects(
    readAllPages(
      async () => ({ items: [{ id: 's' }, { id: 's' }], nextCursor: null }),
      (item) => item.id,
    ),
    /重复/,
  );
  await assert.rejects(
    readAllPages(
      async () => ({ items: [{}], nextCursor: null }),
      (item) => item.id,
    ),
    /编号/,
  );
  await assert.rejects(
    readAllPages(
      async () => ({
        items: Array.from({ length: 10001 }, (_, i) => ({ id: String(i) })),
        nextCursor: null,
      }),
      (item) => item.id,
    ),
    /10000/,
  );
});
test('only server terminal execution states permit stable AI results', () => {
  const { executionTerminal } = setup();
  for (const status of ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'])
    assert.equal(executionTerminal(status), true);
  for (const status of ['QUEUED', 'RUNNING', 'RETRYING', 'unknown'])
    assert.equal(executionTerminal(status), false);
});
test('work page round drift is rejected before drafting or submission', async () => {
  const pages = ['round-1', 'round-2'].map((roundId, i) => ({
    assignmentId: 'a',
    packageId: 'pkg',
    roundId,
    specification: { specVersionId: 'v' },
    items: [{ sampleId: `s${i}` }],
    nextCursor: i ? null : 'next',
  }));
  const { allWorkItems, tasks } = loadTypeScriptModule(
    new URL('./labelingTasks.ts', import.meta.url),
    {
      './labeling': {
        labelingJson: async () => pages.shift(),
        pageQuery: () => 'limit=20',
        post: async (path, body, key) => ({ path, body, key }),
      },
    },
  );
  await assert.rejects(allWorkItems('a'), /发生变化/);
  assert.equal(
    (await tasks.claimRound('round/a', 'k')).path,
    '/rounds/round%2Fa/claims',
  );
  assert.deepEqual(plain((await tasks.release('a', 9, 'reason', 'k')).body), {
    baseRevision: 9,
    reason: 'reason',
  });
});
