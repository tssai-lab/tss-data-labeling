import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { loadTypeScriptModule } from '../../../../scripts/qa/load-typescript-module.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const pageSource = fs.readFileSync(path.join(here, 'index.tsx'), 'utf8');

const loadService = request =>
  loadTypeScriptModule(new URL('../../../services/system/user.ts', import.meta.url), {
    '@umijs/max': { request },
    '@/constants/system': {
      SYSTEM_API_CONFIG: {
        ENDPOINTS: { USER_RESET_PASSWORD: '/user/reset-password' },
      },
    },
    '@/constants/systemLabels': {
      SYSTEM_STATUS: { ENABLED: '启用', DISABLED: '禁用' },
      SYSTEM_ROLES: {
        SUPER_ADMIN: '超管',
        NORMAL_ADMIN: '普通管理员',
        USER: '普通用户',
      },
    },
  });

test('超级管理员改密码调用现有重置接口且不发送确认密码', async () => {
  const calls = [];
  const service = loadService(async (url, options) => {
    calls.push([url, options]);
    return { code: 200 };
  });
  const body = { userId: 7, newPassword: 'new_pass_7' };

  await service.resetUserPassword(body);

  assert.equal(calls[0][0], '/user/reset-password');
  assert.equal(calls[0][1].method, 'POST');
  assert.equal(calls[0][1].data, body);
  assert.deepEqual(Object.keys(calls[0][1].data).sort(), ['newPassword', 'userId']);
});

test('用户管理页保留改用户名并只为超管显示改密码入口', () => {
  assert.match(pageSource, /name="username"/);
  assert.match(pageSource, /\{isSuperAdmin && \(\s*<Button[\s\S]*?改密码/);
  assert.match(pageSource, /name="newPassword"/);
  assert.match(pageSource, /name="confirmPassword"/);
  assert.match(pageSource, /两次输入的密码不一致/);
});
