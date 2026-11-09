// Reproducible UI fixtures only, never a live backend or a replacement for integration acceptance.
// npm install --prefix .qa --no-save --package-lock=false playwright
// node scripts/qa/labeling-v11.browser.mjs   (dev:labeling-demo must be running)
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require('../../.qa/node_modules/playwright');
const base = process.env.LABELING_UI_BASE || 'http://127.0.0.1:8001';
const output = fileURLToPath(
  new URL('../../docs/labeling-v11-evidence/', import.meta.url),
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
  sessionNo = 0;
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
  samples.map((s) => [
    s.sampleId,
    {
      assignmentId: aid,
      sampleId: s.sampleId,
      revision: 1,
      explicitDone: true,
      resultFormat: 'image-classification-v1',
      result: { labelId: 'none' },
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
  totalWorkload: 24,
  packages: [
    { packageId, sequenceNo: 1, sampleCount: 10, requiredCount: 2 },
    { packageId: id(8), sequenceNo: 2, sampleCount: 2, requiredCount: 2 },
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
      items: samples.map((s) => ({
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
              remainingSlots: 2,
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
      items: samples.map((s) => ({
        ...s,
        draftRevision: drafts[s.sampleId].revision,
        explicitDone: true,
        resultFormat: 'image-classification-v1',
        result: drafts[s.sampleId].result,
      })),
      nextCursor: null,
    });
  if (path.includes('/drafts/')) {
    const sampleId = path.split('/').at(-1);
    if (request.method() === 'PUT') {
      const body = request.postDataJSON();
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
  if (path.endsWith('/editor-sessions')) {
    const sampleId = request.postDataJSON().sampleId;
    const editorSessionId = `fixture-session-${++sessionNo}`;
    return json({
      editorSessionId,
      assignmentId: aid,
      projectId: pid,
      sampleId,
      actorId: actor,
      specVersionId: specId,
      mode: 'ASSIGNMENT_IMAGE_CLASSIFICATION',
      editorUrl: `/api/labeling/v1/editor-ui/${editorSessionId}/launch?ticket=synthetic-one-time`,
      launchExpiresAt: new Date(Date.now() + 60000).toISOString(),
      expiresAt: new Date(Date.now() + 1800000).toISOString(),
      draft: drafts[sampleId],
    });
  }
  if (path.includes('/editor-ui/')) {
    const sid = path.split('/')[2];
    const ctx = {
      editorSessionId: sid,
      assignmentId: aid,
      projectId: pid,
      sampleId: samples[0].sampleId,
      actorId: actor,
      specVersionId: specId,
    };
    return route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><html lang="zh"><meta charset="utf-8"><style>body{font:16px sans-serif;margin:16px;overflow-wrap:anywhere}img{max-width:100%;height:auto}button{margin:8px;padding:10px}:focus{outline:3px solid blue}</style><h2>合成受控编辑器夹具（非 Label Studio）</h2><p>测试同源消息握手、脏状态、原生保存通知和平台草稿回读。</p><img alt="宽图夹具" width="3000" height="500" src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='3000' height='500'%3E%3Crect width='3000' height='500' fill='%23dce8f8'/%3E%3C/svg%3E"><button id="dirty">修改分类（未保存）</button><button id="save">Submit / Update 草稿</button><script>const ctx=${JSON.stringify(ctx)};let dirty=false,saving=false,revision=1;const send=(type,extra={})=>parent.postMessage({...ctx,type,...extra},location.origin);window.addEventListener('message',e=>{if(e.source!==parent||e.origin!==location.origin||e.data.editorSessionId!==ctx.editorSessionId)return;const requestId=e.data.requestId;if(e.data.type==='labeling:request-state')send('labeling:state',{requestId,ready:true,saving,dirty,revision});if(e.data.type==='labeling:discard'){dirty=false;send('labeling:discarded',{requestId});}});document.querySelector('#dirty').onclick=()=>dirty=true;document.querySelector('#save').onclick=async()=>{saving=true;const r=await fetch('/api/labeling/v1/assignments/'+ctx.assignmentId+'/drafts/'+ctx.sampleId,{method:'PUT',body:JSON.stringify({baseRevision:revision,explicitDone:true,resultFormat:'image-classification-v1',result:{labelId:'normal'}})});const b=await r.json();saving=false;if(!r.ok){send('labeling:save-failed',{code:b.error.code,message:b.error.message,status:r.status});return;}revision=b.data.revision;dirty=false;send('labeling:saved',{revision,dirty});};send('labeling:ready',{revision});</script></html>`,
    });
  }
  if (path.endsWith('/editor-sync'))
    return json(drafts[request.postDataJSON().sampleId]);
  if (path.endsWith('/submissions') && request.method() === 'POST') {
    submitted = true;
    return json(
      {
        submissionId: summaries[0].submissionId,
        assignmentId: aid,
        status: 'SUBMITTED',
        sampleCount: 12,
      },
      201,
    );
  }
  if (path.endsWith('/file'))
    return route.fulfill({
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jh8kAAAAASUVORK5CYII=',
        'base64',
      ),
    });
  report.unexpectedRequests.push(`${request.method()} ${path}`);
  return error(404, 'FIXTURE_NOT_IMPLEMENTED');
});
async function visit(path, text) {
  await page.goto(`${base}/#${path}`);
  await page.getByText(text, { exact: false }).first().waitFor();
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
      .frameLocator('iframe')
      .getByText('合成受控编辑器夹具', { exact: false })
      .waitFor();
    await capture('assignment', width);
    await page.getByRole('link', { name: '我的任务', exact: true }).click();
    await page.getByRole('heading', { name: '我的标注任务' }).waitFor();
    await visit(
      `/labeling/submissions/${summaries[0].submissionId}`,
      '这是整包提交时冻结的结果',
    );
    await capture('submission', width);
    await visit('/labeling/admin/users', '合成标注员');
    await capture('admin-users', width);
    await page
      .getByRole('button', { name: '修改权限', exact: true })
      .nth(1)
      .click();
    await page.getByRole('dialog').waitFor();
    await page.waitForTimeout(400);
    await capture('admin-modal', width);
    await page
      .getByRole('dialog')
      .getByRole('button', { name: /取.*消/ })
      .click();
  }
  report.checks.push(
    'Five core pages and admin modal at 1280 / 768 / 390; page overflow asserted; table scroll stays local; long filenames / instructions / wide editor fixture',
  );
  await page.setViewportSize({ width: 1280, height: 900 });
  await visit(`/labeling/assignments/${aid}`, '图片分类标注');
  const frame = page.frameLocator('iframe');
  await frame.getByRole('button', { name: '修改分类（未保存）' }).click();
  await page
    .getByRole('button', { name: '提交整个任务包', exact: true })
    .click();
  await page
    .getByText('请先完成当前图片草稿保存与平台回读', { exact: false })
    .waitFor();
  assert.equal(await page.getByRole('dialog').count(), 0);
  let rejectedLeave = false;
  page.once('dialog', async (dialog) => {
    rejectedLeave = true;
    await dialog.dismiss();
  });
  await page.getByRole('link', { name: '我的任务', exact: true }).click();
  await page.waitForTimeout(250);
  assert.ok(rejectedLeave);
  assert.ok(page.url().includes(aid));
  await frame.getByRole('button', { name: 'Submit / Update 草稿' }).click();
  await page.getByText('平台草稿 revision 2', { exact: false }).waitFor();
  report.checks.push(
    'Dirty editor blocks full submission; route guard cancels leaving; native editor save requires real client GET draft readback',
  );
  revisionConflict = true;
  await frame.getByRole('button', { name: 'Submit / Update 草稿' }).click();
  await page.getByText('REVISION_CONFLICT', { exact: false }).first().waitFor();
  await capture('editor-conflict', 1280);
  revisionConflict = false;
  await page.getByRole('button', { name: '恢复核对（editor-sync）' }).click();
  await page.getByText('编辑器已就绪', { exact: false }).waitFor();
  report.checks.push(
    '409 shown; editor-sync calls GET readback and opens a fresh session',
  );
  await page
    .getByRole('button', { name: '提交整个任务包', exact: true })
    .click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '确认提交', exact: true })
    .click();
  await page.getByText('已提交 · 只读', { exact: true }).waitFor();
  assert.equal(await page.locator('iframe').count(), 0);
  report.checks.push(
    'Full package submission freezes work as read-only and removes editor',
  );
  // Simulate a refresh after a server-accepted submission whose receipt was lost.
  await page.evaluate(
    ({ actor, aid, items }) => {
      const key = `labeling:pending-submission:${actor}:${aid}`;
      sessionStorage.removeItem(`${key}:receipt`);
      sessionStorage.setItem(
        key,
        JSON.stringify({
          actorId: actor,
          assignmentId: aid,
          key: 'synthetic-lost-receipt-key',
          items,
        }),
      );
    },
    {
      actor,
      aid,
      items: samples.map((s) => ({
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
    'Lost receipt remains replayable with original revision manifest even when reloaded work is read-only',
  );

  await page.getByRole('link', { name: '我的任务', exact: true }).click();
  await page.getByRole('heading', { name: '我的标注任务' }).waitFor();
  isAdmin = false;
  await visit('/labeling/admin/users', '没有标注管理员权限');
  assert.equal(
    await page.getByRole('button', { name: '修改权限', exact: true }).count(),
    0,
  );
  report.checks.push(
    'Ordinary user directly visiting admin route is denied; self mutation button disabled',
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
  report.checks.push('403 preserves login token, displays code and requestId');
  denied = false;
  empty = true;
  await visit('/labeling/tasks', '暂无可领取任务');
  await capture('empty-pool', 390);
  empty = false;
  await page.setViewportSize({ width: 1280, height: 900 });
  await visit(`/labeling/projects/${pid}`, '分类规范与历史版本');
  await page.getByRole('button', { name: '从此规范创建新版本' }).click();
  await page
    .getByRole('textbox', { name: '分类 1 名称', exact: true })
    .fill('正常（更名）');
  await page
    .getByRole('button', { name: /上.*移/ })
    .nth(1)
    .click();
  await page.getByRole('button', { name: '保存新版本', exact: true }).click();
  await page.getByRole('button', { name: '从此规范创建新版本' }).waitFor();
  assert.deepEqual(project.specVersions[0].labels, [
    { id: 'none', name: '无目标' },
    { id: 'normal', name: '正常（更名）' },
  ]);
  report.checks.push(
    'Clone / rename / reorder specification keeps label IDs and verifies real-client project readback',
  );
  await visit('/labeling/admin/users', '合成标注员');
  assert.equal(
    await page
      .getByRole('button', { name: '修改权限', exact: true })
      .first()
      .isDisabled(),
    true,
  );
  await page
    .getByRole('button', { name: '修改权限', exact: true })
    .nth(1)
    .click();
  await page
    .getByRole('textbox', { name: /操作原因/ })
    .fill('合成浏览器验收原因');
  await page.getByRole('button', { name: '确认修改', exact: true }).click();
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  report.checks.push(
    'Admin reason form confirms PATCH data:null successfully; self-disable/revoke entry disabled',
  );
  await visit('/labeling/demo', '本地合成演示');
  await page.getByRole('button', { name: '创建演示导出' }).click();
  await page.getByText('QUEUED', { exact: true }).waitFor();
  await page.getByRole('button', { name: '查询状态', exact: true }).click();
  await page.getByText('RUNNING', { exact: true }).waitFor();
  await page.getByRole('button', { name: '查询状态', exact: true }).click();
  await page.getByText('SUCCEEDED', { exact: true }).waitFor();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载 ZIP' }).click();
  const download = await downloadPromise;
  await download.saveAs(`${output}/synthetic-export.zip`);
  await capture('gap-demo', 1280);
  report.checks.push(
    'UI mock export queue/running/success and downloadable ZIP containing independent N=2 results',
  );
  const anonymous = await browser.newPage();
  const anonymousRequests = [];
  await anonymous.route('**/api/**', (route) => {
    anonymousRequests.push(new URL(route.request().url()).pathname);
    return route.abort();
  });
  await anonymous.goto(`${base}/#/labeling/demo`);
  await anonymous.getByRole('button', { name: '创建演示导出' }).waitFor();
  await anonymous.getByRole('button', { name: '创建演示导出' }).click();
  await anonymous.getByText('QUEUED', { exact: true }).waitFor();
  assert.deepEqual(anonymousRequests, []);
  await anonymous.close();
  report.checks.push(
    'Standalone synthetic demo works without a login or backend and performs no HTTP requests',
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
