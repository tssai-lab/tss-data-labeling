import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { webcrypto } from 'node:crypto';
import vm from 'node:vm';
import test from 'node:test';
import ts from 'typescript';
import { loadTypeScriptModule } from '../../../../scripts/qa/load-typescript-module.mjs';

const { createTrainingSubmission } = loadTypeScriptModule(new URL('./trainingSubmission.ts', import.meta.url), {}, { crypto: webcrypto });
const source = readFileSync(new URL('./useTrainingCreate.tsx', import.meta.url), 'utf8');
const ast = ts.createSourceFile('hook.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let callback;
function visit(node) {
  if (ts.isVariableDeclaration(node) && node.name.getText(ast) === 'handleSubmit') callback = node.initializer.getText(ast);
  ts.forEachChild(node, visit);
}
visit(ast);
assert.ok(callback);

function flow({ validate = async () => {}, create = async () => ({ success: true, data: { id: 'task', versionNo: 2 } }), continuation = false } = {}) {
  const state = { busy: [], errors: [], success: [], routes: [], requests: [] };
  const submit = async (...args) => { state.requests.push(args); return create(...args); };
  const context = {
    exports: {}, submission: { current: createTrainingSubmission() }, setSubmitting: value => state.busy.push(value),
    validateResourceSection: validate, setCurrentStep() {}, codeCheck: { passed: true }, isCodeApproved: () => true,
    selectedCodeApprovalStatus: 'APPROVED', selectedBaseModelVersionId: 'm', selectedDatasetVersionId: 'd', selectedCodeVersionId: 'c',
    form: { getFieldsValue: () => ({ hyperParams: '{"epochs":2}', hardwareTargetId: 'cpu', resourceProfileId: 'small' }) },
    hardwareOptions: [{ hardwareTargetId: 'cpu' }], resourceProfiles: [{ id: 'small' }], buildTrainingResourceRequest: () => ({ cpuCores: 1 }),
    selectedTrainingPlanId: 'plan', selectedTrainingPlan: { version: '1' }, isExperimentContinue: continuation, experimentId: 'exp',
    createTask: submit, createExperimentVersion: submit,
    message: { success: value => state.success.push(value), error: value => state.errors.push(value) },
    Modal: { error() {}, warning() {} }, history: { push: value => state.routes.push(value) },
  };
  vm.runInNewContext(ts.transpileModule('exports.submit=' + callback, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText, context);
  return { submit: context.exports.submit, state };
}

test('真实提交回调在资源校验等待期间挡住第二次点击', async () => {
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const { submit, state } = flow({ validate: () => pending });
  const first = submit();
  await submit();
  assert.equal(state.requests.length, 0);
  release();
  await first;
  assert.equal(state.requests.length, 1);
  assert.deepEqual(state.busy, [true, false]);
});

test('真实创建和续训回调在响应丢失后重试均复用编号', async () => {
  for (const continuation of [false, true]) {
    let count = 0;
    const { submit, state } = flow({ continuation, create: async () => {
      if (++count === 1) throw new Error('response lost');
      return { success: true, data: { id: 'same-task', versionNo: 2 } };
    } });
    await submit();
    await submit();
    const offset = continuation ? 1 : 0;
    assert.equal(state.requests[0][offset].submissionKey, state.requests[1][offset].submissionKey);
    assert.deepEqual(state.routes, ['/task/detail/same-task']);
    assert.equal(state.errors.length, 1);
    assert.equal(state.success.length, 1);
  }
});

test('缺少创建回执不报成功，校验失败会释放提交锁', async () => {
  const missing = flow({ create: async () => ({ success: true }) });
  await missing.submit();
  assert.equal(missing.state.success.length, 0);
  assert.equal(missing.state.routes.length, 0);
  let attempts = 0;
  const invalid = flow({ validate: async () => { if (++attempts === 1) throw new Error('invalid'); } });
  await invalid.submit();
  await invalid.submit();
  assert.equal(invalid.state.requests.length, 1);
});
