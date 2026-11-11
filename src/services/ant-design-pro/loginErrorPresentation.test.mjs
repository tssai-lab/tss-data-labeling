import assert from 'node:assert/strict';
import test from 'node:test';
import { loginErrorMessage } from './loginErrorPresentation.mjs';

test('代理故障展示服务不可用，不误报账号错误或泄露上游响应', () => {
  for (const status of [502, 503, 504]) {
    assert.equal(loginErrorMessage({
      response: { status, data: { message: '<html>gateway failure</html>' } },
      message: 'Request failed with status code ' + status,
    }), '登录服务暂时不可用，请稍后重试；若持续失败，请联系管理员。');
  }
});

test('超时和断网可与登录业务错误区分', () => {
  for (const code of ['ECONNABORTED', 'ETIMEDOUT']) {
    assert.equal(loginErrorMessage({ code }), '登录请求超时，请稍后重试。');
  }
  assert.equal(loginErrorMessage({ code: 'ERR_NETWORK' }), '无法连接登录服务，请检查网络连接后重试。');
});

test('保留后端账号校验、业务错误和默认提示', () => {
  assert.equal(loginErrorMessage({ response: { status: 401, data: { msg: '密码错误' } }, message: 'Request failed with status code 401' }), '密码错误');
  assert.equal(loginErrorMessage({ info: { errorMessage: '账号已停用' }, message: 'BizError' }), '账号已停用');
  assert.equal(loginErrorMessage(new Error('验证码已过期')), '验证码已过期');
  assert.equal(loginErrorMessage(null), '登录失败，请重试！');
  assert.equal(loginErrorMessage({ message: '  ' }, '请重新登录'), '请重新登录');
});
