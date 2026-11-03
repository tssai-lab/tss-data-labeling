import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
const source = readFileSync(new URL('../pages/task/compare/comparisonModel.tsx', import.meta.url), 'utf8');
const ast = ts.createSourceFile('compare.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const names = new Set(['normalizeTaskListResponse', 'formatImprovementTooltipHtml', 'shortId', 'escapeTooltipText']);
const code = ast.statements.filter(node => ts.isFunctionDeclaration(node) && names.has(node.name?.text)).map(node => node.getText(ast)).join('\n');
const exports = {};
vm.runInNewContext(ts.transpileModule(`${code}\nexports.list = normalizeTaskListResponse; exports.tooltip = formatImprovementTooltipHtml;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText, { exports, formatDisplayDateTime: value => value });
test('对比目录：正常空数据和已有嵌套包装保持兼容，错误响应不得变为空', () => {
  for (const payload of [{ data: [] }, { code: 200, data: { data: [] } }]) assert.equal(exports.list(payload).length, 0);
  for (const payload of [null, {}, { success: false, data: [] }, { code: 403, data: [] }, { data: [null] }, { data: [{}] }]) assert.throws(() => exports.list(payload), /响应/);
});
test('图表提示：用户输入的任务名称不能作为 HTML 执行', () => {
  const html = exports.tooltip({ taskId: 'a', taskName: '<img src=x onerror=alert(1)>', createTime: '<script>x</script>' }, ['<b>0.5</b>']);
  assert.ok(!html.includes('<img') && !html.includes('<script>') && !html.includes('<b>'));
  assert.ok(html.includes('&lt;img') && html.includes('&lt;b&gt;'));
});
