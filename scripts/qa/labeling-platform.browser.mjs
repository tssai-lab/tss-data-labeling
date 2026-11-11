// Isolated HTTP fixtures. No real login, samples, or writes to the platform.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('../../.qa/node_modules/playwright');
const base = process.env.LABELING_UI_BASE || 'http://127.0.0.1:8001';
const out = fileURLToPath(
  new URL('../../docs/labeling-platform-style-evidence/', import.meta.url),
);
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1586, height: 1100 } });
const report = {
  syntheticHttpFixtures: true,
  checks: [],
  pageErrors: [],
  unexpectedRequests: [],
  requests: [],
  screenshots: [],
};
page.on('pageerror', (e) => report.pageErrors.push(e.message));
await page.addInitScript(() =>
  sessionStorage.setItem('token', JSON.stringify('isolated-platform-style')),
);
const project = {
  projectId: 'fixture-project',
  name: '工业零件外观分类 · HTTP夹具',
  description: '页面验收专用响应夹具，不是真实业务数据',
  taskType: 'IMAGE_CLASSIFICATION',
  status: 'OPEN',
  revision: 1,
  createdAt: '2026-11-11T08:00:00Z',
  specVersions: [
    {
      specVersionId: 'spec-new',
      versionNo: 2,
      labels: [{ id: 'normal', name: '新版标签不应覆盖历史' }],
      instructions: '新规范',
    },
    {
      specVersionId: 'spec-frozen',
      versionNo: 1,
      labels: [
        { id: 'normal', name: '正常' },
        { id: 'defect', name: '缺陷' },
      ],
      instructions: '冻结规范',
    },
  ],
};
const summaries = ['a', 'b'].map((key, i) => ({
  submissionId: `submission-${key}`,
  assignmentId: `assignment-${key}`,
  packageId: `package-${key}`,
  submittedBy: `human-${key}`,
  submittedAt: `2026-11-11T0${8 - i}:00:00Z`,
}));
const details = {
  'submission-a': {
    ...summaries[0],
    projectId: project.projectId,
    specVersionId: 'spec-frozen',
    contentHash: 'fixture-a',
    items: Array.from({ length: 22 }, (_, i) => ({
      sampleId: `sample-${String(i + 1).padStart(2, '0')}`,
      revision: i === 0 ? 0 : 3,
      resultFormat: 'image-classification-v1',
      result: i === 21 ? {} : { labelId: i === 1 ? 'defect' : 'normal' },
    })),
  },
  'submission-b': {
    ...summaries[1],
    projectId: project.projectId,
    specVersionId: 'spec-unavailable',
    contentHash: 'fixture-b',
    items: [
      {
        sampleId: 'sample-other',
        revision: 4,
        resultFormat: 'image-classification-v1',
        result: { labelId: 'raw-frozen-label' },
      },
    ],
  },
};
let png,
  denied = false,
  detailDenied = false,
  imageDenied = false,
  empty = false,
  paused = false,
  slow = false,
  releaseSlow,
  startedSlow;
await page.route('**/label-api/**', (route) => route.fulfill({ json: [] }));
await page.route('**/api/**', async (route) => {
  const request = route.request(),
    url = new URL(request.url()),
    path = url.pathname.replace('/api/labeling/v1', '');
  if (!url.pathname.startsWith('/api/labeling/v1'))
    return route.fulfill({
      json: {
        code: 200,
        data: url.pathname.includes('current-user')
          ? {
              id: 1,
              username: 'fixture-owner',
              name: 'HTTP夹具用户',
              roleId: 3,
            }
          : {},
      },
    });
  report.requests.push(`${request.method()} ${path}`);
  assert.equal(
    request.method(),
    'GET',
    'Real workbench must not issue unsupported writes',
  );
  const ok = (data) => route.fulfill({ json: { data } });
  const failure = (code, id) =>
    route.fulfill({
      status: 403,
      json: { error: { code, message: '隔离夹具拒绝读取' }, requestId: id },
    });
  if (path === '/me')
    return ok({
      actorId: 'fixture-owner',
      canCreateProject: true,
      canAnnotate: true,
      labelingAdmin: true,
    });
  if (path === '/projects') return ok({ items: [project], nextCursor: null });
  if (path === `/projects/${project.projectId}`)
    return ok({ ...project, status: paused ? 'PAUSED' : 'OPEN' });
  if (path === `/projects/${project.projectId}/submissions`) {
    if (denied) return failure('FORBIDDEN', 'summary-403');
    return ok({
      items: empty
        ? []
        : url.searchParams.has('cursor')
          ? [summaries[1]]
          : [summaries[0]],
      nextCursor:
        empty || url.searchParams.has('cursor') ? null : 'second-page',
    });
  }
  if (path.startsWith('/submissions/')) {
    if (detailDenied) return failure('FORBIDDEN', 'detail-403');
    const id = path.split('/').at(-1);
    if (slow && id === 'submission-b') {
      startedSlow?.();
      await new Promise((resolve) => {
        releaseSlow = resolve;
      });
    }
    return ok(details[id]);
  }
  if (path.includes('/samples/') && path.endsWith('/file')) {
    if (imageDenied) return failure('FORBIDDEN', 'image-403');
    return route.fulfill({ contentType: 'image/png', body: png });
  }
  if (path === '/task-pool' || path === '/assignments')
    return ok({ items: [], nextCursor: null });
  if (path === '/admin/users')
    return ok([
      {
        actorId: 'fixture-owner',
        displayName: 'HTTP夹具用户',
        identityUserId: '1',
        status: 'ENABLED',
        labelingAdmin: true,
      },
    ]);
  report.unexpectedRequests.push(path);
  return route.abort();
});
const list = () => page.getByRole('region', { name: '样本列表', exact: true });
const detail = () =>
  page.getByRole('region', { name: '审核详情', exact: true });
async function go(path) {
  await page.goto(`${base}/#${path}`);
}
async function tab(name) {
  const view = {
    范围发布: 'publish',
    AI进度与分档: 'ai',
    来源与集成: 'sources',
    成果导出: 'export',
    结果与审核: 'review',
  }[name];
  await go(
    `/labeling/projects/${project.projectId}/workbench${view === 'review' ? '' : `?view=${view}`}`,
  );
}
async function select(name, label) {
  const combo = page.getByRole('combobox', { name, exact: true });
  await combo.locator('xpath=../..').click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .getByText(label, { exact: false })
    .click();
}
async function stats(label, value) {
  await page.waitForFunction(
    ({ label, value }) =>
      [
        ...document.querySelectorAll(
          '[aria-label="当前人工提交筛选统计"] > div',
        ),
      ].some(
        (el) =>
          el.querySelector('[data-stat-label]')?.textContent === label &&
          el.querySelector('strong')?.textContent === String(value),
      ),
    { label, value },
  );
}
async function focused(id) {
  await page.waitForFunction(
    (id) =>
      document
        .querySelector('[data-sample-id]')
        ?.getAttribute('data-sample-id') === id,
    id,
  );
}
async function capture(name, width) {
  await page.setViewportSize({ width, height: 941 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.mouse.move(width - 2, 2);
  assert.ok(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    `${name}: horizontal overflow`,
  );
  await page.screenshot({
    path: `${out}/${name}-${width}.png`,
    fullPage: true,
    animations: 'disabled',
  });
  report.screenshots.push({ name, width });
}
try {
  // Convert our repo-native synthetic SVG to a PNG solely for the file API fixture.
  await go('/platforms');
  png = Buffer.from(
    await page.evaluate(async () => {
      const img = new Image();
      img.src = '/labeling-demo/sample-02.svg';
      await img.decode();
      const canvas = document.createElement('canvas');
      canvas.width = 1024;
      canvas.height = 800;
      canvas.getContext('2d').drawImage(img, 0, 0, 1024, 800);
      return canvas.toDataURL('image/png').split(',')[1];
    }),
    'base64',
  );
  await go('/platforms');
  const legacy = page.getByRole('link', { name: /数据标注平台 Demo/ });
  assert.equal(await legacy.getAttribute('href'), '#/label-projects');
  assert.equal(
    await page
      .getByRole('link', { name: '数据标注平台', exact: true })
      .getAttribute('href'),
    '#/labeling',
  );
  await capture('platforms', 1586);
  await capture('platforms', 390);
  await legacy.click();
  await page.getByRole('heading', { name: '项目管理', exact: true }).waitFor();
  report.checks.push(
    'Platform selector retains separate training, real labeling and legacy Demo cards; legacy route opens unchanged project management',
  );
  await go('/labeling');
  await page
    .getByRole('heading', { name: '图片分类工作空间', exact: true })
    .waitFor();
  await page.getByRole('link', { name: project.name, exact: true }).waitFor();
  await capture('projects', 1586);
  await capture('projects', 390);
  await page.getByRole('link', { name: project.name, exact: true }).click();
  await stats('样本', 22);
  await stats('人工候选', 21);
  await stats('未作答', 1);
  await stats('已确认', '—');
  await focused('sample-01');
  assert.equal(await list().locator('tr[data-row-key]').count(), 20);
  await detail().getByText('人工草稿 revision 0', { exact: true }).waitFor();
  await detail().getByText('正常', { exact: true }).first().waitFor();
  assert.equal(
    await page.getByText('新版标签不应覆盖历史', { exact: true }).count(),
    0,
  );
  await page.getByRole('button', { name: '下一张', exact: true }).click();
  await focused('sample-02');
  await detail().getByText('缺陷', { exact: true }).first().waitFor();
  await page
    .getByRole('region', { name: '原图预览', exact: true })
    .getByRole('img', { name: /授权原图/ })
    .waitFor();
  await list()
    .locator('tr[data-row-key="sample-01"]')
    .getByRole('checkbox')
    .check();
  await focused('sample-02');
  await capture('workbench', 1586);
  await capture('workbench', 390);
  const panelOrder = await page
    .getByRole('region', { name: '真实结果审核工作台', exact: true })
    .locator(':scope > *')
    .evaluateAll((els) => els.map((el) => el.getBoundingClientRect().top));
  assert.ok(panelOrder[0] < panelOrder[1]);
  await page.getByRole('button', { name: '关闭样本详情', exact: true }).click();
  assert.equal(
    await page
      .getByRole('complementary', { name: '样本详情', exact: true })
      .count(),
    0,
  );
  await list()
    .getByRole('button', { name: '查看样本 sample-02', exact: true })
    .click();
  await focused('sample-02');
  assert.equal(
    await page
      .getByRole('button', { name: '设置阈值', exact: true })
      .isDisabled(),
    true,
  );
  await page.getByRole('button', { name: '加载更多样本', exact: true }).click();
  assert.equal(await list().locator('tr[data-row-key]').count(), 22);
  await page.getByRole('button', { name: /^未作答 \(/ }).click();
  await stats('样本', 1);
  await focused('sample-22');
  await page.getByRole('button', { name: '清除筛选', exact: true }).click();
  await stats('样本', 22);
  await select('结果来源筛选', 'AI（待接入）');
  await stats('样本', 0);
  assert.equal(
    await page
      .getByRole('button', { name: '下一张', exact: true })
      .isDisabled(),
    true,
  );
  await page.getByRole('button', { name: '清除筛选', exact: true }).click();
  await stats('样本', 22);
  for (const name of ['确认采用', '标记待修正', '修订为新候选'])
    assert.equal(
      await detail().getByRole('button', { name, exact: true }).isDisabled(),
      true,
    );
  assert.equal(
    await list()
      .getByRole('combobox', { name: '审核状态筛选', exact: true })
      .isDisabled(),
    true,
  );
  report.checks.push(
    'Real projects open the sidebar/table/inspector review layout; inspector closes and reopens; current-submission totals vs 20 loaded rows, thumbnails, more/next/prev/checkbox separation, frozen labels, revision zero, unknown accepted count and filters are accurate; unsupported thresholds remain disabled',
  );
  await page.getByRole('button', { name: '加载更多提交', exact: true }).click();
  await detail()
    .getByRole('button', { name: '查看来源历史', exact: true })
    .click();
  await page
    .getByRole('dialog')
    .locator('tr[data-row-key="submission-b"]')
    .getByRole('button', { name: '查看该提交', exact: true })
    .click();
  await stats('样本', 1);
  await focused('sample-other');
  await detail()
    .getByText('raw-frozen-label', { exact: true })
    .first()
    .waitFor();
  await select('选择已交回的人工提交', 'human-a');
  await stats('样本', 22);
  slow = true;
  const started = new Promise((resolve) => {
    startedSlow = resolve;
  });
  await select('选择已交回的人工提交', 'human-b');
  await started;
  await select('选择已交回的人工提交', 'human-a');
  await stats('样本', 22);
  releaseSlow();
  slow = false;
  await page.evaluate(
    () =>
      new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
  );
  await stats('样本', 22);
  await focused('sample-01');
  imageDenied = true;
  await page.getByRole('button', { name: '下一张', exact: true }).click();
  await focused('sample-02');
  await page.getByText('image-403', { exact: false }).waitFor();
  imageDenied = false;
  await page.getByRole('button', { name: '重试原图', exact: true }).click();
  await page
    .getByRole('region', { name: '原图预览', exact: true })
    .getByRole('img', { name: /授权原图/ })
    .waitFor();
  detailDenied = true;
  await select('选择已交回的人工提交', 'human-b');
  await page.getByText('detail-403', { exact: false }).waitFor();
  await focused('');
  detailDenied = false;
  await page.getByRole('button', { name: '重试提交详情', exact: true }).click();
  await focused('sample-other');
  await tab('范围发布');
  assert.equal(
    await page
      .getByRole('link', { name: '管理规范、导入与发布', exact: true })
      .getAttribute('href'),
    `#/labeling/projects/${project.projectId}?panel=publish`,
  );
  await tab('AI进度与分档');
  assert.equal(
    await page
      .getByRole('button', { name: 'AI范围执行 · 待后端接入', exact: true })
      .isDisabled(),
    true,
  );
  await tab('来源与集成');
  await page.getByRole('link', { name: 'submission-a', exact: true }).waitFor();
  await tab('成果导出');
  assert.equal(
    await page
      .getByRole('button', { name: '成果CSV导出 · 待后端接入', exact: true })
      .isDisabled(),
    true,
  );
  await tab('结果与审核');
  denied = true;
  await page.getByRole('button', { name: '刷新结果', exact: true }).click();
  await page.getByText('summary-403', { exact: false }).waitFor();
  assert.equal(
    await page.evaluate(() => JSON.parse(sessionStorage.getItem('token'))),
    'isolated-platform-style',
  );
  denied = false;
  empty = true;
  await page.getByRole('button', { name: '刷新结果', exact: true }).click();
  await list().getByText('尚无已交回人工提交', { exact: true }).waitFor();
  assert.equal(await list().locator('tr[data-row-key]').count(), 0);
  report.checks.push(
    'Submission pagination/history changes preview only; missing frozen spec retains original label ID; late detail response cannot overwrite newer selection; summary/detail/image 403 shows requestId with retry, token retained; empty projects invent no samples or review counts; unsupported operations send no writes',
  );
  empty = false;
  paused = true;
  const before = report.requests.filter((p) => p.endsWith('/file')).length;
  await page.reload();
  await focused('sample-01');
  await page.getByText('项目已暂停，原图不可读取', { exact: true }).waitFor();
  assert.equal(
    report.requests.filter((p) => p.endsWith('/file')).length,
    before,
  );
  paused = false;
  await go(`/labeling/projects/${project.projectId}`);
  await page
    .getByRole('heading', { name: project.name, exact: true })
    .waitFor();
  await capture('project-setup', 390);
  for (const [path, title] of [
    ['/labeling/tasks', '任务广场'],
    ['/labeling/assignments', '我的标注任务'],
    ['/labeling/admin/users', '标注用户管理'],
    ['/labeling/submissions/submission-a', '已交回的人工包结果'],
  ]) {
    await go(path);
    await page.getByRole('heading', { name: title, exact: true }).waitFor();
    assert.equal(
      await page
        .getByRole('navigation', { name: '标注平台导航' })
        .getByRole('link', { name: '旧版 Demo', exact: true })
        .count(),
      1,
    );
    await capture(path.split('/').at(-1), 390);
  }
  report.checks.push(
    'Shared TSSAI header, left functional navigation, violet selections and white panels cover projects, setup, task pool, own tasks, users and submission details; mobile navigation scrolls and inspector stacks below the table; paused projects make no file request',
  );
  assert.deepEqual(report.pageErrors, []);
  assert.deepEqual(report.unexpectedRequests, []);
  report.passed = true;
  console.log(JSON.stringify(report));
} catch (error) {
  report.passed = false;
  report.failure = String(error);
  console.log(
    'FAILURE',
    page.url(),
    (
      await page
        .locator('body')
        .innerText({ timeout: 2000 })
        .catch(() => '')
    ).slice(-2200),
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
