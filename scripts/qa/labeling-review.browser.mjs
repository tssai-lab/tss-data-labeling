// Local synthetic fixtures only. Start npm run dev:labeling-demo; never writes to real services.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { strFromU8, unzipSync } from 'fflate';

const require = createRequire(import.meta.url);
const { chromium } = require('../../.qa/node_modules/playwright');
const base = process.env.LABELING_UI_BASE || 'http://127.0.0.1:8001';
const out =
  process.env.LABELING_REVIEW_EVIDENCE ||
  fileURLToPath(
    new URL('../../docs/labeling-review-evidence/', import.meta.url),
  );
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1586, height: 1100 } });
const report = {
  synthetic: true,
  checks: [],
  screenshots: [],
  pageErrors: [],
  unexpectedRequests: [],
};
page.on('pageerror', (e) => {
  report.pageErrors.push(e.message);
  console.log('DEMO_PAGE_ERROR', page.url(), e.stack);
});
await page.route('**/api/**', (route) => {
  report.unexpectedRequests.push(route.request().url());
  return route.abort();
});
const list = () => page.getByRole('region', { name: '样本列表', exact: true });
const detail = () =>
  page.getByRole('region', { name: '审核详情', exact: true });
const image = () => page.getByRole('region', { name: '原图预览', exact: true });
async function select(name, label) {
  const combo = page.getByRole('combobox', { name, exact: true });
  await combo.waitFor({ state: 'attached' });
  await combo.locator('xpath=../..').click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .getByText(label, { exact: true })
    .click();
}
async function stats(label, value) {
  await page.waitForFunction(
    ({ label, value }) =>
      [
        ...document.querySelectorAll('[aria-label="当前筛选范围统计"] > div'),
      ].some(
        (el) =>
          el.querySelector('span')?.textContent === label &&
          el.querySelector('strong')?.textContent === String(value),
      ),
    { label, value },
  );
}
async function row(n) {
  return list().locator(`tr[data-row-key="demo-sample-${n}"]`);
}
async function focus(n) {
  await (await row(n)).click();
  await page.waitForFunction(
    (n) =>
      document
        .querySelector('[data-sample-id]')
        ?.getAttribute('data-sample-id') === `demo-sample-${n}`,
    n,
  );
  assert.match(await (await row(n)).getAttribute('class'), /activeRow/);
}
async function tab(name) {
  await page.getByRole('tab', { name, exact: true }).click();
}
async function capture(name, width) {
  await page.setViewportSize({ width, height: 1100 });
  await page.screenshot({
    path: `${out}/${name}-${width}.png`,
    fullPage: true,
    animations: 'disabled',
  });
  const dimensions = await page.evaluate(() => ({
    viewport: innerWidth,
    document: document.documentElement.scrollWidth,
  }));
  assert.ok(
    dimensions.document <= width + 1,
    `${name} page overflow: ${JSON.stringify(dimensions)}`,
  );
  report.screenshots.push({ name, ...dimensions });
}
async function more() {
  const button = page.getByRole('button', { name: '加载更多', exact: true });
  if (await button.count()) {
    await button.click();
    await list().getByText('已加载 12 行', { exact: false }).waitFor();
  }
}
async function reset() {
  const button = page.getByRole('button', {
    name: '重置合成种子',
    exact: true,
  });
  if (!(await button.isVisible()))
    await page.getByText('演示控制与项目管理', { exact: true }).click();
  await button.click();
  await stats('已确认', 0);
  await stats('样本范围', 12);
}
async function confirm() {
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '确认操作', exact: true })
    .click();
}
try {
  await page.goto(`${base}/#/labeling/demo`);
  await stats('样本范围', 12);
  await stats('有候选答案', 8);
  await stats('无候选答案', 4);
  await stats('已确认', 0);
  await list().getByText('已加载 6 行', { exact: false }).waitFor();
  assert.equal(
    await page
      .getByRole('tab', { name: '结果与审核', exact: true })
      .getAttribute('aria-selected'),
    'true',
  );
  await detail().getByText('0', { exact: true }).waitFor();
  await focus(2);
  await detail().getByText('0.59', { exact: true }).waitFor();
  await image()
    .locator('img')
    .evaluate((img) => img.decode());
  assert.match(
    await image().locator('img').getAttribute('src'),
    /sample-02\.svg$/,
  );
  await capture('review-desktop', 1586);
  await image().getByRole('button', { name: '下一张', exact: true }).click();
  await detail().getByText('demo-result-3', { exact: true }).waitFor();
  await image().getByRole('button', { name: '上一张', exact: true }).click();
  await detail().getByText('demo-result-2', { exact: true }).waitFor();
  await (await row(1)).getByRole('checkbox').check();
  assert.equal(
    await image().locator('[data-sample-id]').getAttribute('data-sample-id'),
    'demo-sample-2',
  );
  await (await row(1)).getByRole('checkbox').uncheck();
  await more();
  await select('来源筛选', '人工');
  await stats('样本范围', 2);
  await stats('有候选答案', 2);
  await detail().getByText('不适用', { exact: true }).waitFor();
  await image().getByRole('button', { name: '下一张', exact: true }).click();
  await detail().getByText('demo-result-12', { exact: true }).waitFor();
  assert.equal(
    await image()
      .getByRole('button', { name: '下一张', exact: true })
      .isDisabled(),
    true,
  );
  await page.getByRole('button', { name: '清除筛选', exact: true }).click();
  await stats('样本范围', 12);
  await focus(6);
  await detail().getByText('未知', { exact: true }).waitFor();
  await capture('review-mobile', 390);
  const boxes = await Promise.all(
    [list(), image(), detail()].map((region) => region.boundingBox()),
  );
  assert.ok(
    boxes[0].y < boxes[1].y && boxes[1].y < boxes[2].y,
    'mobile order must be list, image, details',
  );
  report.checks.push(
    'Desktop three columns; mobile list/image/details stack; real full-filter totals differ from loaded rows; source/status filtering, pagination, independent checkbox/focus, prev/next and per-sample SVG binding; AI zero/unknown and human not-applicable',
  );
  await page.setViewportSize({ width: 1586, height: 1100 });
  await more();
  await page.route('**/labeling-demo/sample-07.svg', (route) => route.abort());
  await focus(7);
  await image().getByText('此样本暂无本地合成图', { exact: true }).waitFor();
  assert.equal(
    await detail()
      .getByRole('button', { name: '确认采用', exact: true })
      .isDisabled(),
    true,
  );
  await detail()
    .getByRole('button', { name: '标记待修正', exact: true })
    .click();
  await page
    .getByText('标记待修正前请填写操作原因，输入会保留', { exact: true })
    .waitFor();
  await detail()
    .getByRole('textbox', { name: '操作原因', exact: true })
    .fill('合成无答案样本，需要人工核查');
  await detail()
    .getByRole('button', { name: '标记待修正', exact: true })
    .click();
  await page
    .getByRole('dialog')
    .getByText('demo-sample-7', { exact: true })
    .waitFor();
  await page
    .getByRole('dialog')
    .getByText('无答案样本', { exact: true })
    .waitFor();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '确认操作', exact: true })
    .evaluate((button) => {
      button.click();
      button.click();
    });
  await detail().getByText('待修正', { exact: true }).waitFor();
  await detail().getByText('审核 revision 1', { exact: false }).waitFor();
  await focus(2);
  await detail().getByRole('button', { name: '确认采用', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByText('demo-result-2', { exact: true })
    .waitFor();
  await confirm();
  await stats('已确认', 1);
  await detail().getByText('已确认', { exact: true }).waitFor();
  await detail()
    .getByRole('button', { name: '修订为新候选', exact: true })
    .click();
  await select('需求方修订标签', '正常');
  await page
    .getByRole('textbox', { name: '需求方修订原因', exact: true })
    .fill('合成需求方修订，保留原AI候选');
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '创建新修订', exact: true })
    .click();
  await stats('已确认', 0);
  await detail().getByText('需求方 · 不适用', { exact: true }).waitFor();
  await detail().getByText('不适用', { exact: true }).waitFor();
  await detail()
    .getByRole('button', { name: '查看来源历史', exact: true })
    .click();
  const drawer = page.getByRole('dialog');
  const original = drawer
    .getByRole('row')
    .filter({ has: page.getByText('demo-result-2', { exact: true }) });
  await original.getByText('0.59', { exact: true }).waitFor();
  await original
    .getByRole('button', { name: '选择此候选', exact: true })
    .click();
  await detail().getByText('demo-result-2', { exact: true }).waitFor();
  await stats('已确认', 0);
  await detail().getByRole('button', { name: '确认采用', exact: true }).click();
  await confirm();
  await stats('已确认', 1);
  await select('审核状态筛选', '已确认');
  await stats('样本范围', 1);
  await select('来源筛选', '人工');
  await stats('样本范围', 0);
  await image().getByText('当前筛选无样本', { exact: true }).waitFor();
  await page.getByRole('button', { name: '清除筛选', exact: true }).click();
  await stats('样本范围', 12);
  report.checks.push(
    'Missing image placeholder; no-answer reason-required NEEDS_REVISION; double activation writes one review revision; confirm dialog pins sample/result/base revision; owner revision is pending, old AI confidence retained; history choice previews without auto-accept; empty filters are safe',
  );
  await tab('AI进度与分档');
  await page
    .getByRole('textbox', { name: '流程操作原因' })
    .fill('合成状态验证');
  await page.getByText('演示控制与项目管理', { exact: true }).click();
  await page.getByRole('button', { name: '暂停项目', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /确.*定/ })
    .click();
  await page.getByText('PAUSED', { exact: true }).waitFor();
  await tab('结果与审核');
  await focus(3);
  await detail()
    .getByRole('textbox', { name: '操作原因', exact: true })
    .fill('失败后需要保留的原因');
  await detail().getByRole('button', { name: '确认采用', exact: true }).click();
  await confirm();
  await page.getByText('PROJECT_PAUSED', { exact: false }).waitFor();
  await page
    .getByText('输入已保留，请刷新当前范围后重新核对候选版本并确认。', {
      exact: false,
    })
    .waitFor();
  assert.equal(
    await detail()
      .getByRole('textbox', { name: '操作原因', exact: true })
      .inputValue(),
    '失败后需要保留的原因',
  );
  await stats('已确认', 1);
  await reset();
  await more();
  await (await row(1)).getByRole('checkbox').check();
  await (await row(3)).getByRole('checkbox').check();
  await page.getByText('批量操作与固定范围预览', { exact: true }).click();
  await page
    .getByRole('button', { name: '固定勾选样本并预览', exact: true })
    .click();
  await page
    .getByRole('dialog')
    .getByText('demo-sample-1', { exact: true })
    .waitFor();
  await page.getByRole('button', { name: '完成范围预览', exact: true }).click();
  await page
    .getByRole('button', { name: '明确确认所选结果', exact: true })
    .click();
  await confirm();
  await stats('已确认', 2);
  for (const name of [
    '范围发布',
    'AI进度与分档',
    '来源与集成',
    '成果导出',
    '结果与审核',
  ])
    await tab(name);
  report.checks.push(
    'Write failures preserve inputs and show real code plus refresh/reconfirm guidance; batch review still consumes explicit frozen preview; all five tabs remain accessible',
  );
  await tab('成果导出');
  await page.getByRole('button', { name: '选择成果范围', exact: true }).click();
  await page
    .getByRole('button', { name: '固定勾选样本并预览', exact: true })
    .click();
  await page.getByRole('button', { name: '完成范围预览', exact: true }).click();
  await tab('成果导出');
  await page
    .getByRole('button', { name: '确认形成范围成果', exact: true })
    .click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /确.*定/ })
    .click();
  await page
    .getByText('已固定范围成果，项目其他未完成项仍保留', { exact: true })
    .waitFor();
  await page.locator('#workflow-step-5').getByRole('checkbox').nth(1).check();
  await page.getByRole('button', { name: '创建成果导出', exact: true }).click();
  await page.getByRole('button', { name: '查询导出状态', exact: true }).click();
  await page.getByRole('button', { name: '查询导出状态', exact: true }).click();
  await page.getByText('SUCCEEDED · 2 项', { exact: false }).waitFor();
  const pendingDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载分类 ZIP', exact: true }).click();
  const downloaded = await pendingDownload;
  await downloaded.saveAs(`${out}/review-confirmed.zip`);
  const { readFile } = await import('node:fs/promises');
  const archive = unzipSync(await readFile(`${out}/review-confirmed.zip`));
  const manifest = JSON.parse(strFromU8(archive['manifest.json']));
  assert.equal(manifest.sampleCount, 2);
  assert.ok(manifest.records.every((row) => row.reviewStatus === 'ACCEPTED'));
  await reset();
  await tab('结果与审核');
  await more();
  for (let attempt = 0; attempt < 2; attempt++) {
    await (await row(9)).getByRole('checkbox').check();
    const freeze = page.getByRole('button', {
      name: '固定勾选样本并预览',
      exact: true,
    });
    const batchToggle = page
      .locator('.ant-collapse-header')
      .filter({ hasText: '批量操作与固定范围预览' });
    if ((await batchToggle.getAttribute('aria-expanded')) !== 'true')
      await batchToggle.click();
    await select('固定选择用途', 'PUBLISH');
    await freeze.click();
    await page
      .getByRole('button', { name: '完成范围预览', exact: true })
      .click();
    await tab('范围发布');
    await page
      .getByRole('button', { name: '创建合成 AI 执行', exact: true })
      .click();
    await tab('结果与审核');
    await focus(9);
    assert.equal(
      await detail()
        .getByRole('button', { name: '标记待修正', exact: true })
        .isDisabled(),
      true,
    );
    await tab('AI进度与分档');
    await page
      .getByRole('button', { name: '查询执行进度', exact: true })
      .click();
    await page
      .getByRole('button', { name: '查询执行进度', exact: true })
      .click();
    await page.getByText('SUCCEEDED · revision', { exact: false }).waitFor();
    await tab('结果与审核');
    await focus(9);
  }
  await detail()
    .getByText('有多份候选，请从来源历史选择具体版本', { exact: true })
    .waitFor();
  assert.equal(
    await detail()
      .getByRole('button', { name: '确认采用', exact: true })
      .isDisabled(),
    true,
  );
  await detail()
    .getByRole('button', { name: '查看来源历史', exact: true })
    .click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '选择此候选', exact: true })
    .first()
    .click();
  await stats('已确认', 0);
  await detail().getByRole('button', { name: '确认采用', exact: true }).click();
  await confirm();
  await stats('已确认', 1);
  report.checks.push(
    'Existing export flow produces a ZIP with exactly two accepted results; fixed PUBLISH selections execute AI through publish/progress tabs; processing disables writes; multiple unselected candidates require explicit history choice before adoption',
  );
  await reset();
  await tab('结果与审核');
  await more();
  await capture('review-final-mobile', 390);
  assert.deepEqual(report.unexpectedRequests, []);
  const real = await browser.newPage({
    viewport: { width: 1586, height: 1100 },
  });
  await real.addInitScript(() =>
    sessionStorage.setItem('token', JSON.stringify('synthetic-review-fixture')),
  );
  const realRequests = [];
  let denied = false;
  real.on('pageerror', (e) => {
    report.pageErrors.push(e.message);
    console.log('REAL_PAGE_ERROR', real.url(), e.stack);
  });
  await real.route('**/api/**', (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      p = url.pathname.replace('/api/labeling/v1', '');
    if (!url.pathname.startsWith('/api/labeling/v1'))
      return route.fulfill({
        json: {
          code: 200,
          data: url.pathname.includes('current-user')
            ? { id: 1, username: '合成真实接口夹具', roleId: 3 }
            : {},
        },
      });
    realRequests.push(`${req.method()} ${p}`);
    assert.equal(req.method(), 'GET');
    if (p === '/me')
      return route.fulfill({
        json: { data: { actorId: 'fixture-owner', labelingAdmin: false } },
      });
    if (p === '/projects/fixture-project')
      return route.fulfill({
        json: {
          data: {
            projectId: 'fixture-project',
            name: '真实接口响应夹具项目',
            taskType: 'IMAGE_CLASSIFICATION',
            status: 'OPEN',
            revision: 1,
          },
        },
      });
    if (p === '/projects/fixture-project/submissions')
      return denied
        ? route.fulfill({
            status: 403,
            json: {
              error: { code: 'FORBIDDEN', message: '真实接口拒绝夹具' },
              requestId: 'review-real-403',
            },
          })
        : route.fulfill({
            json: {
              data: {
                items: [
                  {
                    submissionId: 'fixture-submission',
                    packageId: 'fixture-package',
                    assignmentId: 'fixture-assignment',
                    submittedBy: 'fixture-human',
                    submittedAt: '2026-01-02T08:00:00Z',
                  },
                ],
                nextCursor: null,
              },
            },
          });
    if (p === '/submissions/fixture-submission')
      return route.fulfill({
        json: {
          data: {
            submissionId: 'fixture-submission',
            projectId: 'fixture-project',
            assignmentId: 'fixture-assignment',
            packageId: 'fixture-package',
            submittedBy: 'fixture-human',
            submittedAt: '2026-01-02T08:00:00Z',
            specVersionId: 'fixture-spec',
            contentHash: 'fixture-hash',
            items: [
              {
                sampleId: 'fixture-sample',
                revision: 1,
                resultFormat: 'image-classification-v1',
                result: { labelId: 'fixture-label' },
              },
            ],
          },
        },
      });
    if (p === '/projects/fixture-project/samples/fixture-sample/file')
      return route.fulfill({
        contentType: 'image/png',
        body: Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j8ioAAAAASUVORK5CYII=',
          'base64',
        ),
      });
    report.unexpectedRequests.push(p);
    return route.abort();
  });
  await real.goto(`${base}/#/labeling/projects/fixture-project/workbench`);
  await real
    .getByRole('link', { name: 'fixture-submission', exact: true })
    .waitFor();
  assert.match(
    await real
      .getByRole('link', { name: 'fixture-submission', exact: true })
      .getAttribute('href'),
    /labeling\/submissions\/fixture-submission/,
  );
  for (const name of ['确认采用', '标记待修正', '修订为新候选'])
    assert.equal(
      await real
        .getByRole('region', { name: '审核详情', exact: true })
        .getByRole('button', { name, exact: true })
        .isDisabled(),
      true,
    );
  await real.getByRole('button', { name: 'AI进度与分档', exact: true }).click();
  assert.equal(
    await real
      .getByRole('button', { name: 'AI范围执行 · 待后端接入', exact: true })
      .isDisabled(),
    true,
  );
  await real.getByRole('link', { name: '成果导出', exact: true }).click();
  assert.equal(
    await real
      .getByRole('button', { name: '成果CSV导出 · 待后端接入', exact: true })
      .isDisabled(),
    true,
  );
  await real.getByRole('link', { name: '结果审核', exact: true }).click();
  await real.screenshot({
    path: `${out}/real-workbench-1586.png`,
    fullPage: true,
  });
  await real.setViewportSize({ width: 390, height: 1000 });
  await real.screenshot({
    path: `${out}/real-workbench-390.png`,
    fullPage: true,
  });
  assert.ok(
    await real.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  );
  denied = true;
  await real.getByRole('button', { name: /刷新结果/ }).click();
  await real.getByText('review-real-403', { exact: false }).waitFor();
  assert.equal(
    await real.evaluate(() => JSON.parse(sessionStorage.getItem('token'))),
    'synthetic-review-fixture',
  );
  await real.close();
  report.checks.push(
    'Real workbench uses existing project/me/submission APIs with actual errors, loaded package counts and readonly links; all missing AI/review/revision/export operations disabled; 403 keeps token and requestId, no mock fallback',
  );
  report.realRequests = realRequests;
  assert.deepEqual(report.unexpectedRequests, []);
  assert.deepEqual(report.pageErrors, []);
  report.passed = true;
  console.log(JSON.stringify(report));
} catch (error) {
  console.log(
    'REVIEW_FAILURE',
    page.url(),
    (await page.locator('body').innerText()).slice(-1800),
  );
  await page.screenshot({ path: `${out}/last-failure.png`, fullPage: true });
  throw error;
} finally {
  await writeFile(
    `${out}/browser-report.json`,
    JSON.stringify(report, null, 2),
  );
  await browser.close();
}
