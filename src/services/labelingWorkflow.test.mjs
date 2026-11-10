import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
const source = ts.transpileModule(
  readFileSync(new URL('./labelingWorkflow.ts', import.meta.url), 'utf8'),
  {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  },
).outputText;
function module() {
  const exports = {};
  vm.runInNewContext(source, {
    exports,
    URLSearchParams,
    require: () => ({ operationKey: () => 'operation-key' }),
  });
  return exports;
}
test('future workflow client encodes scoped paths, freezes explicit IDs and never manufactures an assignment for AI', async () => {
  const calls = [];
  const api = module().workflowClient(async (...args) => {
    calls.push(args);
    return {};
  }, 'project/encoded');
  await api.execute(
    {
      selectionId: 'fixed',
      specVersionId: 'spec',
      agentId: 'agent',
      referenceMode: 'NONE',
    },
    'same-key',
  );
  assert.equal(calls[0][1], '/projects/project%2Fencoded/ai-executions');
  assert.equal(calls[0][3], 'same-key');
  assert.equal(Object.hasOwn(calls[0][2], 'assignmentId'), false);
  assert.equal(Object.hasOwn(calls[0][2], 'packageId'), false);
  await api.export(
    {
      finalSnapshotIds: ['frozen-final'],
      format: 'classification-csv-zip',
      includeOriginals: false,
    },
    'export-key',
  );
  assert.deepEqual(calls[1][2].finalSnapshotIds, ['frozen-final']);
  assert.equal(calls[1][2].submissionIds, undefined);
  await api.results({
    cursor: 'cursor/+=',
    sourceKind: 'AI',
    reviewStatus: 'PENDING',
  });
  assert.match(calls[2][1], /cursor=cursor%2F%2B%3D/);
  await api.revise(
    'result/encoded',
    {
      baseReviewRevision: 4,
      result: { labelId: 'stable-id' },
      reason: 'owner correction',
    },
    'revision-key',
  );
  assert.equal(
    calls[3][1],
    '/projects/project%2Fencoded/results/result%2Fencoded/revisions',
  );
});
test('new service errors propagate once without implicit fallback or simulated acceptance', async () => {
  let calls = 0;
  const failure = new Error('503 unavailable');
  const api = module().workflowClient(async () => {
    calls++;
    throw failure;
  }, 'project');
  await assert.rejects(
    api.review({ action: 'ACCEPT', selectionId: 'fixed' }, 'key'),
    (e) => e === failure,
  );
  assert.equal(calls, 1);
  assert.equal(module().workflowBackendReady, false);
});
