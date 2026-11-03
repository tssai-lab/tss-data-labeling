import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

const source = ['index.tsx', 'presentation.tsx'].map(file => readFileSync(new URL(`../pages/task/trainingCode/adminAssets/${file}`, import.meta.url), 'utf8')).join('\n');
const ast = ts.createSourceFile('adminAssets.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const callbacks = {};
function visit(node) {
  if (ts.isVariableDeclaration(node) && node.initializer) callbacks[node.name.getText(ast)] = node.initializer.getText(ast);
  if (ts.isFunctionDeclaration(node) && node.name) callbacks[node.name.text] = node.getText(ast);
  ts.forEachChild(node, visit);
}
visit(ast);
const record = id => ({ id, name: `资产 ${id}`, trainingProfile: 'cv', assetRevision: 2 });
const version = id => ({ id: `version-${id}`, codeAssetId: id, versionLabel: 'v1', publishedAt: '2026-09-01T00:00:00Z' });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };

// 执行真实页面回调，仅替换接口和 React 状态容器，不复制被测逻辑。
function page({ detail = async id => record(id), versions = async id => [version(id)], patch = async () => {} } = {}) {
  const state = { activeAsset: record('a'), versions: [version('a')], editing: record('a'), error: undefined, locked: true, loading: false, form: {}, previews: [], messages: [], writes: [], applied: 0 };
  const context = {
    exports: {}, console,
    assetLoadSequence: { current: 0 }, activeAssetRef: { current: state.activeAsset }, browseRef: { current: null },
    actionRef: { current: { reload() {} } },
    getAdminCodeAsset: detail, listAdminCodeAssetVersions: versions,
    patchAdminCodeAsset: async (...args) => { state.writes.push(args); return patch(...args); },
    getApiErrorMessage: (error, fallback) => error?.message || fallback,
    form: { setFieldsValue(values) { state.form = values; }, resetFields() { state.form = {}; }, async validateFields() { return { name: '新名称', trainingProfile: 'cv' }; } },
    message: { success(text) { state.messages.push(['success', text]); }, error(text) { state.messages.push(['error', text]); } },
    closeBrowse() { context.browseRef.current = null; },
    openVersionBrowse: async value => { state.previews.push(value.id); },
    setActiveAsset(value) { state.activeAsset = typeof value === 'function' ? value(state.activeAsset) : value; },
    setEditing(value) { state.editing = value; state.applied++; },
    setVersions(value) { state.versions = value; },
    setVersionsAssetId(value) { state.versionsAssetId = value; },
    setVersionsAssetName(value) { state.versionsAssetName = value; },
    setEditProfileLocked(value) { state.locked = value; },
    setEditOriginalProfile() {}, setEditLoading() {},
    setDetailLoading(value) { state.loading = value; },
    setAssetReadError(value) { state.error = value; },
  };
  Object.defineProperties(context, {
    editing: { get: () => state.editing }, editProfileLocked: { get: () => state.locked },
  });
  const names = ['applyAssetMeta', 'loadAssetMeta', 'exitDetail', 'enterAsset', 'submitEdit', 'retryAssetMeta', 'restoreVersionBrowse'];
  const declarations = names.map(name => `const ${name} = ${callbacks[name]};`).join('\n');
  const code = `${callbacks.pickLatestCodeVersion}\n${declarations}\nexports.api = {${names.join(',')}};`;
  vm.runInNewContext(ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText, context);
  return { ...context.exports.api, state, context };
}

test('资产详情：版本请求失败不得解锁方案、清空版本或提交部分元数据', async () => {
  for (const failure of [new Error('timeout'), ...[401, 403, 404, 429, 500].map(status => ({ response: { status } }))]) {
    const p = page({ versions: async () => { throw failure; } });
    await assert.rejects(p.loadAssetMeta('a'));
    assert.equal(p.state.locked, true);
    assert.equal(p.state.versions.length, 1);
    assert.equal(p.state.applied, 0);
    assert.ok(p.state.error);
    assert.equal(p.state.loading, false);
  }
});

test('资产详情：元数据请求失败明确提示，不能提交另一个成功的版本结果', async () => {
  const p = page({ detail: async () => { throw new Error('资产不可读'); } });
  await assert.rejects(p.loadAssetMeta('a'), /资产不可读/);
  assert.equal(p.state.applied, 0);
  assert.equal(p.state.error, '资产不可读');
});

test('资产详情：畸形及业务错误版本响应不等于零版本', async () => {
  for (const value of [null, {}, { items: [] }, { code: 403, data: [] }, { success: false }, [null], [{}], [{ id: 1 }], [{ id: 'v', codeAssetId: 'other' }]]) {
    const p = page({ versions: async () => value });
    await assert.rejects(p.loadAssetMeta('a'));
    assert.equal(p.state.applied, 0);
    assert.equal(p.state.locked, true);
  }
});

test('资产详情：错误或不对应的资产响应不能写入当前表单', async () => {
  for (const value of [null, [], {}, { ...record('a'), code: 403 }, { ...record('a'), success: false }, { ...record('a'), errorCode: 'DENIED' }, record('b'), { ...record('a'), trainingProfile: 1 }]) {
    const p = page({ detail: async () => value });
    await assert.rejects(p.loadAssetMeta('a'));
    assert.equal(p.state.applied, 0);
  }
});

test('资产详情：真实零版本允许原有方案编辑；编号别名和锁定规则兼容', async () => {
  for (const list of [[], [{ id: 'v' }], [{ versionId: 'v' }], [{ codeVersionId: 'v' }]]) {
    const p = page({ detail: async () => ({ assetId: 'a', trainingProfile: 'cv' }), versions: async () => list });
    const result = await p.loadAssetMeta('a');
    assert.equal(result.versionsNext.length, list.length);
    assert.equal(p.state.locked, list.length > 0);
    assert.equal(p.state.error, undefined);
  }
});

test('资产详情：失败后重试成功，清除错误且不发起写请求', async () => {
  let failed = true;
  const p = page({ versions: async () => { if (failed) throw new Error('失败'); return [version('a')]; } });
  await assert.rejects(p.loadAssetMeta('a'));
  failed = false;
  await p.loadAssetMeta('a');
  assert.equal(p.state.error, undefined);
  assert.equal(p.state.locked, true);
  assert.equal(p.state.writes.length, 0);
});

test('资产详情：慢的 A 响应不得覆盖新打开的 B 或预览 A', async () => {
  const slow = deferred();
  const p = page({ detail: id => id === 'a' ? slow.promise : record(id) });
  const first = p.enterAsset(record('a'));
  await p.enterAsset(record('b'));
  slow.resolve(record('a'));
  await first;
  assert.equal(p.state.activeAsset.id, 'b');
  assert.equal(p.state.editing.id, 'b');
  assert.deepEqual(p.state.previews, ['version-b']);
});

test('资产详情：返回列表后迟到结果不得重新打开详情或预览', async () => {
  const slow = deferred();
  const p = page({ detail: () => slow.promise });
  const pending = p.enterAsset(record('a'));
  p.exitDetail();
  slow.resolve(record('a'));
  await pending;
  assert.equal(p.state.activeAsset, null);
  assert.equal(p.state.editing, null);
  assert.equal(p.state.previews.length, 0);
});

test('资产详情：旧请求失败不得污染新详情错误或加载状态', async () => {
  const slow = deferred();
  const p = page({ detail: id => id === 'a' ? slow.promise : record(id) });
  const first = p.enterAsset(record('a'));
  await p.enterAsset(record('b'));
  slow.reject(new Error('A 过时错误'));
  await first;
  assert.equal(p.state.error, undefined);
  assert.equal(p.state.activeAsset.id, 'b');
  assert.equal(p.state.loading, false);
});

test('资产详情：保存成功但刷新失败不误报保存失败，也不重复 PATCH', async () => {
  const p = page({ detail: async () => { throw new Error('刷新失败'); } });
  await p.submitEdit();
  assert.equal(p.state.writes.length, 1);
  assert.equal(p.state.messages.filter(([kind]) => kind === 'success').length, 1);
  assert.equal(p.state.messages.filter(([kind]) => kind === 'error').length, 0);
  assert.equal(p.state.error, '刷新失败');
  assert.equal('trainingProfile' in p.state.writes[0][1], false);
});

test('资产详情：保存接口失败仍报告失败，不改原有版本号与未锁定方案提交', async () => {
  const p = page({ patch: async () => { throw new Error('revision conflict'); } });
  p.state.locked = false;
  await p.submitEdit();
  assert.equal(p.state.writes[0][1].assetRevision, 2);
  assert.equal(p.state.writes[0][1].trainingProfile, 'cv');
  assert.equal(p.state.messages[0][0], 'error');
  assert.equal(p.state.applied, 0);
});

test('资产详情：重试保留已打开的工作区，不重新预览或创建工作区', async () => {
  const p = page();
  const workspace = { mode: 'workspace', targetId: 'existing-workspace' };
  p.context.browseRef.current = workspace;
  await p.retryAssetMeta();
  assert.equal(p.context.browseRef.current, workspace);
  assert.equal(p.state.previews.length, 0);
  assert.equal(p.state.writes.length, 0);
});

test('资产详情：发布后的只读恢复仍优先指定版本，失败有提示而非空版本', async () => {
  const p = page({ versions: async () => [version('a'), { ...version('a'), id: 'latest', publishedAt: '2026-09-02T00:00:00Z' }] });
  await p.restoreVersionBrowse('version-a');
  assert.deepEqual(p.state.previews, ['version-a']);
  const broken = page({ versions: async () => { throw new Error('版本刷新失败'); } });
  await broken.restoreVersionBrowse();
  assert.equal(broken.state.error, '版本刷新失败');
  assert.equal(broken.state.previews.length, 0);
});

test('资产详情：退出后旧保存回调不重新读取或打开原资产', async () => {
  let reads = 0;
  const p = page({ detail: async () => { reads++; return record('a'); } });
  p.exitDetail();
  assert.equal(await p.loadAssetMeta('a'), undefined);
  assert.equal(reads, 0);
  assert.equal(p.state.loading, false);
});
