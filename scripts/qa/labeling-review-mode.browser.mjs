import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('../../.qa/node_modules/playwright');
const base = process.env.LABELING_UI_BASE || 'http://127.0.0.1:8001';
const mode = process.env.LABELING_REVIEW_MODE || 'default-off';
assert.ok(['default-off', 'production-forced-flag'].includes(mode));
const out =
  process.env.LABELING_REVIEW_EVIDENCE ||
  fileURLToPath(
    new URL('../../docs/labeling-review-evidence/', import.meta.url),
  );
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage();
const requests = [],
  errors = [];
page.on('pageerror', (error) => errors.push(error.message));
await page.route('**/api/**', (route) => {
  requests.push(route.request().url());
  return route.abort();
});
try {
  for (const path of ['/labeling/demo', '/labeling/demo/energy']) {
    await page.goto(`${base}/#${path}`);
    await page
      .getByText('开发演示默认关闭，生产构建不可开启', { exact: false })
      .waitFor();
    assert.equal(
      await page.getByRole('button', { name: '确认采用', exact: true }).count(),
      0,
    );
    assert.equal(
      await page
        .getByRole('button', { name: '创建成果导出', exact: true })
        .count(),
      0,
    );
    assert.equal(
      await page
        .getByRole('button', { name: '交回当前能耗包', exact: true })
        .count(),
      0,
    );
  }
  assert.deepEqual(requests, []);
  assert.deepEqual(errors, []);
  const report = {
    mode,
    passed: true,
    routes: ['/labeling/demo', '/labeling/demo/energy'],
    apiRequests: requests,
    pageErrors: errors,
    checks: [
      'No review/export/energy writes or synthetic results exposed when disabled',
      'No backend requests; production forced flag cannot open the demo',
    ],
  };
  await writeFile(
    `${out}/${mode}-report.json`,
    JSON.stringify(report, null, 2),
  );
  console.log(JSON.stringify(report));
} finally {
  await browser.close();
}
