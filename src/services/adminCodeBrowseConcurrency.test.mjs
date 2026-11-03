import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

const source = readFileSync(new URL('../pages/task/trainingCode/adminAssets/useAdminCodeBrowser.ts', import.meta.url), 'utf8');
const ast = ts.createSourceFile('page.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const callbacks = {};
function visit(node) {
  if (ts.isVariableDeclaration(node) && node.initializer) callbacks[node.name.getText(ast)] = node.initializer.getText(ast);
  ts.forEachChild(node, visit);
}
visit(ast);
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function page({ content = async (_id, path) => path, metadata = async (_id, path) => ({ path, editable: true, readOnly: false, workspaceRevision: 2 }), tree = async () => [] } = {}) {
  const state = { browse: { mode: 'workspace', targetId: 'w', workspaceRevision: 1, fileEditable: true }, previewContent: 'old', originalPreviewContent: 'old' };
  const context = {
    exports: {}, console, browseRef: { current: state.browse }, browseLoadSequence: { current: 0 }, fileLoadSequence: { current: 0 },
    getAdminCodeWorkspaceFileContent: content, getAdminCodeVersionFileContent: content,
    getAdminCodeWorkspaceFileMetadata: metadata, extractV2FileText: value => value,
    fetchAllV2CodeTreeFiles: tree, getAdminCodeWorkspaceTree() {}, getAdminCodeVersionTree() {},
    buildCodeFileTreeData: value => value, collectCodeFileTreeExpandedKeys: () => [],
    getApiErrorMessage: (e, fallback) => e?.message || fallback, message: { error() {} },
  };
  for (const key of ['browse', 'browseFiles', 'selectedPath', 'previewLoading', 'previewContent', 'originalPreviewContent', 'expandedKeys', 'browseLoading', 'browseReadError', 'fileReadError']) {
    context[`set${key[0].toUpperCase()}${key.slice(1)}`] = value => { state[key] = typeof value === 'function' ? value(state[key]) : value; };
  }
  const names = ['patchBrowse', 'closeBrowse', 'loadBrowseFile', 'loadBrowseTree'];
  const text = names.map(name => `const ${name} = ${callbacks[name]};`).join('\n') + `\nexports.api = {${names.join(',')}};`;
  vm.runInNewContext(ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText, context);
  return { ...context.exports.api, state, context };
}
test('文件切换：迟到成功不能覆盖新文件，加载中不保留旧内容或旧编辑资格', async () => {
  const held = deferred(); const p = page({ content: async (_id, path) => path === 'a.py' ? held.promise : 'new' });
  const first = p.loadBrowseFile(p.state.browse, 'a.py');
  assert.equal(p.state.previewContent, ''); assert.equal(p.state.browse.fileEditable, false);
  await p.loadBrowseFile(p.state.browse, 'b.py');
  held.resolve('old'); await first;
  assert.equal(p.state.selectedPath, 'b.py'); assert.equal(p.state.previewContent, 'new');
});
test('文件切换：迟到失败不能清空新内容或覆盖新错误状态', async () => {
  const held = deferred(); const p = page({ content: async (_id, path) => path === 'a.py' ? held.promise : 'new' });
  const first = p.loadBrowseFile(p.state.browse, 'a.py');
  await p.loadBrowseFile(p.state.browse, 'b.py'); held.reject(new Error('old failure')); await first;
  assert.equal(p.state.previewContent, 'new'); assert.equal(p.state.fileReadError, undefined);
});
test('文件元数据：读取失败、畸形或身份不符均不能把未知当可编辑', async () => {
  for (const metadata of [async () => { throw new Error('403'); }, async () => ({}), async () => ({ path: 'other', editable: true, readOnly: false, workspaceRevision: 1 })]) {
    const p = page({ metadata }); await p.loadBrowseFile(p.state.browse, 'a.py');
    assert.equal(p.state.browse.fileEditable, false); assert.ok(p.state.fileReadError);
    assert.equal(p.state.previewContent, '');
  }
});
test('关闭预览：在途文件和目录响应都不能复活已关闭页面', async () => {
  for (const kind of ['file', 'tree']) {
    const held = deferred(); const p = page(kind === 'file' ? { content: () => held.promise } : { tree: () => held.promise });
    const loading = kind === 'file' ? p.loadBrowseFile(p.state.browse, 'a.py') : p.loadBrowseTree(p.state.browse);
    p.closeBrowse(); held.resolve(kind === 'file' ? 'old' : [{ path: 'a.py' }]); await loading;
    assert.equal(p.state.previewContent, ''); assert.equal(p.state.browse, null); assert.equal(p.state.browseFiles.length, 0);
  }
});
test('目录加载：失败持续提示，重试清除错误且不会误报为空', async () => {
  let fail = true; const p = page({ tree: async () => { if (fail) throw new Error('subtree failed'); return []; } });
  await p.loadBrowseTree(p.state.browse); assert.match(p.state.browseReadError, /subtree failed/);
  fail = false; await p.loadBrowseTree(p.state.browse); assert.equal(p.state.browseReadError, undefined);
});
