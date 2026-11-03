import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../../scripts/qa/load-typescript-module.mjs';

const load = request =>
  loadTypeScriptModule(new URL('./api.ts', import.meta.url), {
    '@umijs/max': { request },
  });

test('用户名和手机号注册各自调用已有后端接口', async () => {
  const calls = [];
  const api = load(async (url, options) => {
    calls.push([url, options]);
    return { code: 200 };
  });
  const usernameBody = {
    username: 'tester1',
    password: 'secret_1',
    confirmPassword: 'secret_1',
  };
  await api.register(usernameBody);
  await api.registerByMobile({
    ...usernameBody,
    mobile: '13800000000',
    smsCode: '123456',
  });

  assert.equal(calls[0][0], '/user/register/username');
  assert.equal(calls[1][0], '/user/register/mobile');
  assert.equal(calls[0][1].data, usernameBody);
  assert.equal(calls[0][1].method, 'POST');
});
