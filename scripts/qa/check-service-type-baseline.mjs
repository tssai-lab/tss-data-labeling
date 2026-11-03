/** 历史服务逐步收紧：旧 any 数量不能增长，新服务不得引入 any。 */
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import ts from 'typescript';
const root = fileURLToPath(new URL('../../', import.meta.url));
const baseline = JSON.parse(readFileSync(new URL('./service-type-baseline.json', import.meta.url), 'utf8'));
const counts = {};
function scan(relative) {
  for (const item of readdirSync(path.join(root, relative), {withFileTypes:true})) {
    const file = `${relative}/${item.name}`;
    if (item.isDirectory()) scan(file);
    else if (/\.tsx?$/.test(file)) {
      let count = 0;
      const source = ts.createSourceFile(file, readFileSync(path.join(root,file),'utf8'), ts.ScriptTarget.Latest, true);
      function visit(node) { if(node.kind === ts.SyntaxKind.AnyKeyword) count++; ts.forEachChild(node,visit); }
      visit(source); counts[file] = count;
      assert.ok(count <= (baseline[file] ?? 0), `${file}: any=${count}，允许上限=${baseline[file] ?? 0}；请补充真实类型，不提高基线绕过检查`);
    }
  }
}
scan('src/services');
console.log(`服务类型基线通过：${Object.keys(counts).length} 个文件，新增 any 不允许；历史警告不代表已清零。`);
