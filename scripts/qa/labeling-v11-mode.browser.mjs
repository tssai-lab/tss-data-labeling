// Run with LABELING_UI_BASE against default-off dev or production preview.
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('../../.qa/node_modules/playwright');
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage();
const base = process.env.LABELING_UI_BASE || 'http://127.0.0.1:8001';
const writes = [];
let requests = 0;
await page.addInitScript(() =>
  sessionStorage.setItem('token', JSON.stringify('synthetic-mode-fixture')),
);
await page.route('**/api/**', (route) => {
  const req = route.request();
  if (req.method() !== 'GET')
    writes.push(`${req.method()} ${new URL(req.url()).pathname}`);
  if (req.url().includes('/api/labeling/v1/')) {
    requests++;
    return route.fulfill({
      status: 403,
      json: {
        error: {
          code: 'FORBIDDEN',
          message: '合成真实接口拒绝，不得降级为模拟成功',
        },
        requestId: 'mode-fixture-request',
      },
    });
  }
  return route.fulfill({
    json: {
      code: 200,
      data: req.url().includes('current-user')
        ? { id: 1, username: '合成模式测试用户', roleId: 3 }
        : {},
    },
  });
});
try {
  await page.goto(`${base}/#/labeling/demo`);
  await page
    .getByText('开发演示默认关闭，生产构建不可开启', { exact: false })
    .waitFor();
  await page.goto(`${base}/#/labeling/demo/energy`);
  await page
    .getByText('开发演示默认关闭，生产构建不可开启', { exact: false })
    .waitFor();
  assert.equal(
    await page.getByRole('button', { name: '交回当前能耗包' }).count(),
    0,
  );
  assert.equal(
    await page.getByRole('button', { name: '创建成果导出' }).count(),
    0,
  );
  await page.goto(`${base}/#/labeling`);
  await page.getByText('FORBIDDEN', { exact: false }).waitFor();
  assert.ok(requests > 0);
  assert.equal(
    await page.evaluate(() => JSON.parse(sessionStorage.getItem('token'))),
    'synthetic-mode-fixture',
  );
  assert.deepEqual(writes, []);
  const result = {
    mode: process.env.LABELING_MODE_NAME || 'default-off',
    fixture: true,
    checks: [
      'demo route cannot enable missing capabilities',
      'real-client 403 retains token and error/requestId',
      'no simulated or real writes',
    ],
    passed: true,
  };
  await writeFile(
    new URL(
      `../../docs/labeling-v11-template-evidence/${result.mode}-report.json`,
      import.meta.url,
    ),
    JSON.stringify(result, null, 2),
  );
  console.log(JSON.stringify(result));
} catch (error) {
  console.log(
    'MODE_FAILURE_STATE',
    page.url(),
    (await page.locator('body').innerText()).slice(0, 2400),
  );
  throw error;
} finally {
  await browser.close();
}
