import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

const text = readFileSync(new URL('../pages/task/trainingCode/detail/useOwnerCodeDetailReads.tsx', import.meta.url), 'utf8');
const ast = ts.createSourceFile('page.tsx', text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const callbacks = {};
function visit(node) { if (ts.isVariableDeclaration(node) && node.initializer) callbacks[node.name.getText(ast)] = node.initializer.getText(ast); ts.forEachChild(node, visit); }
visit(ast);
function page(preview) {
  const state = {};
  const context = { exports: {}, useCallback: fn => fn, adminReviewMode: false, codeVersionId: 'v', meta: { codeAssetId: 'a' },
    previewSequence: { current: 0 }, previewCodeEditableFile: preview,
    getApiErrorMessage: (error, fallback) => error?.message || fallback, message: { error() {} },
  };
  for (const key of ['selectedPath', 'previewLoading', 'previewContent', 'originalPreviewContent', 'previewFileName', 'previewReadError']) context[`set${key[0].toUpperCase()}${key.slice(1)}`] = value => { state[key] = value; };
  vm.runInNewContext(ts.transpileModule(`exports.load = ${callbacks.loadPreview};`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText, context);
  return { state, context, load: context.exports.load };
}
test('本人代码预览：迟到成功不得覆盖新路径、内容和原始内容', async () => {
  let resolve; const held = new Promise(yes => { resolve = yes; });
  const p = page(async ({ path }) => path === 'a.py' ? held : { data: { content: 'new' } });
  const old = p.load('a.py'); await p.load('b.py'); resolve({ data: { content: 'old' } }); await old;
  assert.equal(p.state.previewContent, 'new'); assert.equal(p.state.originalPreviewContent, 'new'); assert.equal(p.state.selectedPath, 'b.py');
});
test('本人代码预览：失败保留明确错误；重试空文件是成功而非继续显示错误', async () => {
  let fail = true; const p = page(async () => { if (fail) throw new Error('403'); return { data: { content: '' } }; });
  await p.load('a.py'); assert.equal(p.state.previewReadError, '403');
  fail = false; await p.load('a.py'); assert.equal(p.state.previewReadError, undefined); assert.equal(p.state.previewContent, '');
});
