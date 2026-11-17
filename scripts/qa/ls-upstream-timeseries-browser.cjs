const fs = require('node:fs');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { zipSync, strToU8 } = require('fflate');
const { chromium } = require('../../../.tools/browser-tests/node_modules/playwright-core');
const base = 'http://127.0.0.1:8001';
const report = { steps: [], errors: [] };
(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true });
  const page = await browser.newPage({ viewport: { width: 1700, height: 1050 } });
  page.setDefaultTimeout(40000);
  page.on('pageerror', error => report.errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  await page.route('**/*', route => ['127.0.0.1', 'localhost'].includes(new URL(route.request().url()).hostname) ? route.continue() : route.abort());
  try {
    await page.goto(base + '/#/user/login');
    await page.getByPlaceholder('请输入用户名或手机号').fill(process.env.LS_TEST_USERNAME || 'local_dev');
    await page.getByPlaceholder('请输入密码', { exact: true }).fill(process.env.LS_TEST_PASSWORD || 'LocalDev_2026');
    await page.getByRole('button', { name: '登录工作空间' }).click();
    await page.waitForURL(url => !url.hash.includes('/user/login'));
    const token = await page.evaluate(() => JSON.parse(sessionStorage.getItem('token') || localStorage.getItem('token')));
    const api = async (path, body, method = 'POST', extra = {}) => {
      const response = await page.request.fetch(base + '/api/labeling/v1' + path, { method,
        headers: { Authorization: 'Bearer ' + token, 'Idempotency-Key': crypto.randomUUID(), 'Content-Type': body instanceof Buffer ? 'application/octet-stream' : 'application/json', ...extra },
        ...(body === undefined ? {} : { data: body instanceof Buffer ? body : JSON.stringify(body) }) });
      const value = await response.json();
      assert.ok(response.ok(), JSON.stringify(value));
      return value.data;
    };
    const upload = async (project, files) => {
      const bytes = Buffer.from(zipSync(Object.fromEntries(Object.entries(files).map(([name, text]) => [name, strToU8(text)]))));
      const hash = crypto.createHash('sha256').update(bytes).digest('hex');
      const batch = await api('/projects/' + project + '/imports', { filename: 'curves.zip', sizeBytes: bytes.length, sha256: hash });
      await api('/imports/' + batch.batchId + '/chunks?offset=0', bytes, 'PUT', { 'X-Chunk-SHA256': hash });
      await api('/imports/' + batch.batchId + '/complete');
      let ready = false;
      for (let i = 0; i < 60; i++) {
        const current = await api('/imports/' + batch.batchId, undefined, 'GET');
        if (current.status === 'READY') { ready = true; break; }
        await page.waitForTimeout(300);
      }
      assert.ok(ready);
    };
    const curve = 'time,power\n0,3\n1,\n2,5\n';
    const project = await api('/projects', { name: 'LS 原版曲线行为验收-' + Date.now(), taskType: 'TIME_SERIES' });
    report.projectId = project.projectId;
    await upload(project.projectId, { 'curve.csv': curve });
    await page.goto(base + '/?upstream=file#/labeling/projects/' + project.projectId);
    await page.getByRole('button', { name: '文件作为一个样本', exact: true }).click();
    await page.getByText('标注样本（1）', { exact: true }).waitFor();
    report.steps.push('csv-file-to-one-task-without-mapping');
    await page.getByRole('tab', { name: '2 标注设置', exact: true }).click();
    await page.waitForFunction(() => window.Htx?.annotationStore?.selected?.names.get('series_view')?.dataObj?.power?.length === 3);
    const data = await page.evaluate(() => window.Htx.annotationStore.selected.names.get('series_view').dataObj);
    assert.deepEqual(data.power, [3, 0, 5]);
    assert.deepEqual(data.time, [0, 1, 2]);
    report.steps.push('upstream-csv-empty-cell-behavior');
    const savedConfig = page.waitForResponse(response => response.url().includes('/spec-versions') && response.request().method() === 'POST');
    await page.getByRole('button', { name: '保存配置', exact: true }).click();
    const response = await savedConfig;
    assert.equal(response.status(), 201);
    const spec = (await response.json()).data;
    report.specVersionId = spec.specVersionId;
    assert.match(spec.editorConfig.labelConfig, /valueType="url"/);
    const samples = (await api('/projects/' + project.projectId + '/input-samples', undefined, 'GET')).items;
    const plan = await api('/projects/' + project.projectId + '/package-plans', { specVersionId: spec.specVersionId, packageSize: 1, samples: [{ sampleId: samples[0].sampleId }] });
    const published = await api('/package-plans/' + plan.planId + '/publish', { selectionHash: plan.selectionHash });
    const assignment = await api('/packages/' + published.packages[0].packageId + '/claims', {});
    report.assignmentId = assignment.assignmentId;
    await page.goto(base + '/?upstream=editor#/labeling/assignments/' + assignment.assignmentId);
    await page.waitForFunction(() => window.Htx?.annotationStore?.selected?.names.get('series_view')?.dataObj?.power?.length === 3);
    assert.equal(await page.getByRole('button', { name: '放大时间范围', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: '缩小时间范围', exact: true }).count(), 0);
    await page.getByRole('checkbox', { name: /标签一/ }).first().check();
    const saved = page.waitForResponse(response => response.url().includes('/drafts/') && response.request().method() === 'PUT');
    await page.getByRole('button', { name: '标记完成并保存', exact: true }).click();
    assert.equal((await saved).status(), 200);
    report.steps.push('native-curve-without-platform-zoom-buttons-and-save');
    const rowsProject = await api('/projects', { name: 'LS CSV 行任务验收-' + Date.now(), taskType: 'TIME_SERIES' });
    await upload(rowsProject.projectId, { 'manifest.csv': 'csv,caption\ncurve-a.csv,A\ncurve-b.csv,B\n', 'curve-a.csv': curve, 'curve-b.csv': curve });
    await page.goto(base + '/?upstream=rows#/labeling/projects/' + rowsProject.projectId);
    await page.getByRole('row').filter({ hasText: 'manifest.csv' }).getByRole('button', { name: '每行一个样本', exact: true }).click();
    await page.getByText('标注样本（2）', { exact: true }).waitFor();
    report.steps.push('csv-rows-to-tasks-without-mapping');
    report.rowsProjectId = rowsProject.projectId;
    assert.deepEqual(report.errors, []);
    report.success = true;
  } catch (error) {
    report.failure = error.stack;
    report.url = page.url();
    report.body = (await page.locator('body').innerText()).slice(-5000);
    await page.screenshot({ path: 'D:/projects/Annotation/.tools/native-tests/ls-upstream-failed.png', fullPage: true });
    process.exitCode = 1;
  } finally {
    fs.writeFileSync('D:/projects/Annotation/.tools/native-tests/ls-upstream-regression.json', JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
    await browser.close();
  }
})();
