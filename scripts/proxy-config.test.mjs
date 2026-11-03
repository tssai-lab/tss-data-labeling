import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import path from 'node:path';
import ts from 'typescript';

function loadDevProxy(env = {}) {
  const exports = {};
  const source = ts.transpileModule(readFileSync(new URL('../config/proxy.ts', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(source, { exports, process: { env } });
  return exports.default;
}

test('所有开发环境默认连接本机，不使用环境名选择真实服务器', () => {
  const config = loadDevProxy({ DEV_SERVER: 'master' });
  for (const mode of ['dev', 'test', 'pre', 'prod']) {
    assert.equal(config[mode]['/api/'].target, 'http://127.0.0.1:8080');
    assert.equal(config[mode]['/v3/api-docs'].target, 'http://127.0.0.1:8080');
    assert.equal(config[mode]['/mlflow-api/'].target, 'http://127.0.0.1:5000');
    assert.equal(config[mode]['/mlflow-api/'].pathRewrite['^/mlflow-api'], '/ajax-api');
  }
});

test('远端网关联调需要明确目标且不重复改写 MLflow 路径', () => {
  const config = loadDevProxy({
    DEV_API_TARGET: 'https://platform.example',
    DEV_MLFLOW_TARGET: 'https://platform.example',
    DEV_MLFLOW_THROUGH_GATEWAY: 'true',
  }).dev;
  assert.equal(config['/api/'].target, 'https://platform.example');
  assert.equal(config['/mlflow-api/'].pathRewrite, undefined);
});

test('直连自定义 MLflow 仍需改写路径', () => {
  const config = loadDevProxy({ DEV_MLFLOW_TARGET: 'http://mlflow.example:5000' }).dev;
  assert.equal(config['/mlflow-api/'].target, 'http://mlflow.example:5000');
  assert.equal(config['/mlflow-api/'].pathRewrite['^/mlflow-api'], '/ajax-api');
});

test('静态预览服务的请求默认只代理到本机，允许明确覆盖', () => {
  for (const [env, host, port] of [
    [{}, '127.0.0.1', '8080'],
    [{ BACKEND_PROXY_TARGET: 'https://backend.example:8443' }, 'backend.example', '8443'],
  ]) {
    const routes = new Map();
    const requests = [];
    const app = { use: (route, handler) => routes.set(route, handler), get() {}, listen() {} };
    const express = Object.assign(() => app, { static: () => () => {} });
    const transport = { request: options => { requests.push(options); return { on() {} }; } };
    const dependencies = { express, 'node:http': transport, 'node:https': transport, 'node:path': path, 'node:url': { URL } };
    vm.runInNewContext(readFileSync(new URL('./serve-dist.cjs', import.meta.url), 'utf8'), {
      require: name => { assert.ok(name in dependencies); return dependencies[name]; },
      process: { env }, __dirname: '/test/scripts', console,
    });
    routes.get('/api')({ originalUrl: '/api/user/current-user', headers: {}, method: 'GET', pipe() {} }, {});
    assert.equal(requests[0].hostname, host);
    assert.equal(requests[0].port, port);
    assert.equal(requests[0].path, '/api/user/current-user');
  }
});
