import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

test('planned task data uses encoded authorized resource paths, not shared CSV or arbitrary dataUrl; no automatic API call', async () => {
  const calls = [],
    exports = {};
  const source = ts.transpileModule(
    readFileSync(new URL('./labelingTaskInputs.ts', import.meta.url), 'utf8'),
    {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    },
  ).outputText;
  vm.runInNewContext(source, {
    exports,
    require: () => ({
      labelingJson: async (...args) => {
        calls.push(args);
        return {};
      },
    }),
  });
  assert.equal(calls.length, 0);
  assert.equal(exports.taskInputBackendReady, false);
  await exports.taskInputs.assignment('assignment/one', 'sample/two');
  await exports.taskInputs.owner('project/one', 'sample/two');
  await exports.taskInputs.templates();
  assert.deepEqual(
    calls.map((c) => c[0]),
    [
      '/assignments/assignment%2Fone/samples/sample%2Ftwo/data',
      '/projects/project%2Fone/samples/sample%2Ftwo/data',
      '/task-templates',
    ],
  );
});
