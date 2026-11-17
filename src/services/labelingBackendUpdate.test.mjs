import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';
import * as energy from './labelingEnergy.mjs';

const plain = (v) => JSON.parse(JSON.stringify(v));
function setup(handle = () => ({})) {
  const calls = [];
  const send = (method, path, body, key, signal) => {
    calls.push({ method, path, body: plain(body ?? null), key, signal });
    return Promise.resolve(handle(path, body));
  };
  const { ownerApi } = loadTypeScriptModule(
    new URL('./labelingOwner.ts', import.meta.url),
    {
      './labeling': {
        post: (path, body, key) => send('POST', path, body, key),
        labelingJson: (path, init) =>
          send(
            init?.method || 'GET',
            path,
            init?.body ? JSON.parse(init.body) : undefined,
            init?.headers?.['Idempotency-Key'],
            init?.signal,
          ),
        labelingZip: (path, signal) =>
          send('BINARY', path, null, undefined, signal),
        pageQuery: (cursor) =>
          new URLSearchParams({
            limit: '20',
            ...(cursor ? { cursor } : {}),
          }).toString(),
      },
    },
  );
  return { api: ownerApi, calls };
}
test('real scoped publication and AI consume selectionId and referenceMode, never translate resultId to submissionId', async () => {
  const { api, calls } = setup();
  const scope = {
    specVersionId: 'v',
    selectionId: 'selection',
    referenceMode: 'SELECTED',
    reopenAccepted: true,
    instructions: '复核',
  };
  await api.planSelection(
    'project/a',
    { ...scope, packageSize: 5, annotatorsPerSample: 1 },
    'plan-key',
  );
  await api.executeSelection(
    'project/a',
    { ...scope, agentId: 'agent' },
    'ai-key',
  );
  assert.deepEqual(
    calls.map((c) => c.path),
    [
      '/projects/project%2Fa/package-plans',
      '/projects/project%2Fa/ai-executions',
    ],
  );
  for (const call of calls) {
    assert.equal(call.body.selectionId, 'selection');
    assert.equal(call.body.referenceMode, 'SELECTED');
    assert.equal(Object.hasOwn(call.body, 'samples'), false);
    assert.equal(Object.hasOwn(call.body, 'referenceResultId'), false);
  }
  assert.equal(calls[0].key, 'plan-key');
  assert.equal(calls[1].key, 'ai-key');
});
test('atomic review supports fixed scope and explicit result CAS as distinct request bodies', async () => {
  const { api, calls } = setup();
  await api.atomicReview(
    'p',
    { action: 'ACCEPT', selectionId: 'scope', reason: 'checked' },
    'same-key',
  );
  const body = {
    specVersionId: 'v',
    action: 'NEEDS_REVISION',
    reason: 'bad',
    items: [{ sampleId: 's', resultId: null, baseReviewRevision: 7 }],
  };
  await api.atomicReview('p', body, 'next-key');
  assert.equal(calls[0].path, '/projects/p/reviews');
  assert.deepEqual(calls[0].body, {
    action: 'ACCEPT',
    selectionId: 'scope',
    reason: 'checked',
  });
  assert.deepEqual(calls[1].body, body);
});
test('generic owner revision retains parent route, review revision and full structured result', async () => {
  const { api, calls } = setup();
  const result = {
    schemaVersion: '1.0.0',
    annotations: [
      {
        id: 'primary',
        from_name: 'primary_label',
        to_name: 'load_curve',
        type: 'choices',
        value: { labelIds: ['custom'] },
      },
    ],
  };
  const body = {
    specVersionId: 'v',
    sampleId: 's',
    baseReviewRevision: 4,
    resultFormat: 'annotation-record-v1',
    reason: 'corrected',
    result,
  };
  await api.revise('p', 'parent/a', body, 'key');
  assert.equal(calls[0].path, '/projects/p/results/parent%2Fa/revisions');
  assert.deepEqual(calls[0].body, body);
});
test('final snapshots use FINALIZE selection instead of unversioned sampleIds', async () => {
  const { api, calls } = setup();
  await api.finalizeSelection(
    'p',
    { name: 'final', specVersionId: 'v', selectionId: 'f' },
    'key',
  );
  assert.equal(calls[0].path, '/projects/p/final-snapshots');
  assert.deepEqual(calls[0].body, {
    name: 'final',
    specVersionId: 'v',
    selectionId: 'f',
  });
});
test('AI retry uses baseRevision and an optional explicit failed subset', async () => {
  const { api, calls } = setup();
  await api.retry('execution/a', { baseRevision: 5 }, 'retry');
  await api.retry(
    'execution/a',
    { baseRevision: 5, sampleIds: ['failed'], instructions: 'again' },
    'subset',
  );
  assert.equal(calls[0].path, '/ai-executions/execution%2Fa/retries');
  assert.deepEqual(calls[0].body, { baseRevision: 5 });
  assert.deepEqual(calls[1].body.sampleIds, ['failed']);
});
test('project transitions and draft deletion send reason, revision, correct method and idempotency header', async () => {
  const { api, calls } = setup();
  for (const action of ['pause', 'resume', 'archive', 'delete'])
    await api.state('p/a', action, { baseRevision: 3, reason: '管理' }, action);
  assert.deepEqual(
    calls.map((c) => [c.method, c.path, c.key]),
    [
      ['POST', '/projects/p%2Fa/pause', 'pause'],
      ['POST', '/projects/p%2Fa/resume', 'resume'],
      ['POST', '/projects/p%2Fa/archive', 'archive'],
      ['DELETE', '/projects/p%2Fa', 'delete'],
    ],
  );
  assert.ok(
    calls.every((c) => c.body.baseRevision === 3 && c.body.reason === '管理'),
  );
});
test('server histories and receipts forward opaque cursors and cancellation signals', async () => {
  const { api, calls } = setup(),
    signal = new AbortController().signal;
  for (const kind of ['imports', 'rounds', 'ai-executions', 'package-plans'])
    await api.historyList('p/a', kind, 'x+/=', signal);
  await api.receipts('x+/=', signal);
  for (const call of calls) {
    assert.equal(
      new URL(`https://test.invalid${call.path}`).searchParams.get('cursor'),
      'x+/=',
    );
    assert.equal(call.signal, signal);
  }
  assert.match(calls[4].path, /^\/me\/operation-receipts\?/);
});
test('aggregation sends explicit multi-result IDs; suggestions are read separately', async () => {
  const { api, calls } = setup();
  const body = {
    algorithmId: 'majority-vote-v1',
    specVersionId: 'v',
    items: [{ sampleId: 's', resultIds: ['one', 'two'] }],
  };
  await api.algorithms();
  await api.aggregate('p', body, 'key');
  await api.aggregation('aggregation');
  await api.aggregationItems('aggregation', 'next');
  assert.equal(calls[0].path, '/aggregation-algorithms');
  assert.deepEqual(calls[1].body, body);
  assert.equal(calls[2].path, '/aggregations/aggregation');
  assert.match(calls[3].path, /\/aggregations\/aggregation\/items/);
});
test('formal export consumes frozen snapshots and downloads authorized binary by exportId', async () => {
  const { api, calls } = setup();
  for (const format of ['classification-csv-zip', 'energy-jsonl-zip'])
    await api.export(
      'p',
      { finalSnapshotIds: ['s'], format, includeOriginals: false },
      format,
    );
  await api.exportStatus('e/a');
  await api.downloadExport('e/a');
  assert.deepEqual(
    calls.slice(0, 2).map((c) => c.body.format),
    ['classification-csv-zip', 'energy-jsonl-zip'],
  );
  assert.equal(calls[2].path, '/exports/e%2Fa');
  assert.equal(calls[3].path, '/exports/e%2Fa/download');
  assert.equal(calls[3].method, 'BINARY');
});
test('missing or rejected actual APIs propagate failure without fallback simulation', async () => {
  const failure = Object.assign(new Error('not deployed'), { status: 404 });
  const { api, calls } = setup(() => {
    throw failure;
  });
  await assert.rejects(
    async () =>
      api.atomicReview(
        'p',
        { selectionId: 's', action: 'ACCEPT', reason: '' },
        'k',
      ),
    (e) => e === failure,
  );
  assert.equal(calls.length, 1);
  assert.equal(calls[0].path, '/projects/p/reviews');
});
const input = {
  sampleId: 's',
  inputFormat: 'energy-station-day-input-v1',
  schemaId: 'energy-station-day-input@1.0.0',
  inputHash: 'hash',
  payload: {
    stationKey: 'station',
    dateLocal: '2026-11-17',
    timezone: 'Asia/Shanghai',
    dailyKwh: 0,
    series: {
      name: 'load_curve',
      channel: 'value_kwh',
      unit: 'kWh',
      start: '2026-11-17T00:00:00+08:00',
      periodSeconds: 3600,
      values: Array(24).fill(0),
    },
  },
  context: { status: 'NOT_PROVIDED', availableHistoryDays: 0 },
};
test('energy labels follow the frozen specification, including non-demo labels', () => {
  const value = { ...energy.emptyEnergyValue(), primary: 'custom' };
  const result = energy.encodeEnergyResult(value, input, ['custom']);
  assert.equal(
    energy.decodeEnergyResult(result, input, ['custom']).primary,
    'custom',
  );
  assert.equal(
    energy.validateEnergyResult(result, input, true, ['custom']),
    true,
  );
  assert.throws(() =>
    energy.validateEnergyResult(result, input, true, ['other']),
  );
  assert.throws(
    () =>
      energy.encodeEnergyResult({ ...value, secondary: ['custom'] }, input, [
        'custom',
      ]),
    /副标签/,
  );
});

test('energy save/readback comparison tolerates JSONB key ordering but detects changed answers', () => {
  const result = energy.encodeEnergyResult(
    { ...energy.emptyEnergyValue(), primary: 'custom' },
    input,
    ['custom'],
  );
  const saved = {
    annotations: result.annotations.map((a) => ({
      value: { labelIds: a.value.labelIds },
      type: a.type,
      to_name: a.to_name,
      from_name: a.from_name,
      id: a.id,
    })),
    schemaVersion: '1.0.0',
  };
  assert.equal(energy.sameEnergyResult(result, saved), true);
  assert.equal(
    energy.sameEnergyResult({ ...saved, annotations: [] }, result),
    false,
  );
});
test('structured drafts verify assignment, sample, input hash, format, revision and completed annotations', () => {
  const { verifyStructuredDraft } = loadTypeScriptModule(
    new URL('./labelingStructured.ts', import.meta.url),
    { './labeling': {}, './labelingEnergy.mjs': energy },
  );
  const record = {
    assignmentId: 'a',
    sampleId: 's',
    inputHash: 'hash',
    resultFormat: 'annotation-record-v1',
    revision: 0,
    explicitDone: false,
    result: { schemaVersion: '1.0.0', annotations: [] },
  };
  assert.equal(verifyStructuredDraft(record, 'a', input, ['custom']), record);
  for (const change of [
    { assignmentId: 'other' },
    { sampleId: 'other' },
    { inputHash: 'other' },
    { revision: -1 },
    { resultFormat: 'image-classification-v1' },
    { explicitDone: true },
  ])
    assert.throws(() =>
      verifyStructuredDraft({ ...record, ...change }, 'a', input, ['custom']),
    );
});
test('structured reads use separately authorized owner and assignment data routes', async () => {
  const { api, calls } = setup();
  await api.sampleData('p/a', 's/a');
  await api.assignmentData('a/a', 's/a');
  assert.deepEqual(
    calls.map((c) => c.path),
    [
      '/projects/p%2Fa/samples/s%2Fa/data',
      '/assignments/a%2Fa/samples/s%2Fa/data',
    ],
  );
});
