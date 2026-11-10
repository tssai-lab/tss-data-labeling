import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

function moduleSource(name) {
  return ts.transpileModule(
    readFileSync(new URL(name, import.meta.url), 'utf8'),
    {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    },
  ).outputText;
}
function client(fetch) {
  const base = {};
  const common = { Headers, AbortSignal, URLSearchParams, crypto, fetch };
  vm.runInNewContext(moduleSource('./labeling.ts'), {
    ...common,
    exports: base,
    require: (name) =>
      name.endsWith('/storage')
        ? {
            STORAGE_KEYS: { TOKEN: 'token' },
            storage: { get: () => 'test-token', remove: () => {} },
          }
        : { redirectToLogin: () => {} },
  });
  const api = {};
  vm.runInNewContext(moduleSource('./labelingTasks.ts'), {
    ...common,
    exports: api,
    require: () => base,
  });
  return { ...api, ...base };
}
const page = (id, cursor, items, spec = 'frozen-spec') => ({
  assignmentId: id,
  packageId: 'package',
  status: 'IN_PROGRESS',
  readOnly: false,
  specification: {
    specVersionId: spec,
    labels: [{ id: 'stable', name: '名称' }],
    instructions: '',
  },
  items,
  nextCursor: cursor,
});

test('all package pages load through opaque encoded cursors before submission', async () => {
  const paths = [];
  const api = client(async (path) => {
    paths.push(path);
    return Response.json({
      data:
        paths.length === 1
          ? page('work', 'cursor/+=', [{ sampleId: 'a' }])
          : page('work', null, [{ sampleId: 'b' }]),
    });
  });
  const work = await api.allWorkItems('work');
  assert.equal(work.items.length, 2);
  assert.match(paths[1], /cursor=cursor%2F%2B%3D/);
});

test('duplicate members, cursor loops and changed frozen specifications fail closed', async () => {
  for (const second of [
    page('work', null, [{ sampleId: 'a' }]),
    page('work', 'next', [{ sampleId: 'b' }]),
    page('work', null, [{ sampleId: 'b' }], 'changed'),
    {
      ...page('work', null, [{ sampleId: 'b' }]),
      templateId: 'energy-station-day',
      templateVersion: '1.0.0',
    },
    {
      ...page('work', null, [{ sampleId: 'b' }]),
      specification: {
        ...page('work', null, []).specification,
        resultSchemaId: 'changed-under-same-spec-id',
      },
    },
  ]) {
    let calls = 0;
    const api = client(async () =>
      Response.json({
        data:
          ++calls === 1 ? page('work', 'next', [{ sampleId: 'a' }]) : second,
      }),
    );
    await assert.rejects(api.allWorkItems('work'));
  }
});

test('draft writes stable label ID, exact baseRevision and no idempotency key', async () => {
  const calls = [];
  const api = client(async (path, init) => {
    assert.equal(path, '/api/labeling/v1/assignments/work/drafts/sample');
    assert.equal(init.method, 'PUT');
    assert.equal(init.headers.get('Authorization'), 'Bearer test-token');
    assert.equal(init.headers.has('Idempotency-Key'), false);
    calls.push(JSON.parse(init.body));
    return Response.json({ data: { revision: 4 } });
  });
  await api.tasks.save('work', 'sample', 3, 'stable');
  await api.tasks.save('work', 'sample', 4, '');
  assert.deepEqual(calls, [
    {
      baseRevision: 3,
      explicitDone: true,
      resultFormat: 'image-classification-v1',
      result: { labelId: 'stable' },
    },
    {
      baseRevision: 4,
      explicitDone: false,
      resultFormat: 'image-classification-v1',
      result: {},
    },
  ]);
});

test('revision conflict is surfaced without automatic retry', async () => {
  let calls = 0;
  const api = client(async () => {
    calls++;
    return Response.json(
      { error: { code: 'REVISION_CONFLICT', message: '请重新读取' } },
      { status: 409 },
    );
  });
  await assert.rejects(
    api.tasks.save('work', 'sample', 1, 'stable'),
    (e) => e.code === 'REVISION_CONFLICT',
  );
  assert.equal(calls, 1);
});

test('submission replay uses original key and unchanged full revision manifest', async () => {
  const requests = [];
  const api = client(async (path, init) => {
    requests.push({
      path,
      key: init.headers.get('Idempotency-Key'),
      body: JSON.parse(init.body),
    });
    if (requests.length === 1) throw new Error('response lost');
    return Response.json({
      data: { submissionId: 'receipt', status: 'SUBMITTED' },
    });
  });
  const items = [
    { sampleId: 'a', revision: 2 },
    { sampleId: 'b', revision: 7 },
  ];
  await assert.rejects(api.tasks.submit('work', items, 'same-key'));
  assert.equal(
    (await api.tasks.submit('work', items, 'same-key')).submissionId,
    'receipt',
  );
  assert.deepEqual(requests[0], requests[1]);
});

test('claimed sample preview always uses assignment authorization path', async () => {
  const api = client(async (path, init) => {
    assert.equal(path, '/api/labeling/v1/assignments/work/samples/sample/file');
    assert.equal(init.headers.get('Authorization'), 'Bearer test-token');
    return new Response('test-bytes', {
      headers: { 'Content-Type': 'image/png' },
    });
  });
  assert.equal(
    (
      await api.assignmentSampleBlob(
        'work',
        'sample',
        new AbortController().signal,
      )
    ).type,
    'image/png',
  );
});
