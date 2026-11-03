import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';

/** 测试专用：执行真实 TS 模块及其内部依赖，仅替换明确声明的网络/浏览器边界。 */
export function loadTypeScriptModule(entry, dependencies = {}, globals = {}) {
  const cache = new Map();
  const canonical = url => url.href.replace(/\.(tsx?|mjs|js)$/, '');
  const overrides = new Map(Object.entries(dependencies).filter(([name]) => name.startsWith('.')).map(([name, value]) => [canonical(new URL(name, entry)), value]));
  const sourceRoot = new URL('../../src/', import.meta.url);
  function load(url) {
    const key = canonical(url);
    if (overrides.has(key)) return overrides.get(key);
    if (cache.has(key)) return cache.get(key);
    const candidate = [url, ...['.ts', '.tsx', '.mjs', '.js'].map(ext => new URL(url.href + ext))].find(item => existsSync(fileURLToPath(item)));
    assert.ok(candidate, `找不到被测模块：${url.href}`);
    const exports = {};
    cache.set(key, exports);
    const source = ts.transpileModule(readFileSync(candidate, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React },
    }).outputText;
    vm.runInNewContext(source, {
      exports, process: { env: {} }, FormData, Blob, URLSearchParams, console, ...globals,
      require(name) {
        if (Object.hasOwn(dependencies, name)) return dependencies[name];
        if (name.startsWith('.')) return load(new URL(name, candidate));
        if (name.startsWith('@/')) return load(new URL(name.slice(2), sourceRoot));
        assert.fail(`未声明的测试外部依赖：${name}`);
      },
    }, { filename: fileURLToPath(candidate) });
    return exports;
  }
  return load(entry);
}
