import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as compatibility from '../utils/apiCompatibility.mjs';
import * as receipt from '../utils/codeUploadReceipt.mjs';
import * as pagination from './paginatedCandidates.mjs';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

// 执行真实服务及正常化函数；网络替身只允许本测试声明的 GET。
function load(file, dependencies) {
  return loadTypeScriptModule(new URL(file, import.meta.url), dependencies);
}
const row = id => ({ versionId: id, assetId: 'asset', codeName: `代码 ${id}`, fileName: `${id}.py`, trainingProfile: 'cv', approvalStatus: 'PENDING' });
const asset = id => ({ id, name: `资产 ${id}`, ownerUserId: 1, trainingProfile: 'cv' });
function queue({ primary = { items: [], totalElements: 0 }, assets = { items: [] }, versions = async () => [], detail } = {}) {
  const calls = [];
  const request = async (url, options = {}) => {
    assert.equal(options.method, 'GET');
    calls.push({ url, params: options.params });
    if (url === '/v2/admin/code-review-tasks') return typeof primary === 'function' ? primary() : primary;
    if (url === '/v2/admin/code-assets') return typeof assets === 'function' ? assets() : assets;
    const match = url.match(/^\/v2\/admin\/code-assets\/([^/]+)\/versions$/);
    if (match) return versions(match[1]);
    if (detail) return detail(url);
    assert.fail(`不应请求 ${url}`);
  };
  const v2 = load('./codeV2.ts', { '@umijs/max': { request }, '@/constants/request': { FILE_DOWNLOAD_REQUEST_TIMEOUT: 10000 } });
  const service = load('./code.ts', {
    '@umijs/max': { request }, '@/utils/authFileDownload': {}, '@/constants/trainingCode': {},
    '@/utils/pendingCodeVersions': { listPendingCodeVersions: () => [] },
    '@/utils/codeUploadReceipt.mjs': receipt, '@/utils/apiCompatibility.mjs': compatibility,
    './paginatedCandidates.mjs': pagination, './codeV2': v2,
  });
  return { fetch: service.fetchPendingCodeReviewTasks, calls };
}

test('管理员队列：主接口网络/权限/业务错误不触发资产补查或旧接口', async () => {
  for (const failure of [new Error('timeout'), ...[401, 403, 404, 429, 500].map(status => ({ response: { status } }))]) {
    const state = queue({ primary: async () => { throw failure; } });
    await assert.rejects(state.fetch(), error => error === failure);
    assert.equal(state.calls.length, 1);
  }
});

test('管理员队列：畸形主列表不伪装为空或触发补查', async () => {
  for (const primary of [null, {}, { data: {} }, { errorCode: 'DENIED', items: [] }, { success: false, data: [] },
    { code: 403, items: [] }, { items: [{}] }, { items: [null] }]) {
    const state = queue({ primary });
    await assert.rejects(state.fetch());
    assert.equal(state.calls.length, 1);
  }
});

test('管理员队列：已知数组及嵌套包装继续可读，真实空队列不是失败', async () => {
  for (const wrap of [items => items, items => ({ items }), items => ({ data: { items } }), items => ({ data: items })]) {
    const full = await queue({ primary: wrap([row('v1')]) }).fetch();
    assert.equal(full.data[0].codeVersionId, 'v1');
    assert.equal(full.total, 1);
    const empty = await queue({ primary: wrap([]) }).fetch();
    assert.equal(empty.data.length, 0);
    assert.ok(!empty.incomplete);
  }
});

test('管理员队列：主列表为空且资产入口或全部版本失败时不能宣称没有待审', async () => {
  for (const config of [
    { assets: async () => { throw new Error('offline'); } },
    { assets: { errorCode: 'FORBIDDEN', items: [] } },
    { assets: {} },
    { assets: { items: [asset('a')] }, versions: async () => { throw new Error('timeout'); } },
    { assets: { items: [asset('a')] }, versions: async () => ({}) },
    { assets: { items: [asset('a')] }, versions: async () => ({ code: 403, items: [] }) },
    { assets: { items: [asset('a')] }, versions: async () => [{}] },
    { assets: { items: [{}] } },
  ]) await assert.rejects(queue(config).fetch(), /补查|版本|列表/);
});

test('管理员队列：部分版本补查成功保留记录并报告缺项，去重及状态规则不变', async () => {
  const state = queue({ assets: { items: [asset('good'), asset('bad')] }, versions: async id => {
    if (id === 'bad') throw new Error('offline');
    return [row('v1'), row('v1'), { ...row('approved'), approvalStatus: 'APPROVED' }];
  } });
  const result = await state.fetch();
  assert.equal(result.data.length, 1);
  assert.equal(result.data[0].codeVersionId, 'v1');
  assert.equal(result.incomplete, true);
  assert.match(result.warningMessage, /1.*版本列表.*失败/);
});

test('管理员队列：已有主列表时补查全部失败仍保留主列表，但不声称完整', async () => {
  const state = queue({ primary: { items: [row('main')] }, assets: async () => { throw new Error('offline'); } });
  const result = await state.fetch({ keyword: '代码' });
  assert.equal(result.data[0].codeVersionId, 'main');
  assert.equal(result.incomplete, true);
  assert.match(result.warningMessage, /补查.*失败/);
});

test('管理员队列：补查前 50 个资产不足完整范围时明确告警，不自行扩大扫描', async () => {
  const state = queue({ assets: { items: [asset('a')], totalElements: 51 }, versions: async () => [row('v1')] });
  const result = await state.fetch();
  assert.equal(result.incomplete, true);
  assert.match(result.warningMessage, /范围|完整/);
  assert.equal(state.calls.filter(call => call.url === '/v2/admin/code-assets').length, 1);
  assert.equal(state.calls.find(call => call.url === '/v2/admin/code-assets').params.pageSize, 50);
});

test('管理员队列：分页/风险/时间限制禁止不兼容补查，参数透传不变', async () => {
  for (const params of [{ current: 2 }, { riskLevel: 'HIGH' }, { submittedFrom: '2026-09-01T00:00:00Z' }, { submittedTo: '2026-09-10T00:00:00Z' }]) {
    const state = queue();
    await state.fetch({ ...params, approvalStatus: 'REJECTED', ownerUserId: 8, keyword: '  名称  ', pageSize: 10 });
    assert.equal(state.calls.length, 1);
    assert.equal(state.calls[0].params.approvalStatus, 'REJECTED');
    assert.equal(state.calls[0].params.keyword, '名称');
    assert.equal(state.calls[0].params.ownerUserId, 8);
    assert.equal(state.calls[0].params.page, params.current === 2 ? 1 : 0);
  }
});

test('管理员队列：可选显示名补全失败不删掉已有待审记录，也不改变审核状态', async () => {
  const result = await queue({ primary: { items: [{ ...row('v1'), codeName: '' }] }, detail: async () => { throw new Error('display name unavailable'); } }).fetch();
  assert.equal(result.data[0].codeVersionId, 'v1');
  assert.equal(result.data[0].approvalStatus, 'PENDING');
});

// 执行真实页面回调，检查 ProTable 成功标志、持续提示和本地登记的边界。
function pageRequest(fetch, pending = []) {
  const source = readFileSync(new URL('../pages/task/trainingCode/pending/index.tsx', import.meta.url), 'utf8');
  const ast = ts.createSourceFile('pending.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let initializer;
  const visit = node => {
    if (ts.isVariableDeclaration(node) && node.name.getText(ast) === 'requestList') initializer = node.initializer.getText(ast);
    ts.forEachChild(node, visit);
  };
  visit(ast);
  assert.ok(initializer);
  const output = ts.transpileModule(`exports.request = ${initializer}`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const exports = {}, notices = [];
  let localReads = 0;
  vm.runInNewContext(output, {
    exports, approvalStatusFilter: 'PENDING', ownerUsernameMap: new Map(),
    resolveOwnerUserIdFilter: raw => raw ? Number(raw) : undefined,
    fetchPendingCodeReviewTasks: fetch, listRequestSequence: { current: 0 },
    setListNotice: notice => notices.push(notice), message: { error() {} },
    getApiErrorMessage: (error, fallback) => error?.message || fallback,
    listPendingCodeVersions: () => { localReads++; return pending; }, manualRecordToRow: item => item,
  });
  return { request: exports.request, notices, localReads: () => localReads };
}

test('管理员页面：主请求失败返回 success=false，不用本地手工登记伪装成功', async () => {
  const state = pageRequest(async () => { throw new Error('forbidden'); }, [{ codeVersionId: 'local' }]);
  const result = await state.request({});
  assert.equal(result.success, false);
  assert.equal(state.localReads(), 0);
  assert.equal(state.notices.at(-1).type, 'error');
});

test('管理员页面：部分成功持续提示，恢复后清除告警，仍保留正常本地登记', async () => {
  let incomplete = true;
  const state = pageRequest(async () => ({ success: true, data: [{ codeVersionId: 'v1' }], total: 1, incomplete, warningMessage: '补查不完整' }), [{ codeVersionId: 'local', approvalStatus: 'PENDING' }]);
  const result = await state.request({});
  assert.equal(result.data.length, 2);
  assert.equal(state.notices.at(-1).type, 'warning');
  incomplete = false;
  await state.request({});
  assert.equal(state.notices.at(-1), undefined);
});

test('管理员页面：错误响应不能直接转为空列表成功', async () => {
  for (const res of [undefined, { success: false, data: [] }, { success: true }, { success: true, data: 'bad' }]) {
    const state = pageRequest(async () => res);
    assert.equal((await state.request({})).success, false);
  }
});

test('管理员页面：迟到的旧查询不覆盖新查询结果或告警', async () => {
  let resolve;
  const slow = new Promise(done => { resolve = done; });
  let count = 0;
  const state = pageRequest(async () => ++count === 1 ? slow : { success: true, data: [], total: 0 });
  const first = state.request({});
  await state.request({});
  resolve({ success: true, data: [{ codeVersionId: 'old' }], incomplete: true });
  assert.equal((await first).success, false);
  assert.equal(state.notices.at(-1), undefined);
});

test('管理员页面：分页只设默认 10 条，不用固定值锁死已有条数选择器', () => {
  const source = readFileSync(new URL('../pages/task/trainingCode/pending/index.tsx', import.meta.url), 'utf8');
  const ast = ts.createSourceFile('pending.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let expression;
  const visit = node => {
    if (ts.isJsxAttribute(node) && node.name.getText(ast) === 'pagination' && ts.isJsxExpression(node.initializer) && node.initializer.expression?.getText(ast).includes('showSizeChanger')) {
      expression = node.initializer.expression.getText(ast);
    }
    ts.forEachChild(node, visit);
  };
  visit(ast);
  assert.ok(expression);
  const config = vm.runInNewContext(`(${expression})`);
  assert.equal(config.defaultPageSize, 10);
  assert.equal(config.pageSize, undefined);
  assert.equal(config.showSizeChanger, true);
});
