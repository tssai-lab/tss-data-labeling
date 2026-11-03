import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

const load = request => loadTypeScriptModule(new URL('./task.ts', import.meta.url), {
  '@umijs/max': { request },
});

test('训练创建保留版本编号、资源单位和超参数原值，不改调用方对象', async () => {
  const body = { datasetVersionId: 'dataset-v1', baseModelVersionId: 'model-v2',
    codeVersionId: 'code-v3', planId: 'plan', planVersion: 'v4', trainingProfile: 'plan',
    resourceRequest: { hardwareTargetId: 'cpu', cpuCores: 2, memoryMiB: 4096 },
    hyperParams: { learning_rate: 0, enabled: false } };
  const before = structuredClone(body);
  const result = { success: true, data: { id: 'task-1', versionNo: 1 } };
  const api = load(async (url, options) => {
    assert.equal(url, '/task/create');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['Content-Type'], 'application/json');
    assert.equal(options.data, body);
    assert.equal(options.skipErrorHandler, true);
    return result;
  });
  assert.equal(await api.createTask(body, { skipErrorHandler: true }), result);
  assert.deepEqual(body, before);
});

test('续训只提交调用方提供的字段，继承规则交给后端', async () => {
  const body = { hyperParams: '{"epochs":2}' };
  const api = load(async (url, options) => {
    assert.equal(url, '/experiments/exp%2F1/versions');
    assert.equal(options.method, 'POST');
    assert.deepEqual(options.data, body);
    assert.equal(Object.hasOwn(options.data, 'datasetVersionId'), false);
    return { success: true, data: { versionNo: 2 } };
  });
  assert.equal((await api.createExperimentVersion('exp/1', body)).data.versionNo, 2);
});

test('训练写请求失败只上抛一次；业务失败回执原样交给页面', async () => {
  let calls = 0;
  const failure = new Error('connection lost');
  const api = load(async () => { calls++; throw failure; });
  await assert.rejects(api.createTask({ datasetVersionId: 'v1' }), error => error === failure);
  assert.equal(calls, 1);
  const receipt = { success: false, errorMessage: '方案已停用' };
  assert.equal(await load(async () => receipt).createTask({ datasetVersionId: 'v1' }), receipt);
});

test('列表筛选进入查询参数，页码和总数采用后端结果', async () => {
  const response = { success: true, data: { data: [{ id: 'task-201' }], total: 240 } };
  const api = load(async (url, options) => {
    assert.equal(url, '/task/list');
    assert.equal(options.params.current, 11);
    assert.equal(options.params.pageSize, 20);
    assert.equal(options.params.name, '训练');
    assert.equal(options.params.experimentId, 'exp-');
    assert.equal(options.params.status, 'running');
    assert.equal(options.skipErrorHandler, true);
    return response;
  });
  assert.equal(await api.fetchTaskList({ current: 11, pageSize: 20, name: '训练', experimentId: 'exp-', status: 'running', skipErrorHandler: true }), response);
});

test('创建与续训都透传办理编号，不自动更换或重试', async () => {
  const calls = [];
  const api = load(async (url, options) => { calls.push(options.data.submissionKey); return { success: true }; });
  await api.createTask({ datasetVersionId: 'd', submissionKey: 'stable-request-key' });
  await api.createExperimentVersion('e', { submissionKey: 'stable-request-key' });
  assert.deepEqual(calls, ['stable-request-key', 'stable-request-key']);
});
