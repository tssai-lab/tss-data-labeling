import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import * as jsxRuntime from 'react/jsx-runtime';
import ts from 'typescript';

test('帮助按钮指向平台本地手册，并具有可识别的按钮说明', () => {
  const exports = {};
  const dependencies = {
    'react/jsx-runtime': jsxRuntime,
    '@ant-design/icons': { QuestionCircleOutlined: () => React.createElement('span', null, '?') },
    '@umijs/max': {
      Link: ({ to, ...props }) => React.createElement('a', { ...props, href: to }),
    },
  };
  const source = ts.transpileModule(readFileSync(new URL('./index.tsx', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  vm.runInNewContext(source, {
    exports,
    require: name => { assert.ok(name in dependencies); return dependencies[name]; },
  });
  const html = renderToStaticMarkup(React.createElement(exports.Question));
  assert.match(html, /href="\/user-manual"/);
  assert.match(html, /aria-label="平台用户手册"/);
  assert.doesNotMatch(html, /https?:|target="_blank"/);
});
