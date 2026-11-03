import assert from 'node:assert/strict';
import test from 'node:test';

import {
  apiMessage,
  isApiSuccess,
  localSmsCode,
} from './smsResponsePresentation.mjs';

test('recognizes current and legacy success shapes', () => {
  assert.equal(isApiSuccess({ code: 200 }), true);
  assert.equal(isApiSuccess({ status: 'ok' }), true);
  assert.equal(isApiSuccess({ code: 500 }), false);
});

test('prefers the backend message field and keeps msg compatibility', () => {
  assert.equal(apiMessage({ message: '短信服务尚未开通' }, '失败'), '短信服务尚未开通');
  assert.equal(apiMessage({ msg: '旧错误' }, '失败'), '旧错误');
  assert.equal(apiMessage({}, '失败'), '失败');
});

test('only exposes a six-digit code returned by isolated local mode', () => {
  assert.equal(localSmsCode({ data: { code: '123456' } }), '123456');
  assert.equal(localSmsCode({ data: { code: 'secret' } }), '');
  assert.equal(localSmsCode({}), '');
});
