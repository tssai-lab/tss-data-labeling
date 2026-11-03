import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as compatibility from '../utils/apiCompatibility.mjs';
import * as receipt from '../utils/codeUploadReceipt.mjs';
import * as pagination from './paginatedCandidates.mjs';
import * as metricHistory from './mlflowMetricHistory.mjs';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

// 直接执行产品服务，替换网络和浏览器存储；不复制被测读取逻辑。
function load(file, dependencies) {
  return loadTypeScriptModule(new URL(file, import.meta.url), dependencies);
}

function metrics(request) {
  return load('./mlflow.ts', {
    '@umijs/max': { request },
    '@/constants/platform': { API_CONFIG: { ENDPOINTS: { MLFLOW_METRICS_HISTORY: '/metrics' } } },
    '@/utils/trainingMetrics': { TRAINING_MLFLOW_METRIC_KEYS: ['train_loss', 'val_accuracy'] },
    './mlflowMetricHistory.mjs': metricHistory,
  });
}

test('指标：成功空记录才查别名，保留零值、去重和排序', async () => {
  const calls = [];
  const service = metrics(async url => {
    const key = new URL(url, 'http://local').searchParams.get('metric_key');
    calls.push(key);
    return { metrics: key === 'loss' ? [
      { step: 2, value: 2 }, { step: 0, value: 0 }, { step: 2, value: 1 },
    ] : [] };
  });
  const result = await service.fetchMlflowMetricsBulk('run /1', ['train_loss']);
  assert.deepEqual(calls, ['train_loss', 'loss']);
  assert.equal(JSON.stringify(result.train_loss), '[{"step":0,"value":0},{"step":2,"value":1}]');
});

test('指标：所有别名正常为空、可选 metrics 缺省仍可返回空结果', async () => {
  for (const payload of [{ metrics: [] }, {}]) {
    const result = await metrics(async () => payload).fetchMlflowMetricsBulk('run', ['train_loss', 'custom']);
    assert.equal(result.custom.length, 0);
    assert.equal(result.train_loss.length, 0);
  }
});

test('指标：网络/鉴权/服务器/取消错误不吞掉，也不继续请求别名', async () => {
  for (const key of ['train_loss', 'custom']) {
    for (const failure of [new Error('timeout'), new Error('cancelled'), ...[401, 403, 404, 429, 500, 503].map(status => ({ response: { status } }))]) {
      let calls = 0;
      const service = metrics(async () => { calls++; throw failure; });
      await assert.rejects(service.fetchMlflowMetricsBulk('run', [key]), error => error === failure);
      assert.equal(calls, 1);
    }
  }
});

test('指标：错误响应结构不能伪装成空数据', async () => {
  for (const payload of [null, 'gateway error', [], { metrics: {} }, { metrics: null }, { error_code: 'INTERNAL_ERROR' }, { success: false }, { errorCode: 'DENIED', metrics: [] }]) {
    for (const key of ['train_loss', 'custom']) {
      await assert.rejects(metrics(async () => payload).fetchMlflowMetricsBulk('run', [key]), /指标.*响应/);
    }
  }
});

test('指标：多指标只有一项失败也必须报告失败，不返回假的完整结果', async () => {
  const failure = new Error('offline');
  await assert.rejects(metrics(async url => {
    if (url.includes('metric_key=custom')) throw failure;
    return { metrics: [{ step: 1, value: 0.5 }] };
  }).fetchMlflowMetricsBulk('run', ['train_loss', 'custom']), error => error === failure);
});

const version = id => ({ codeVersionId: id, codeAssetId: 'asset', codeName: '训练代码', trainingProfile: 'cv', validationStatus: 'VALID', riskLevel: 'LOW' });
function inventory({ assets = [], versions = async () => [], request = async () => assert.fail('不应调用 legacy'), pending = [] } = {}) {
  return load('./code.ts', {
    '@umijs/max': { request }, '@/utils/authFileDownload': {}, '@/constants/trainingCode': {},
    '@/utils/pendingCodeVersions': { listPendingCodeVersions: () => pending },
    '@/utils/codeUploadReceipt.mjs': receipt, '@/utils/apiCompatibility.mjs': compatibility,
    './paginatedCandidates.mjs': pagination,
    './codeV2': {
      listV2CodeAssets: typeof assets === 'function' ? assets : async () => assets,
      listV2CodeAssetVersions: versions,
      mapV2CodeVersionToLegacy: value => value,
      isInternalGeneratedCodeAssetName: () => false,
    },
  });
}

test('本人代码：成功空列表与本地上传登记保持兼容', async () => {
  const empty = await inventory().fetchOwnerCodeVersionInventory();
  assert.equal(empty.success, true); assert.equal(empty.total, 0);
  const local = await inventory({ pending: [{ codeVersionId: 'local', fileName: 'code.zip', source: 'upload' }] }).fetchOwnerCodeVersionInventory();
  assert.equal(local.data[0].codeVersionId, 'local');
});

test('本人代码：部分资产失败保留成功记录并显式声明不完整', async () => {
  const result = await inventory({
    assets: [{ id: 'good', name: 'good' }, { id: 'bad' }],
    versions: async id => { if (id === 'bad') throw new Error('offline'); return [version('v1')]; },
  }).fetchOwnerCodeVersionInventory();
  assert.equal(result.success, true); assert.equal(result.data.length, 1);
  assert.equal(result.incomplete, true); assert.match(result.warningMessage, /1.*2|2.*1/);
});

test('本人代码：全部版本读取失败不以本地登记伪装为成功，也不降级 legacy', async () => {
  for (const status of [401, 403, 404, 500]) {
    await assert.rejects(inventory({
      assets: [{ id: 'a' }], versions: async () => { throw { response: { status } }; },
      pending: [{ codeVersionId: 'local', fileName: 'local.zip' }],
    }).fetchOwnerCodeVersionInventory(), /训练代码.*加载失败/);
  }
});

test('本人代码：V2 入口鉴权/超时/业务 404 不误用旧列表', async () => {
  for (const error of [new Error('timeout'), ...[401, 403, 500].map(status => ({ response: { status } })), { response: { status: 404, data: { errorCode: 'DENIED' } } }]) {
    await assert.rejects(inventory({ assets: async () => { throw error; } }).fetchOwnerCodeVersionInventory(), actual => actual === error);
  }
});

test('本人代码：只有 V2 入口不支持时兼容旧端点', async () => {
  for (const status of [404, 405, 501]) {
    let calls = 0;
    const result = await inventory({
      assets: async () => { throw { response: { status } }; },
      request: async url => { assert.equal(url, '/code/version/list'); calls++; return { success: true, data: [version('old')] }; },
    }).fetchOwnerCodeVersionInventory();
    assert.equal(result.data[0].codeVersionId, 'old'); assert.equal(calls, 1);
  }
});

test('本人代码：错误结构和缺少标识的记录不能静默消失', async () => {
  for (const assets of [null, {}, { errorCode: 'FAILED', data: [] }]) {
    await assert.rejects(inventory({ assets }).fetchOwnerCodeVersionInventory(), /响应/);
  }
  for (const options of [
    { assets: [null] }, { assets: [{}] },
    { assets: [{ id: 'a' }], versions: async () => ({}) },
    { assets: [{ id: 'a' }], versions: async () => ({ code: 403, items: [] }) },
    { assets: [{ id: 'a' }], versions: async () => [{}] },
  ]) await assert.rejects(inventory(options).fetchOwnerCodeVersionInventory(), /训练代码.*加载失败/);
});

test('本人代码：既有列表包装形式继续可读', async () => {
  for (const wrap of [data => data, data => ({ data }), items => ({ items }), items => ({ data: { items } })]) {
    const result = await inventory({ assets: wrap([{ id: 'a' }]), versions: async () => ({ items: [version('v')] }) }).fetchOwnerCodeVersionInventory();
    assert.equal(result.data[0].codeVersionId, 'v');
  }
});

test('本人代码：降级后旧接口报错或缺少列表也不能伪装成功', async () => {
  for (const payload of [{ success: false, errorMessage: '旧接口不可用', data: [] }, { success: true }, null]) {
    await assert.rejects(inventory({
      assets: async () => { throw { response: { status: 404 } }; },
      request: async () => payload,
    }).fetchOwnerCodeVersionInventory(), /旧接口不可用|响应/);
  }
});

function compareLoader({ ids = ['a', 'b'], detail, metrics } = {}) {
  // 仅提取页面中真实回调表达式，隔离大页面其它交互；不重写指标汇总算法。
  const path = new URL('../pages/task/compare/useCompareData.ts', import.meta.url);
  const sourceFile = ts.createSourceFile(path.pathname, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let initializer;
  const visit = node => {
    if (ts.isVariableDeclaration(node) && node.name.getText(sourceFile) === 'loadCompareData') initializer = node.initializer;
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  assert.ok(initializer, '必须找到页面的真实加载回调');
  const compiled = ts.transpileModule(`exports.load = ${initializer.getText(sourceFile)}`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const calls = [], detailCalls = [], messages = [], snapshots = [], loading = [];
  const exports = {};
  vm.runInNewContext(compiled, {
    exports, useCallback: fn => fn, selectedRowKeys: ids, taskList: ids.map(id => ({ id, name: id })),
    MLFLOW_METRIC_KEYS: ['train_loss'], compareLoadSequence: { current: 0 },
    fetchTaskDetail: async id => { detailCalls.push(id); return detail ? detail(id) : { data: { id, name: id, runId: id } }; },
    fetchMlflowMetricsBulk: async run => { calls.push(run); return metrics ? metrics(run) : { train_loss: [{ step: 0, value: 1 }] }; },
    setMetricsLoading: value => loading.push(value), setMetricsData: data => snapshots.push(data),
    message: Object.fromEntries(['info', 'error', 'warning'].map(level => [level, text => messages.push({ level, text })])),
  });
  return { load: exports.load, calls, detailCalls, messages, snapshots, loading };
}

test('结果对比：真实加载回调区分请求失败、指标为空与成功对比', async () => {
  for (const scenario of ['failure', 'empty', 'success', 'partial']) {
    const ids = scenario === 'partial' ? ['a', 'b', 'c'] : ['a', 'b'];
    const { load, calls, messages, snapshots } = compareLoader({ ids,
      metrics: async run => {
        if ((scenario === 'failure' && run === 'b') || (scenario === 'partial' && run === 'c')) throw new Error('offline');
        return { train_loss: scenario === 'empty' ? [] : [{ step: 0, value: 1 }] };
      },
    });
    await load();
    assert.deepEqual(calls, ids);
    if (scenario === 'failure') {
      assert.ok(messages.some(item => item.level === 'error' && /加载失败/.test(item.text)));
      assert.equal(snapshots.at(-1).length, 0);
    } else if (scenario === 'empty') {
      assert.ok(messages.some(item => /没有共同核心指标/.test(item.text)));
    } else {
      assert.equal(snapshots.at(-1).length, 2);
      assert.equal(messages.filter(item => item.level === 'error').length, 0);
      if (scenario === 'partial') assert.ok(messages.some(item => /1 个指标拉取失败/.test(item.text)));
    }
  }
});

test('结果对比：较早加载的迟到指标不能覆盖最后一次加载', async () => {
  let resolve;
  const held = new Promise(yes => { resolve = yes; });
  let first = true;
  const state = compareLoader({ metrics: async () => {
    if (first) { first = false; return held; }
    return { train_loss: [{ step: 0, value: 2 }] };
  } });
  const old = state.load();
  await new Promise(yes => setImmediate(yes));
  await state.load();
  resolve({ train_loss: [{ step: 0, value: 1 }] });
  await old;
  assert.equal(state.snapshots.length, 1);
  assert.equal(state.snapshots[0][0].metrics.train_loss[0].value, 2);
});

test('对比详情：网络、权限和服务错误不误报没有指标', async () => {
  for (const failure of [new Error('timeout'), ...[401, 403, 404, 429, 500].map(status => ({ response: { status } }))]) {
    const state = compareLoader({ detail: async () => { throw failure; } });
    await state.load();
    assert.ok(state.messages.some(m => m.level === 'error' && /2.*任务详情.*加载失败/.test(m.text)));
    assert.ok(state.messages.every(m => !/没有可用|没有共同|尚无运行记录/.test(m.text)));
    assert.equal(state.calls.length, 0, '不绕过失败详情去读列表缓存中的 Run');
    assert.equal(state.snapshots.at(-1).length, 0);
    assert.equal(state.loading.at(-1), false);
  }
});

test('对比详情：一项失败加一项成功时指出详情失败而非指标不足', async () => {
  const state = compareLoader({ detail: async id => {
    if (id === 'b') throw new Error('offline');
    return { data: { id, runId: id } };
  } });
  await state.load();
  assert.ok(state.messages.some(m => m.level === 'error' && /1.*任务详情.*加载失败/.test(m.text)));
  assert.deepEqual(state.calls, ['a']);
  assert.equal(state.snapshots.at(-1).length, 0);
});

test('对比详情：两项成功一项详情失败仍可对比并明确缺项', async () => {
  const state = compareLoader({ ids: ['a', 'b', 'c'], detail: async id => {
    if (id === 'c') throw new Error('offline');
    return { data: { id, runId: id } };
  } });
  await state.load();
  assert.equal(state.snapshots.at(-1).length, 2);
  assert.ok(state.messages.some(m => m.level === 'warning' && /1.*任务详情.*加载失败/.test(m.text)));
  assert.ok(state.messages.every(m => !/无详情或 Run ID/.test(m.text)));
});

test('对比详情：有效详情没有运行记录时才提示暂无 Run ID', async () => {
  for (const runId of [undefined, null, '', '   ']) {
    const state = compareLoader({ detail: async id => ({ success: true, data: { id, runId } }) });
    await state.load();
    assert.equal(state.calls.length, 0);
    assert.ok(state.messages.some(m => /运行记录|Run ID/.test(m.text)));
    assert.equal(state.messages.filter(m => m.level === 'error').length, 0);
  }
});

test('对比详情：一项读取失败加一项无 Run 时优先说明读取失败', async () => {
  const state = compareLoader({ detail: async id => {
    if (id === 'a') throw new Error('offline');
    return { success: true, data: { id, runId: null } };
  } });
  await state.load();
  assert.ok(state.messages.some(m => m.level === 'error' && /任务详情.*加载失败/.test(m.text)));
  assert.equal(state.calls.length, 0);
});

test('对比详情：空、畸形和业务失败响应不是无指标的证据', async () => {
  for (const response of [null, {}, { data: null }, { data: [] }, { data: {} }, { data: 'bad' },
    { data: { unexpected: true } }, { success: false, data: { id: 'a', runId: 'a' } },
    { code: 403, data: { id: 'a', runId: 'a' } }, { data: { id: 'a', runId: 123 } },
    { data: { id: 'a', run_id: {} } }, { data: { id: 'a', runId: 0 } },
    { data: { id: 'a', runId: false } }, { data: { id: {}, runId: 'a' } },
    { data: { id: '   ' } },
  ]) {
    const state = compareLoader({ detail: async () => response });
    await state.load();
    assert.ok(state.messages.some(m => m.level === 'error' && /任务详情.*加载失败/.test(m.text)), JSON.stringify(response));
    assert.equal(state.calls.length, 0);
  }
});

test('对比详情：保留旧 run_id 与实验编号兼容，不修改原始响应', async () => {
  const data = new Map(['a', 'b'].map(id => [id, Object.freeze({ id: `version-${id}`, run_id: ` run-${id} ` })]));
  const state = compareLoader({ detail: async id => ({ code: 200, data: data.get(id) }) });
  await state.load();
  assert.deepEqual(state.calls, ['run-a', 'run-b']);
  assert.equal(state.snapshots.at(-1).length, 2);
  assert.equal(data.get('a').name, undefined);
  const legacy = compareLoader({ detail: async id => ({ data: { run_id: id } }) });
  await legacy.load();
  assert.deepEqual(legacy.calls, ['a', 'b'], '保留可读旧 Run 的缺省 ID 兼容');
});

test('对比详情：重复编号不能凑成两个不同任务', async () => {
  const duplicate = compareLoader({ ids: ['a', 'a'] });
  await duplicate.load();
  assert.equal(duplicate.detailCalls.length, 0);
  assert.equal(duplicate.calls.length, 0);
  const alias = compareLoader({ ids: ['a', 'exp-a'], detail: async () => ({ data: { id: 'a', runId: 'run-a' } }) });
  await alias.load();
  assert.equal(alias.snapshots.at(-1).length, 0, '同一版本的实验编号别名仍是同一任务');
});

test('对比详情：重试恢复后不继承上轮失败计数；成功空指标保持原判断', async () => {
  let fail = true;
  const state = compareLoader({ detail: async id => {
    if (fail) throw new Error('offline');
    return { data: { id, runId: id } };
  } });
  await state.load();
  state.messages.length = 0;
  fail = false;
  await state.load();
  assert.equal(state.snapshots.at(-1).length, 2);
  assert.equal(state.messages.length, 0);
  const empty = compareLoader({ metrics: async () => ({ train_loss: [] }) });
  await empty.load();
  assert.ok(empty.messages.some(m => /没有共同核心指标/.test(m.text)));
});

test('对比详情：多类缺项同时存在时分别计数，不把无 Run 算成详情失败', async () => {
  const state = compareLoader({
    ids: ['a', 'b', 'no-run', 'detail-error', 'metric-error'],
    detail: async id => {
      if (id === 'detail-error') throw new Error('detail unavailable');
      return { data: { id, runId: id === 'no-run' ? undefined : id } };
    },
    metrics: async id => {
      if (id === 'metric-error') throw new Error('metrics unavailable');
      return { train_loss: [{ step: 0, value: 1 }] };
    },
  });
  await state.load();
  assert.equal(state.snapshots.at(-1).length, 2);
  assert.deepEqual(state.calls, ['a', 'b', 'metric-error']);
  assert.ok(state.messages.some(m => m.level === 'info' && /1 个任务尚无运行记录/.test(m.text)));
  assert.ok(state.messages.some(m => m.level === 'warning' && /1 个任务详情加载失败.*1 个指标拉取失败/.test(m.text)));
});

// 执行页面原有请求回调与排序函数，避免测试里复制一份已修正的筛选/计数逻辑。
function ownerListRequest(fetchInventory) {
  const path = new URL('../pages/task/trainingCode/list/index.tsx', import.meta.url);
  const source = readFileSync(path, 'utf8');
  const sourceFile = ts.createSourceFile(path.pathname, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const helpers = [], notices = [], requests = [];
  let initializer;
  const visit = node => {
    if (ts.isFunctionDeclaration(node) && ['getCodeUploadedAt', 'compareUploadedAtDesc'].includes(node.name?.text)) {
      helpers.push(node.getText(sourceFile));
    }
    if (ts.isVariableDeclaration(node) && node.name.getText(sourceFile) === 'requestList') initializer = node.initializer;
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  assert.ok(initializer, '必须找到页面的真实列表请求回调');
  assert.equal(helpers.length, 2, '必须执行页面的真实排序函数');
  const compiled = ts.transpileModule(`${helpers.join('\n')}\nexports.request = ${initializer.getText(sourceFile)}`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {};
  vm.runInNewContext(compiled, {
    exports, listRequestSequence: { current: 0 }, setListNotice: notice => notices.push(notice),
    fetchOwnerCodeVersionInventory: async options => { requests.push(options); return fetchInventory(); },
    getCodeUserDisplayName: inventory().getCodeUserDisplayName,
    getApiErrorMessage: (error, fallback) => error.message || fallback,
  });
  return { request: exports.request, notices, requests };
}

const ownerRows = (count, matches) => Array.from({ length: count }, (_, index) => ({
  codeVersionId: `version-${String(index).padStart(2, '0')}`,
  codeName: index < matches ? `MiniRBT ${index}` : `YOLO ${index}`,
  fileName: `train-${index}.zip`, createdAt: `2026-09-${String(index + 1).padStart(2, '0')}`,
}));

test('本人列表分页：名称筛选 7 条时总数是 7，不是服务返回的 16', async () => {
  const rows = ownerRows(16, 7);
  const before = JSON.stringify(rows);
  const list = ownerListRequest(async () => ({ success: true, data: rows, total: 16 }));
  const result = await list.request({ codeAssetName: 'MiniRBT', current: 1, pageSize: 10 });
  assert.equal(result.data.length, 7);
  assert.equal(result.total, 7);
  assert.equal(result.data[0].codeVersionId, 'version-06', '保持最新上传在前');
  assert.equal(JSON.stringify(rows), before, '不原地修改服务数组');
  assert.equal(list.requests.length, 1, '不新增计数接口或重复请求');
});

test('本人列表分页：文件名匹配忽略大小写和查询首尾空格', async () => {
  const list = ownerListRequest(async () => ({ success: true, data: ownerRows(16, 7), total: 16 }));
  const result = await list.request({ codeAssetName: '  TRAIN-15.ZIP  ' });
  assert.equal(result.data[0].codeVersionId, 'version-15');
  assert.equal(result.total, 1);
});

test('本人列表分页：零匹配和空显示字段均返回真实零条数', async () => {
  const list = ownerListRequest(async () => ({ success: true, data: [
    ...ownerRows(16, 7), { codeVersionId: 'missing', codeName: null, fileName: null },
  ], total: 17 }));
  const result = await list.request({ codeAssetName: '不存在的名称' });
  assert.equal(result.success, true);
  assert.equal(result.data.length, 0);
  assert.equal(result.total, 0);
  assert.equal(list.notices.at(-1), undefined, '零匹配不是读取失败');
});

test('本人列表分页：空查询和重置恢复完整总数', async () => {
  const list = ownerListRequest(async () => ({ success: true, data: ownerRows(16, 7), total: 16 }));
  await list.request({ codeAssetName: 'MiniRBT' });
  for (const keyword of [undefined, '', '   ']) {
    const result = await list.request({ codeAssetName: keyword });
    assert.equal(result.data.length, 16);
    assert.equal(result.total, 16);
  }
});

test('本人列表分页：第二页仍返回完整匹配数组，避免重复分页截断', async () => {
  const list = ownerListRequest(async () => ({ success: true, data: ownerRows(23, 12), total: 23 }));
  const result = await list.request({ codeAssetName: 'MiniRBT', current: 2, pageSize: 10 });
  assert.equal(result.data.length, 12, '切片交给表格；回调不再截成第二页 2 条');
  assert.equal(result.total, 12);
});

test('本人列表分页：部分失败的匹配条数不冒充完整资产数且保留告警', async () => {
  const list = ownerListRequest(async () => ({
    success: true, data: ownerRows(9, 4), total: 9, incomplete: true, warningMessage: '部分版本列表加载失败',
  }));
  const result = await list.request({ codeAssetName: 'MiniRBT' });
  assert.equal(result.total, 4);
  assert.equal(list.notices.at(-1).type, 'warning');
  assert.match(list.notices.at(-1).text, /部分版本列表加载失败/);
});

test('本人列表分页：空资产集仍是正常零条，不提示故障', async () => {
  const list = ownerListRequest(async () => ({ success: true, data: [], total: 0 }));
  const result = await list.request({});
  assert.equal(result.success, true);
  assert.equal(result.total, 0);
  assert.equal(list.notices.at(-1), undefined);
});

test('本人列表分页：请求失败仍标记 success=false，重试成功清除错误', async () => {
  let state = 'success';
  const list = ownerListRequest(async () => {
    if (state === 'exception') throw new Error('网络不可用');
    if (state === 'business') return { success: false, errorMessage: '读取被拒绝', data: [] };
    return { success: true, data: ownerRows(16, 7), total: 16 };
  });
  await list.request({});
  for (state of ['exception', 'business']) {
    const result = await list.request({});
    assert.equal(result.success, false, '表格依此保留上次成功结果');
    assert.equal(list.notices.at(-1).type, 'error');
  }
  state = 'success';
  assert.equal((await list.request({})).total, 16);
  assert.equal(list.notices.at(-1), undefined);
});
