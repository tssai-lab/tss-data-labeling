import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url),
  {
    chromium,
  } = require('../../../.tools/browser-tests/node_modules/playwright-core');
const browser = await chromium.launch({
  executablePath:
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
});
const page = await browser.newPage({ viewport: { width: 1700, height: 1100 } }),
  errors = [];
page.on('pageerror', (e) => errors.push(e.message));
const requests=[];page.on('response',r=>{if(r.url().includes('/api/labeling/v1/label-configs/'))requests.push({path:new URL(r.url()).pathname,status:r.status()});});
page.on('dialog', (d) => d.accept());
await page.route('**/*', (r) =>
  ['127.0.0.1', 'localhost'].includes(new URL(r.request().url()).hostname)
    ? r.continue()
    : r.abort(),
);
try {
  await page.goto('http://127.0.0.1:8001/#/user/login');
  await page.getByPlaceholder('请输入用户名或手机号').fill(process.env.LS_TEST_USERNAME || 'local_dev');
  await page.getByPlaceholder('请输入密码',{exact:true}).fill(process.env.LS_TEST_PASSWORD || 'LocalDev_2026');
  await page.getByRole('button',{name:'登录工作空间'}).click();
  await page.waitForURL(url=>!url.hash.includes('/user/login'));
  await page.goto('http://127.0.0.1:8001/#/labeling/templates');
  await page
    .locator('.lsf-templates-list__group')
    .filter({ hasText: '图像与计算机视觉' })
    .waitFor({ timeout: 40000 });
  assert.equal(
    await page.locator('.lsf-templates-list__sidebar li').count(),
    2,
  );
  assert.equal(await page.getByText('平台能耗', { exact: true }).count(), 0);
  assert.equal(await page.locator('.lsf-templates-list__template').count(), 12);
  assert.equal(await page.getByRole('heading', { name: 'PDF OCR 标注', exact: true }).count(), 0);
  await page
    .locator('.lsf-templates-list__group')
    .filter({ hasText: '时序分析' })
    .click();
  for (const name of [
    '活动识别',
    '变化点检测',
    '离群点与异常检测',
    '信号质量',
    '时序预测',
  ])
    await page.getByRole('heading', { name, exact: true }).waitFor();
  await fs.mkdir('.qa', { recursive: true });
  await page.screenshot({ path: '.qa/ls-native-catalog.png', fullPage: true });
  await page.getByRole('heading', { name: '时序预测', exact: true }).click();
  await page.waitForFunction(
    () =>
      window.Htx?.annotationStore?.selected?.names.get('stock')?.brushRange
        ?.length === 2,
  );
  const csv = await page.evaluate(async () => await (await fetch(JSON.parse(window.Htx.task.data).csv)).text());
  assert.match(csv, /value/);
  await page.waitForFunction(() => window.Htx?.annotationStore?.selected?.names.get('stock')?.dataObj?.value?.length===100);
  await page.getByText('示例数据预览，操作不会保存为正式标注结果。',{exact:true}).waitFor();
  await page.screenshot({
    path: '.qa/ls-native-timeseries.png',
    fullPage: true,
  });
  await page.getByRole('button', { name: '浏览模板', exact: true }).click();
  await page
    .locator('.lsf-templates-list__group')
    .filter({ hasText: '图像与计算机视觉' })
    .click();
  await page.getByRole('heading', { name: '图像分类', exact: true }).click();
  await page.getByRole('button', { name: '浏览模板', exact: true }).waitFor();
  await page.waitForFunction(() => !!window.Htx?.annotationStore?.selected);
  const alerts = await page.getByRole('alert').allTextContents();
  assert.deepEqual(
    alerts.filter((t) => t.includes('校验失败')),
    [],
  );
  await page.getByText('XML 代码', { exact: true }).click();
  await page.locator('.CodeMirror').waitFor();
  const original = await page
    .locator('.CodeMirror')
    .evaluate((el) => el.CodeMirror.getValue());
  assert.match(original, /<Image/);
  const edited = original.replace(
    '<View>',
    '<View>\n<Header value="中文 XML 实时预览"/>',
  );
  await page
    .locator('.CodeMirror')
    .evaluate((el, v) => el.CodeMirror.setValue(v), edited);
  await page.getByText('中文 XML 实时预览', { exact: true }).waitFor();
  const rejected = await page.evaluate(() => {
    try {
      window.PlatformLs.validateLabelConfig('<View><Unknown name="x"/></View>');
      return false;
    } catch {
      return true;
    }
  });
  assert.equal(rejected, true);
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  await page
    .getByText('模板草稿已保存到本浏览器，尚未绑定正式项目或发布。', {
      exact: true,
    })
    .waitFor();
  await page.getByText('可视化', { exact: true }).click();
  await page.getByRole('textbox', { name: '标签名称' }).first().waitFor();
  const name = await page
    .getByRole('textbox', { name: '标签名称' })
    .first()
    .inputValue();
  await page
    .getByRole('textbox', { name: '标签名称' })
    .first()
    .fill('中文类别');
  await page.getByText('XML 代码', { exact: true }).click();
  const aliasXml = await page
    .locator('.CodeMirror')
    .evaluate((el) => el.CodeMirror.getValue());
  assert.ok(aliasXml.includes(`alias="${name}"`));
  await page.getByText('可视化', { exact: true }).click();
  const handle = page.locator('[title="拖动调整宽度"]');
  const before = await page
    .locator('.lsf-configure__container')
    .evaluate((el) => getComputedStyle(el).gridTemplateColumns);
  const box = await handle.boundingBox();
  assert.ok(box);
  await page.mouse.move(box.x + box.width / 2, box.y + 20);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 80, box.y + 20, { steps: 8 });
  await page.mouse.up();
  const after = await page
    .locator('.lsf-configure__container')
    .evaluate((el) => getComputedStyle(el).gridTemplateColumns);
  assert.notEqual(before, after);
  await page.screenshot({ path: '.qa/ls-native-config.png', fullPage: true });
  await page.getByRole('button', { name: '浏览模板', exact: true }).click();
  await page
    .getByRole('button', { name: '创建自定义配置', exact: true })
    .click();
  await page.locator('.CodeMirror').waitFor();
  await page
    .locator('.CodeMirror')
    .evaluate((el, v) => el.CodeMirror.setValue(v), edited);
  await page.getByText('中文 XML 实时预览', { exact: true }).waitFor();
  await page.reload();
  await page
    .getByRole('button', { name: '恢复本地模板草稿', exact: true })
    .click();
  await page.getByRole('button', { name: '浏览模板', exact: true }).waitFor();
  await page.getByText('中文 XML 实时预览', { exact: true }).waitFor();
  assert.deepEqual(errors, []);
  for(const endpoint of ['templates','data-summary','validate','sample-task'])assert.ok(requests.some(r=>r.path.endsWith('/'+endpoint)&&r.status===200),endpoint);
  console.log(
    JSON.stringify({
      categories: 2,
      httpRequests: requests,
      availableTemplates: 17,
      nativeGallery: true,
      visualAndXml: true,
      liveImagePreview: true,
      customConfig: true,
      saveRestore: true,
      generatedTimeseriesPreview: true,
      labelAliasesStable: true,
      resizablePanels: true,
      errors,
    }),
  );
} catch (e) {
  await fs.mkdir('.qa', { recursive: true });
  await page.screenshot({ path: '.qa/ls-native-failure.png', fullPage: true });
  console.error(await page.getByRole('alert').allTextContents());
  console.error(errors);
  throw e;
} finally {
  await browser.close();
}
