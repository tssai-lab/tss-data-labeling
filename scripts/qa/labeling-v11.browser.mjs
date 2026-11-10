// Reproducible UI fixtures only, never a live backend or a replacement for integration acceptance.
// npm install --prefix .qa --no-save --package-lock=false playwright
// node scripts/qa/labeling-v11.browser.mjs   (dev:labeling-demo must be running)
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const require = createRequire(import.meta.url);
const { chromium } = require('../../.qa/node_modules/playwright');
const base = process.env.LABELING_UI_BASE || 'http://127.0.0.1:8001';
const output = fileURLToPath(
  new URL('../../docs/labeling-v11-template-evidence/', import.meta.url),
);
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({
  viewport: { width: 1280, height: 900 },
});
const page = await context.newPage();
const report = {
  data: 'Synthetic browser fixtures; no real accounts or service writes',
  widths: [],
  checks: [],
  pageErrors: [],
  unexpectedRequests: [],
};
page.on('pageerror', (e) => report.pageErrors.push(e.message));
const id = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const pid = id(1),
  bid = id(2),
  aid = id(3),
  actor = id(4),
  specId = id(5),
  planId = id(6),
  packageId = id(7);
let isAdmin = true,
  empty = false,
  denied = false,
  submitted = false,
  revisionConflict = false,
  saveDelay = 0;
const labels = [
  { id: 'normal', name: '正常' },
  { id: 'none', name: '无目标' },
];
const long = '超长图片目录与文件名称-'.repeat(14);
const samples = Array.from({ length: 12 }, (_, i) => ({
  sampleId: id(100 + i),
  projectId: pid,
  batchId: bid,
  sourcePath: `batch/${long}${i}.png`,
  mediaType: 'image/png',
  metadata: { width: 3000, height: 500, sizeBytes: 1000 },
}));
const drafts = Object.fromEntries(
  samples.map((s, i) => [
    s.sampleId,
    {
      assignmentId: aid,
      sampleId: s.sampleId,
      revision: i < 6 ? 1 : 0,
      explicitDone: i < 6,
      resultFormat: 'image-classification-v1',
      result: i < 6 ? { labelId: 'none' } : {},
      savedAt: '2026-11-09T01:00:00Z',
    },
  ]),
);
const project = {
  projectId: pid,
  name: '合成验收 · 图片分类',
  description: '用于浏览器测试的独立夹具，非真实项目。'.repeat(8),
  taskType: 'IMAGE_CLASSIFICATION',
  status: 'OPEN',
  revision: 1,
  createdAt: '2026-11-09T01:00:00Z',
  specVersions: [
    {
      specVersionId: specId,
      versionNo: 1,
      labels,
      instructions: '超长规范说明，用于检查自然换行及移动端可读性。'.repeat(60),
    },
  ],
};
const plan = {
  planId,
  status: 'PUBLISHED',
  selectionHash: 'synthetic',
  sampleCount: 12,
  packageSampleCounts: [10, 2],
  totalWorkload: 12,
  packages: [
    { packageId, sequenceNo: 1, sampleCount: 10, requiredCount: 1 },
    { packageId: id(8), sequenceNo: 2, sampleCount: 2, requiredCount: 1 },
  ],
};
const summaries = [0, 1].map((i) => ({
  submissionId: id(20 + i),
  assignmentId: id(30 + i),
  projectId: pid,
  packageId,
  submittedBy: id(40 + i),
  submittedAt: '2026-11-09T01:00:00Z',
}));
await context.addInitScript(
  ({ pid, bid, planId }) => {
    sessionStorage.setItem('token', JSON.stringify('synthetic-ui-fixture'));
    localStorage.setItem(
      `labeling:last-batch:00000000-0000-4000-8000-000000000004:${pid}`,
      bid,
    );
    localStorage.setItem(`labeling:plan:${pid}:${bid}`, planId);
  },
  { pid, bid, planId },
);
function pngChunk(type, data) {
  const name = Buffer.from(type),
    size = Buffer.alloc(4),
    checksum = Buffer.alloc(4);
  size.writeUInt32BE(data.length);
  let crc = 0xffffffff;
  for (const byte of Buffer.concat([name, data])) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  checksum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
  return Buffer.concat([size, name, data, checksum]);
}
const imageHeader = Buffer.alloc(13);
imageHeader.writeUInt32BE(3000, 0);
imageHeader.writeUInt32BE(200, 4);
imageHeader[8] = 8;
imageHeader[9] = 2;
const imageRaw = Buffer.alloc((3000 * 3 + 1) * 200);
for (let y = 0; y < 200; y++)
  for (let x = 0; x < 3000; x++) {
    const i = y * 9001 + 1 + x * 3;
    imageRaw[i] = x < 1500 ? 68 : 38;
    imageRaw[i + 1] = x < 1500 ? 130 : 174;
    imageRaw[i + 2] = 210;
  }
const wideImage = Buffer.concat([
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
  pngChunk('IHDR', imageHeader),
  pngChunk('IDAT', deflateSync(imageRaw)),
  pngChunk('IEND', Buffer.alloc(0)),
]);
await page.route('**/api/**', async (route) => {
  const request = route.request(),
    url = new URL(request.url()),
    path = url.pathname.replace('/api/labeling/v1', '');
  const json = (data, status = 200) =>
    route.fulfill({ status, json: { data, requestId: 'synthetic-request' } });
  const error = (status, code) =>
    route.fulfill({
      status,
      json: {
        error: { code, message: '合成服务错误，用于验收错误展示' },
        requestId: 'synthetic-error-request',
      },
    });
  if (!url.pathname.startsWith('/api/labeling/v1')) {
    if (url.pathname.includes('current-user'))
      return route.fulfill({
        json: {
          code: 200,
          data: {
            id: 1,
            username: '合成测试用户',
            name: '合成测试用户',
            roleId: 3,
          },
        },
      });
    return route.fulfill({ json: { code: 200, data: {} } });
  }
  if (path === '/me')
    return denied
      ? error(403, 'FORBIDDEN')
      : json({
          actorId: actor,
          labelingAdmin: isAdmin,
          canCreateProject: true,
          canAnnotate: true,
        });
  if (path === '/admin/users' && request.method() === 'GET')
    return isAdmin
      ? json([
          {
            actorId: actor,
            identityUserId: 'fixture-self',
            displayName: '合成管理员本人',
            status: 'ENABLED',
            labelingAdmin: true,
          },
          {
            actorId: id(55),
            identityUserId: 'fixture-other',
            displayName: '合成标注员',
            status: 'ENABLED',
            labelingAdmin: false,
          },
        ])
      : error(403, 'FORBIDDEN');
  if (path.startsWith('/admin/users/') && request.method() === 'PATCH')
    return json(null);
  if (path === '/projects')
    return json({ items: empty ? [] : [project], nextCursor: null });
  if (path === `/projects/${pid}`) return json(project);
  if (path.endsWith('/spec-versions')) {
    const body = request.postDataJSON();
    const created = {
      ...body,
      specVersionId: id(60 + project.specVersions.length),
      versionNo: project.specVersions.length + 1,
    };
    project.specVersions.unshift(created);
    return json(created, 201);
  }
  if (path === `/imports/${bid}`)
    return json({
      batchId: bid,
      projectId: pid,
      status: 'READY',
      validSampleCount: 12,
      errorCount: 0,
      errors: [],
      sizeBytes: 1000,
      receivedBytes: 1000,
      originalComplete: true,
    });
  if (path === `/imports/${bid}/samples`)
    return json({ items: samples, nextCursor: null });
  if (path === `/package-plans/${planId}`) return json(plan);
  if (path === `/projects/${pid}/package-plans`) {
    assert.equal(
      request.postDataJSON().specVersionId,
      project.specVersions[0].specVersionId,
    );
    return json({ ...plan, status: 'DRAFT', packages: [] });
  }
  if (path === `/projects/${pid}/submissions`)
    return json({
      items: url.searchParams.has('cursor') ? [summaries[1]] : [summaries[0]],
      nextCursor: url.searchParams.has('cursor') ? null : 'next-fixture-page',
    });
  if (path.startsWith('/submissions/'))
    return json({
      ...summaries[0],
      specVersionId: specId,
      contentHash: 'synthetic',
      items: samples.slice(0, 10).map((s) => ({
        sampleId: s.sampleId,
        revision: 1,
        resultFormat: 'image-classification-v1',
        result: { labelId: 'none' },
      })),
    });
  if (path === '/task-pool')
    return json({
      items: empty
        ? []
        : [
            {
              ...plan.packages[0],
              projectId: pid,
              projectName: project.name,
              remainingSlots: 1,
              taskType: project.taskType,
            },
          ],
      nextCursor: null,
    });
  if (path === '/assignments')
    return json({
      items: [
        {
          assignmentId: aid,
          packageId,
          slotNo: 1,
          status: submitted ? 'SUBMITTED' : 'IN_PROGRESS',
          revision: 2,
        },
        {
          assignmentId: id(99),
          packageId: id(8),
          slotNo: 1,
          status: 'IN_PROGRESS',
          revision: 1,
        },
      ],
      nextCursor: null,
    });
  if (path === `/assignments/${aid}/items`)
    return json({
      assignmentId: aid,
      packageId,
      status: submitted ? 'SUBMITTED' : 'IN_PROGRESS',
      readOnly: submitted,
      specification: project.specVersions.find(
        (s) => s.specVersionId === specId,
      ),
      items: (url.searchParams.has('cursor')
        ? samples.slice(5, 10)
        : samples.slice(0, 5)
      ).map((s) => ({
        ...s,
        draftRevision: drafts[s.sampleId].revision || null,
        explicitDone: drafts[s.sampleId].revision
          ? drafts[s.sampleId].explicitDone
          : null,
        resultFormat: 'image-classification-v1',
        result: drafts[s.sampleId].result,
        ...(s.sampleId === samples[6].sampleId
          ? {
              referenceResultId: 'fixture-fixed-ai-result',
              referenceResult: { labelId: 'normal' },
              sourceKind: 'AI',
              modelVersion: 'fixture-model-v1',
              confidence: 0.72,
            }
          : {}),
      })),
      nextCursor: url.searchParams.has('cursor') ? null : 'second-half',
    });
  if (path.includes('/drafts/')) {
    const sampleId = path.split('/').at(-1);
    if (request.method() === 'PUT') {
      const body = request.postDataJSON();
      if (saveDelay)
        await new Promise((resolve) => setTimeout(resolve, saveDelay));
      if (revisionConflict || body.baseRevision !== drafts[sampleId].revision)
        return error(409, 'REVISION_CONFLICT');
      drafts[sampleId] = {
        ...drafts[sampleId],
        ...body,
        revision: drafts[sampleId].revision + 1,
      };
    }
    return json(drafts[sampleId]);
  }
  if (/editor-sessions|editor-sync|editor-ui|editor-assets/.test(path)) {
    report.unexpectedRequests.push(`LEGACY ${path}`);
    return error(410, 'LEGACY_EDITOR_DISABLED');
  }
  if (path.endsWith('/submissions') && request.method() === 'POST') {
    assert.equal(request.postDataJSON().items.length, 10);
    assert.deepEqual(
      new Set(request.postDataJSON().items.map((i) => i.sampleId)),
      new Set(samples.slice(0, 10).map((s) => s.sampleId)),
    );
    submitted = true;
    return json(
      {
        submissionId: summaries[0].submissionId,
        assignmentId: aid,
        status: 'SUBMITTED',
        sampleCount: 10,
      },
      201,
    );
  }
  if (path.endsWith('/file'))
    return route.fulfill({
      contentType: 'image/png',
      body: wideImage,
    });
  report.unexpectedRequests.push(`${request.method()} ${path}`);
  return error(404, 'FIXTURE_NOT_IMPLEMENTED');
});
async function visit(path, text) {
  await page.goto(`${base}/#${path}`);
  try {
    await page.getByText(text, { exact: false }).first().waitFor();
  } catch (e) {
    console.log(
      'FAILED_ROUTE',
      page.url(),
      (await page.locator('body').innerText()).slice(0, 2000),
    );
    await page.screenshot({ path: `${output}/last-failure.png` });
    throw e;
  }
  await page
    .locator('.ant-spin-spinning')
    .first()
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
}
async function capture(name, width) {
  await page.screenshot({
    path: `${output}/${name}-${width}.png`,
    fullPage: true,
  });
  const layout = await page.evaluate(() => ({
    viewport: innerWidth,
    document: document.documentElement.scrollWidth,
    tables: [...document.querySelectorAll('.ant-table-content')].map((e) => ({
      client: e.clientWidth,
      scroll: e.scrollWidth,
    })),
  }));
  assert.ok(
    layout.document <= width + 1,
    `${name} at ${width} has page overflow: ${layout.document}`,
  );
  report.widths.push({ page: name, ...layout });
}

try {
  for (const width of [1280, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await visit(`/labeling/projects/${pid}`, '分类规范与历史版本');
    await page
      .getByRole('button', { name: '加载更多结果', exact: true })
      .click();
    await page.getByText('已读取 2 份提交', { exact: false }).waitFor();
    await capture('project', width);
    await visit('/labeling/tasks', '任务广场');
    await capture('task-pool', width);
    await visit(`/labeling/assignments/${aid}`, '图片分类标注');
    await page
      .getByRole('radio', { name: '无目标', exact: true })
      .waitFor({ state: 'attached' });
    assert.equal(await page.locator('iframe').count(), 0);
    await page
      .getByRole('img', { name: '当前领取图片', exact: true })
      .evaluate((img) => img.decode());
    assert.equal(
      await page
        .getByRole('img', { name: '当前领取图片', exact: true })
        .evaluate((img) => img.naturalWidth),
      3000,
    );
    assert.equal(
      await page
        .getByRole('button', { name: '提交整个任务包', exact: true })
        .isDisabled(),
      true,
    );
    await capture('inline-assignment', width);
    await page.getByRole('link', { name: '我的任务', exact: true }).click();
    await page.getByRole('heading', { name: '我的标注任务' }).waitFor();
    await visit(
      `/labeling/submissions/${summaries[0].submissionId}`,
      '本包人工结果已交回并冻结',
    );
    await capture('submission', width);
    await visit('/labeling/admin/users', '合成标注员');
    await capture('admin-users', width);
    await page
      .getByRole('button', { name: '修改权限', exact: true })
      .nth(1)
      .click();
    await page.getByRole('dialog').waitFor();
    await page.waitForTimeout(350);
    await capture('admin-modal', width);
    await page
      .getByRole('dialog')
      .getByRole('button', { name: /取.*消/ })
      .click();
    await visit(`/labeling/projects/${pid}/workbench`, '结果工作台');
    await capture('real-workbench-pending', width);
    await visit('/labeling/demo', '需求方工作台 · 合成流程演示');
    await page.getByRole('button', { name: '查看种子 PARTIAL 批次' }).click();
    await page.getByRole('spinbutton', { name: '低边界L' }).waitFor();
    await capture('owner-workflow', width);
  }
  report.checks.push(
    '1280 / 768 / 390 core pages and inline editor; no iframe; 6/10 incomplete package cannot submit; viewport overflow asserted',
  );
  await page.setViewportSize({ width: 1280, height: 900 });
  await visit(`/labeling/assignments/${aid}`, '图片分类标注');
  await page
    .locator('.ant-radio-button-wrapper')
    .filter({ has: page.getByRole('radio', { name: '正常', exact: true }) })
    .click();
  page.once('dialog', (dialog) => dialog.dismiss());
  await page.getByRole('link', { name: '我的任务', exact: true }).click();
  await page.waitForTimeout(150);
  assert.ok(page.url().includes(aid));
  revisionConflict = true;
  await page.getByRole('button', { name: '保存当前草稿', exact: true }).click();
  await page.getByText('REVISION_CONFLICT', { exact: false }).first().waitFor();
  assert.equal(
    await page.getByRole('radio', { name: '正常', exact: true }).isChecked(),
    true,
  );
  await capture('draft-conflict-preserves-selection', 1280);
  revisionConflict = false;
  await page
    .getByRole('button', { name: '重新读取草稿', exact: true })
    .first()
    .click();
  await page
    .getByRole('radio', { name: '无目标', exact: true })
    .waitFor({ state: 'attached' });
  assert.equal(
    await page.getByRole('radio', { name: '无目标', exact: true }).isChecked(),
    true,
  );
  await page
    .getByRole('button', { name: samples[6].sourcePath, exact: true })
    .click();
  await page.getByText('AI参考结果 · 待人工确认', { exact: true }).waitFor();
  assert.equal(drafts[samples[6].sampleId].explicitDone, false);
  saveDelay = 400;
  await page
    .getByRole('button', { name: '确认参考并保存草稿', exact: true })
    .click();
  assert.equal(
    await page
      .getByRole('button', { name: '下一张', exact: true })
      .isDisabled(),
    true,
  );
  await page
    .getByRole('button', { name: '保存当前草稿', exact: true })
    .waitFor();
  saveDelay = 0;
  assert.equal(drafts[samples[6].sampleId].explicitDone, true);
  await page.getByRole('button', { name: '清空选择', exact: true }).click();
  await page.getByRole('button', { name: '保存当前草稿', exact: true }).click();
  await page.getByText('已保存未完成草稿', { exact: false }).first().waitFor();
  await page
    .getByRole('button', { name: '重新读取草稿', exact: true })
    .first()
    .click();
  await page
    .getByRole('radio', { name: '正常', exact: true })
    .waitFor({ state: 'attached' });
  assert.equal(
    await page.getByRole('radio', { name: '正常', exact: true }).isChecked(),
    false,
  );
  assert.equal(
    await page.getByText('AI参考结果 · 待人工确认', { exact: true }).count(),
    0,
  );
  report.checks.push(
    'Inline dirty navigation guard; 409 preserves choice; reference never auto-completes; save locks navigation; cleared saved draft never restores reference',
  );
  // Complete only this assignment as fixture data; the second claimed package stays unfinished.
  for (const s of samples.slice(0, 10))
    drafts[s.sampleId] = {
      ...drafts[s.sampleId],
      revision: Math.max(1, drafts[s.sampleId].revision),
      explicitDone: true,
      result: { labelId: 'none' },
    };
  await page.getByRole('button', { name: '重新加载整包', exact: true }).click();
  await page
    .getByRole('button', { name: '提交整个任务包', exact: true })
    .waitFor();
  await page
    .getByRole('button', { name: '提交整个任务包', exact: true })
    .click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '确认提交', exact: true })
    .click();
  await page
    .getByText('已交回 · 待需求方审核 · 只读', { exact: true })
    .waitFor();
  assert.equal(
    await page
      .getByRole('button', { name: '保存当前草稿', exact: true })
      .count(),
    0,
  );
  await page.evaluate(
    ({ actor, aid, items }) => {
      const key = `labeling:pending-submission:${actor}:${aid}`;
      sessionStorage.removeItem(`${key}:receipt`);
      sessionStorage.setItem(
        key,
        JSON.stringify({
          actorId: actor,
          assignmentId: aid,
          key: 'fixture-lost-receipt',
          items,
        }),
      );
    },
    {
      actor,
      aid,
      items: samples.slice(0, 10).map((s) => ({
        sampleId: s.sampleId,
        revision: drafts[s.sampleId].revision,
      })),
    },
  );
  await page.getByRole('link', { name: '我的任务', exact: true }).click();
  await page.getByRole('heading', { name: '我的标注任务' }).waitFor();
  await visit(`/labeling/assignments/${aid}`, '上一次提交结果待确认');
  await page.getByRole('button', { name: '确认本次提交', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '确认本次提交', exact: true })
    .click();
  await page
    .getByRole('link', { name: '查看冻结提交结果', exact: true })
    .waitFor();
  report.checks.push(
    'Exactly 10 frozen members across all item pages submitted; another incomplete assignment does not block; handback stays pending review; lost receipt replay succeeds',
  );
  await page.getByRole('link', { name: '我的任务', exact: true }).click();
  await page.getByRole('heading', { name: '我的标注任务' }).waitFor();
  isAdmin = false;
  await visit('/labeling/admin/users', '没有标注管理员权限');
  assert.equal(
    await page.getByRole('button', { name: '修改权限', exact: true }).count(),
    0,
  );
  isAdmin = true;
  denied = true;
  await page.setViewportSize({ width: 390, height: 900 });
  await visit('/labeling', 'FORBIDDEN');
  await capture('permission-error', 390);
  assert.equal(
    await page.evaluate(() => JSON.parse(sessionStorage.getItem('token'))),
    'synthetic-ui-fixture',
  );
  denied = false;
  empty = true;
  await visit('/labeling/tasks', '暂无可领取任务');
  await capture('empty-pool', 390);
  empty = false;
  await page.setViewportSize({ width: 1280, height: 900 });
  await visit('/labeling/demo', '需求方工作台 · 合成流程演示');
  await page.getByRole('button', { name: '查看种子 PARTIAL 批次' }).click();
  assert.equal(
    await page
      .getByRole('button', { name: '保存阈值策略（不审核）' })
      .isDisabled(),
    true,
  );
  await page.getByRole('spinbutton', { name: '低边界L' }).fill('0.6');
  await page.getByRole('spinbutton', { name: '高边界H' }).fill('0.9');
  await page.getByRole('button', { name: '保存阈值策略（不审核）' }).click();
  await page
    .getByText('阈值策略已保存，没有产生任何审核决定', { exact: true })
    .waitFor();
  await page.getByRole('button', { name: /MEDIUM · 已载成功项 2/ }).waitFor();
  await page.getByRole('spinbutton', { name: '高边界H' }).fill('0.61');
  assert.equal(
    await page.getByRole('button', { name: /MEDIUM · 已载成功项 2/ }).count(),
    1,
  );
  await page.getByRole('button', { name: /MEDIUM · 已载成功项 2/ }).click();
  await page
    .getByRole('textbox', { name: '全部筛选结果排除样本' })
    .fill('demo-sample-4');
  await page.getByRole('button', { name: '固定全部筛选结果并预览' }).click();
  await page.getByRole('dialog', { name: '固定选择预览 · 1 项' }).waitFor();
  await page.getByRole('button', { name: '完成范围预览' }).click();
  await page.getByRole('button', { name: '明确确认所选结果' }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '确认操作' })
    .click();
  await page
    .getByText('已明确审核 1 项；尚未形成最终成果。', { exact: true })
    .waitFor();
  const resultTable = page.locator('.ant-table-wrapper').first();
  await resultTable
    .locator('tr[data-row-key="demo-sample-3"]')
    .getByRole('checkbox')
    .check();
  await page
    .getByRole('combobox', { name: '固定选择用途' })
    .locator('xpath=../..')
    .click();
  await page
    .locator('.ant-select-item-option-content')
    .getByText('FINALIZE', { exact: true })
    .click();
  await page.getByRole('button', { name: '固定勾选样本并预览' }).click();
  await page.getByRole('button', { name: '完成范围预览' }).click();
  await page.getByRole('button', { name: '确认形成范围成果' }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /确.*定|确.*认|OK/ })
    .last()
    .click();
  await page
    .getByText('已固定范围成果，项目其他未完成项仍保留', { exact: true })
    .waitFor();
  const finalTable = page.locator('.ant-table-wrapper').filter({
    has: page.getByRole('columnheader', { name: '成果快照', exact: true }),
  });
  await finalTable
    .locator('tbody tr.ant-table-row')
    .first()
    .getByRole('checkbox')
    .check();
  await page.getByRole('button', { name: '创建成果导出', exact: true }).click();
  await page.getByRole('button', { name: '查询导出状态' }).click();
  await page.getByRole('button', { name: '查询导出状态' }).click();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载分类 ZIP' }).click();
  await (await downloadPromise).saveAs(
    `${output}/confirmed-classification-export.zip`,
  );
  await capture('confirmed-final-export', 1280);
  report.checks.push(
    'No default thresholds; editing unsaved thresholds does not change saved policy; fixed medium-band selection excludes one item; explicit review then final snapshot then CSV ZIP',
  );
  await page.getByRole('button', { name: '重置合成种子' }).click();
  const allRowsTable = page.locator('.ant-table-wrapper').first();
  await allRowsTable
    .locator('tr[data-row-key="demo-sample-1"]')
    .getByRole('button', { name: '来源历史' })
    .click();
  const historyTable = page.locator('.ant-table-wrapper').filter({
    has: page.getByRole('columnheader', { name: '来源身份', exact: true }),
  });
  await historyTable
    .locator('tbody tr.ant-table-row')
    .getByRole('checkbox')
    .nth(0)
    .check();
  await historyTable
    .locator('tbody tr.ant-table-row')
    .getByRole('checkbox')
    .nth(1)
    .check();
  await historyTable
    .locator('tbody tr.ant-table-row')
    .getByRole('checkbox')
    .nth(2)
    .check();
  await page.getByRole('button', { name: '生成集成建议（不审核）' }).click();
  await page.getByText('CONSENSUS', { exact: true }).waitFor();
  await capture('majority-suggestion', 1280);
  report.checks.push(
    'Three independent fixed result versions produce strict-majority suggestion without automatic acceptance',
  );
  await page.getByRole('button', { name: '重置合成种子' }).click();
  await page
    .getByRole('combobox', { name: '固定选择用途' })
    .locator('xpath=../..')
    .click();
  await page
    .locator('.ant-select-item-option-content')
    .getByText('PUBLISH', { exact: true })
    .click();
  await page.getByRole('button', { name: '固定全部筛选结果并预览' }).click();
  await page.getByRole('button', { name: '完成范围预览' }).click();
  await page.getByRole('button', { name: '预览人工打包' }).click();
  await page.getByText('各包 10 / 2', { exact: false }).waitFor();
  await page.getByRole('button', { name: '确认发布人工包' }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /确.*定/ })
    .click();
  await page
    .getByRole('button', { name: '模拟本包全部完成并交回' })
    .first()
    .click();
  const packagesTable = page.locator('.ant-table-wrapper').filter({
    has: page.getByRole('columnheader', { name: '新人工包', exact: true }),
  });
  await packagesTable.getByText('SUBMITTED', { exact: true }).waitFor();
  assert.equal(
    await packagesTable.getByText('OPEN', { exact: true }).count(),
    1,
  );
  await capture('human-republish-10-plus-2', 1280);
  await page.getByRole('button', { name: '重置合成种子' }).click();
  await page
    .getByRole('combobox', { name: '固定选择用途' })
    .locator('xpath=../..')
    .click();
  await page
    .locator('.ant-select-item-option-content')
    .getByText('PUBLISH', { exact: true })
    .click();
  await page
    .getByRole('combobox', { name: '答案筛选' })
    .locator('xpath=../..')
    .click();
  await page
    .locator('.ant-select-item-option-content')
    .getByText('没有答案', { exact: true })
    .click();
  await page.getByRole('button', { name: '固定全部筛选结果并预览' }).click();
  await page.getByRole('button', { name: '完成范围预览' }).click();
  await page.getByRole('button', { name: '创建合成 AI 执行' }).click();
  await page.getByText('QUEUED · revision 1', { exact: false }).waitFor();
  await page.getByRole('button', { name: '查询执行进度' }).click();
  await page.getByText('RUNNING · revision', { exact: false }).waitFor();
  await page.getByRole('button', { name: '查询执行进度' }).click();
  await page.getByText('PARTIAL · revision', { exact: false }).waitFor();
  await page
    .locator('#workflow-step-3')
    .getByText('SYNTHETIC_MODEL_FAILURE', { exact: true })
    .waitFor();
  await capture('ai-partial-fixed-output', 1280);
  await page.getByRole('button', { name: '仅重试该批次失败/取消项' }).click();
  await page.getByText('QUEUED · revision 1', { exact: false }).waitFor();
  await page
    .getByRole('textbox', { name: '操作原因', exact: true })
    .fill('取消合成重试');
  await page.getByRole('button', { name: '取消执行', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /确.*定/ })
    .click();
  await page.getByText('CANCELLED · revision', { exact: false }).waitFor();
  report.checks.push(
    'UI publishes 10+2 independent packages; one handed back while other remains open; AI executes directly on fixed samples, partial outputs, failure-only retry, cancellation',
  );
  const anonymous = await browser.newPage();
  const anonymousRequests = [];
  await anonymous.route('**/api/**', (route) => {
    anonymousRequests.push(new URL(route.request().url()).pathname);
    return route.abort();
  });
  await anonymous.goto(`${base}/#/labeling/demo`);
  await anonymous
    .getByRole('button', { name: '查看种子 PARTIAL 批次' })
    .waitFor();
  await anonymous.close();
  assert.deepEqual(anonymousRequests, []);
  report.checks.push(
    'Synthetic workflow requires no login/backend; no legacy editor session/sync/assets requests; no external model calls',
  );
  assert.deepEqual(report.unexpectedRequests, []);
  assert.deepEqual(report.pageErrors, []);
  console.log(
    JSON.stringify({ checks: report.checks, captures: report.widths.length }),
  );
} finally {
  await writeFile(
    `${output}/browser-report.json`,
    JSON.stringify(report, null, 2),
  );
  await browser.close();
}
