import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createUnauthorizedOnceGate,
  isPublicAuthenticationRequest,
  isUnauthorizedResponse,
  shouldNotifySessionExpired,
} from './authFailure.mjs';

test('recognizes HTTP and business 401 responses', () => {
  assert.equal(
    isUnauthorizedResponse({ response: { status: 401, data: {} } }),
    true,
  );
  assert.equal(
    isUnauthorizedResponse({ response: { status: 200, data: { code: 401 } } }),
    true,
  );
  assert.equal(isUnauthorizedResponse({ info: { code: 401 } }), true);
});

test('does not log out for permission, timeout, or network failures', () => {
  assert.equal(isUnauthorizedResponse({ response: { status: 403 } }), false);
  assert.equal(isUnauthorizedResponse({ code: 'ECONNABORTED' }), false);
  assert.equal(isUnauthorizedResponse({ name: 'AxiosError' }), false);
});

test('keeps public authentication request failures on their forms', () => {
  for (const url of [
    '/user/login',
    '/api/user/register/mobile',
    'http://example.invalid/api/user/sms/code?purpose=LOGIN_REGISTER',
  ]) {
    const error = { config: { url }, response: { status: 401 } };
    assert.equal(isPublicAuthenticationRequest(error), true);
    assert.equal(isUnauthorizedResponse(error), false);
  }
});

test('handles concurrent unauthorized responses only once', () => {
  const gate = createUnauthorizedOnceGate();
  let effects = 0;
  assert.equal(gate.run('expired-token', () => effects++), true);
  assert.equal(gate.run(null, () => effects++), false);
  assert.equal(gate.run('expired-token', () => effects++), false);
  assert.equal(gate.isHandled(), true);
  assert.equal(effects, 1);
});

test('allows a later session to expire after the user logs in again', () => {
  const gate = createUnauthorizedOnceGate();
  let effects = 0;
  assert.equal(gate.run('first-token', () => effects++), true);
  assert.equal(gate.run('second-token', () => effects++), true);
  assert.equal(gate.run(null, () => effects++), false);
  assert.equal(effects, 2);
});

test('only notifies an expired active session, not an anonymous or startup check', () => {
  assert.equal(shouldNotifySessionExpired(null), false);
  assert.equal(shouldNotifySessionExpired(''), false);
  assert.equal(shouldNotifySessionExpired('old-token', true), false);
  assert.equal(shouldNotifySessionExpired('old-token', false), true);
});
