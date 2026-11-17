// Synthetic UI only. Never loads the restricted energy package or contacts a backend/model.
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { strFromU8, unzipSync } from 'fflate';

const require = createRequire(import.meta.url);
const { chromium } = require('../../.qa/node_modules/playwright');
const base = process.env.LABELING_UI_BASE || 'http://127.0.0.1:8001';
const output = fileURLToPath(
  new URL('../../docs/labeling-v11-template-evidence/', import.meta.url),
);
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({
  viewport: { width: 1280, height: 900 },
  acceptDownloads: true,
});
const report = {
  synthetic: true,
  checks: [],
  captures: [],
  pageErrors: [],
  apiRequests: [],
};
page.on('pageerror', (error) => report.pageErrors.push(error.message));
await page.route('**/api/**', (route) => {
  report.apiRequests.push(route.request().url());
  return route.abort();
});
async function select(name, label) {
  const combo = page.getByRole('combobox', { name, exact: true });
  await combo.waitFor({ state: 'attached' });
  await combo.locator('xpath=../..').click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .getByText(label, { exact: true })
    .click();
}
async function capture(name, width) {
  await page.setViewportSize({ width, height: 900 });
  await page.screenshot({
    path: `${output}/${name}-${width}.png`,
    fullPage: true,
  });
  const dimensions = await page.evaluate(() => ({
    viewport: innerWidth,
    document: document.documentElement.scrollWidth,
  }));
  assert.ok(dimensions.document <= width + 1, `${name} overflows at ${width}`);
  report.captures.push({ name, ...dimensions });
}
async function markFirst() {
  await select('能耗主标签', 'spike');
  await select('人工自评', 'UNKNOWN');
  await page
    .getByRole('textbox', { name: '能耗备注', exact: true })
    .fill('合成边界测试：不代表真实尖峰规则');
  await select('选择小时', '14:00');
  await select('定位标签', 'spike');
  await page.getByRole('button', { name: '添加小时标记', exact: true }).click();
  await select('选择小时', '22:00');
  await page.getByRole('spinbutton', { name: '区间结束小时' }).fill('24');
  await page.getByRole('spinbutton', { name: '区间结束小时' }).press('Tab');
  await page.getByRole('button', { name: '添加区间标记', exact: true }).click();
}
try {
  await page.goto(`${base}/#/labeling/demo/energy`);
  await page.getByText('0/10 草稿完成', { exact: true }).waitFor();
  assert.equal(
    await page.getByRole('button', { name: '交回当前能耗包' }).isDisabled(),
    true,
  );
  await page
    .getByText('零值是实际数值，不等于缺测。', { exact: false })
    .waitFor();
  await markFirst();
  page.once('dialog', (dialog) => dialog.dismiss());
  await page
    .getByRole('link', { name: '图片分类合成演示', exact: true })
    .click();
  assert.match(page.url(), /\/labeling\/demo\/energy/);
  await page
    .getByRole('button', { name: '保存并完成当前试标', exact: true })
    .click();
  await page
    .getByText('已保存并读回 synthetic-energy-1 · revision 1', { exact: false })
    .waitFor();
  await page.reload();
  await page.getByText('1/10 草稿完成', { exact: true }).waitFor();
  await page.getByText('[22:00,24:00) · spike', { exact: true }).waitFor();
  assert.equal(
    await page
      .getByRole('textbox', { name: '能耗备注', exact: true })
      .inputValue(),
    '合成边界测试：不代表真实尖峰规则',
  );
  for (const width of [1280, 768, 390])
    await capture('energy-annotations', width);
  await page.getByRole('button', { name: '清空试标', exact: true }).click();
  await page
    .getByRole('button', { name: '保存未完成试标草稿', exact: true })
    .click();
  await page
    .getByText('已保存并读回 synthetic-energy-1 · revision 2', { exact: false })
    .waitFor();
  await page.reload();
  await page.getByText('0/10 草稿完成', { exact: true }).waitFor();
  assert.equal(
    await page
      .getByRole('textbox', { name: '能耗备注', exact: true })
      .inputValue(),
    '',
  );
  report.checks.push(
    '24 hourly kWh points, UTC+8 and independent daily value; dirty navigation guard; point/interval exact coordinates survive save/reload; cleared saved result remains empty',
  );
  await page.setViewportSize({ width: 1280, height: 900 });
  await markFirst();
  await page
    .getByRole('button', { name: '保存并完成当前试标', exact: true })
    .click();
  await page.getByText('1/10 草稿完成', { exact: true }).waitFor();
  for (let n = 2; n <= 10; n++) {
    await page.getByRole('button', { name: `打开 ${n}`, exact: true }).click();
    if (n === 2) {
      await page
        .getByText('缺测 1 个小时，曲线断开。', { exact: false })
        .waitFor();
      await capture('energy-missing-not-zero', 390);
      await page.setViewportSize({ width: 1280, height: 900 });
    }
    await select('能耗主标签', 'normal');
    await select('人工自评', 'UNKNOWN');
    await page
      .getByRole('button', { name: '保存并完成当前试标', exact: true })
      .click();
    await page.getByText(`${n}/10 草稿完成`, { exact: true }).waitFor();
    if (n === 9)
      assert.equal(
        await page.getByRole('button', { name: '交回当前能耗包' }).isDisabled(),
        true,
      );
  }
  page.once('dialog', (dialog) => dialog.accept());
  await page
    .getByRole('button', { name: '交回当前能耗包', exact: true })
    .click();
  await page
    .getByText('人工包已交回，仍待需求方确认', { exact: true })
    .waitFor();
  assert.equal(
    await page
      .getByRole('button', { name: '保存并完成当前试标', exact: true })
      .isDisabled(),
    true,
  );
  await select('能耗合成视角', '需求方视角（合成）');
  await page.getByRole('button', { name: '固定已确认能耗成果' }).click();
  await page.getByText('FINAL_RESULTS_INCOMPLETE', { exact: false }).waitFor();
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '明确确认当前10份能耗结果' }).click();
  await page.getByRole('button', { name: '固定已确认能耗成果' }).click();
  const downloadButton = page
    .getByRole('button', { name: /^下载 synthetic-energy-final-/ })
    .last();
  await downloadButton.waitFor();
  const pending = page.waitForEvent('download');
  await downloadButton.click();
  const download = await pending;
  await download.saveAs(`${output}/synthetic-energy-confirmed.zip`);
  const files = unzipSync(
    await readFile(`${output}/synthetic-energy-confirmed.zip`),
  );
  const records = strFromU8(files['annotations.jsonl'])
    .trim()
    .split('\n')
    .map(JSON.parse);
  assert.equal(records.length, 10);
  assert.equal(JSON.parse(strFromU8(files['manifest.json'])).synthetic, true);
  assert.equal(
    records[0].result.annotations.find((a) => a.type === 'timeseries_point')
      .value.timestamp,
    '2026-01-02T14:00:00+08:00',
  );
  assert.equal(
    records[0].result.annotations.find((a) => a.type === 'timeseries_interval')
      .value.end,
    '2026-01-03T00:00:00+08:00',
  );
  assert.equal(records[0].confidence, null);
  report.checks.push(
    '9/10 cannot submit; 10/10 handback is read-only and still pending review; explicit owner confirmation creates immutable JSONL/CSV/manifest ZIP with typed coordinates and source hashes',
  );
  await page.getByRole('button', { name: '打开 1', exact: true }).click();
  await page
    .getByRole('textbox', { name: '能耗需求方修订原因' })
    .fill('合成需求方独立修订');
  await select('能耗主标签', 'holiday');
  await page
    .getByRole('button', { name: '保存需求方修订（仍待确认）' })
    .click();
  await page.getByText('已生成需求方新修订', { exact: false }).waitFor();
  await page.getByRole('button', { name: '固定已确认能耗成果' }).click();
  await page.getByText('FINAL_RESULTS_INCOMPLETE', { exact: false }).waitFor();
  await page.getByRole('button', { name: '固定质检预览清单' }).click();
  await page.getByText('10条 / synthetic-qc-1', { exact: true }).waitFor();
  await page
    .getByRole('textbox', { name: '质检抽样种子' })
    .fill('not-yet-saved');
  await page.getByText('10条 / synthetic-qc-1', { exact: true }).waitFor();
  await capture('energy-owner-quality', 1280);
  await capture('energy-owner-quality', 390);
  await page
    .getByRole('link', { name: '图片分类合成演示', exact: true })
    .click();
  await page
    .getByRole('button', { name: '查看种子 PARTIAL 批次', exact: true })
    .waitFor();
  report.checks.push(
    'owner revision creates a separate pending candidate; fixed QC preview does not change when inputs change; gold remains unconfirmed, double-blind publication disabled pending API; kappa is synthetic primary-label pairing only; clean navigation leaves the editor normally',
  );
  assert.deepEqual(report.pageErrors, []);
  assert.deepEqual(report.apiRequests, []);
  report.passed = true;
  console.log(
    JSON.stringify({
      passed: true,
      captures: report.captures.length,
      checks: report.checks,
    }),
  );
} catch (error) {
  console.log(
    'ENERGY_FAILURE_STATE',
    page.url(),
    (await page.locator('body').innerText()).slice(-2400),
  );
  await page.screenshot({
    path: fileURLToPath(
      new URL('../../.qa/energy-last-state.png', import.meta.url),
    ),
    fullPage: true,
  });
  throw error;
} finally {
  await writeFile(
    `${output}/energy-browser-report.json`,
    JSON.stringify(report, null, 2),
  );
  await browser.close();
}
