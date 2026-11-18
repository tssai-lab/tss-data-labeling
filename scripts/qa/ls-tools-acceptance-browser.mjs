import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import {
  createLsTimeseriesConfig,
  createLsTimeseriesTask,
} from '../../src/services/labelStudioTimeseries.mjs';
const require = createRequire(import.meta.url),
  {
    chromium,
  } = require('../../../.tools/browser-tests/node_modules/playwright-core');
const browser = await chromium.launch({
  executablePath:
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
});
const page = await browser.newPage({ viewport: { width: 1500, height: 1100 } }),
  errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.route('**/*', async (route) => {
  const url = new URL(route.request().url());
  if (url.origin !== 'http://127.0.0.1:8017') return route.abort();
  if (url.pathname === '/harness')
    return route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><meta charset="utf-8"><div id="host"></div><script src="/ls-editor/${JSON.parse(await fs.readFile("public/ls-editor/manifest.json", "utf8")).entry}"></script>`,
    });
  const file = path.resolve('public', `.${url.pathname}`);
  if (!file.startsWith(path.resolve('public') + path.sep)) return route.abort();
  try {
    return route.fulfill({
      body: await fs.readFile(file),
      contentType: file.endsWith('.js')
        ? 'text/javascript'
        : file.endsWith('.jpg')
          ? 'image/jpeg'
          : 'application/octet-stream',
    });
  } catch {
    return route.fulfill({ status: 404, body: '' });
  }
});
async function mount(config, task, result = []) {
  await page.evaluate(
    async ({ config, task, result }) => {
      if (window.editor) {
        await window.editor.ready;
        window.editor.destroy();
      }
      const root =
        document.getElementById('host').shadowRoot ||
        document.getElementById('host').attachShadow({ mode: 'open' });
      root.replaceChildren();
      window.PlatformLs.attachStyles(root);
      const node = document.createElement('div');
      root.append(node);
      window.editor = new window.PlatformLs.LabelStudio(node, {
        config,
        task,
        instanceOptions: { reactVersion: 'v18' },
        interfaces: [
          'topbar',
          'side-column',
          'controls',
          'edit-history',
          'annotations:current',
        ],
        customButtons: { _replace: [] },
        onStorageInitialized(store) {
          const a = store.annotationStore.addAnnotation({ userGenerate: true });
          store.annotationStore.selectAnnotation(a.id);
          a.deserializeResults(result);
        },
      });
      await window.editor.ready;
    },
    { config, task, result },
  );
}
try {
  await page.goto('http://127.0.0.1:8017/harness');
  const input = {
    sampleId: 'energy:gap-test',
    inputFormat: 'energy-station-day-input-v1',
    schemaId: 'energy-station-day-input@1.0.0',
    inputHash: 'fixture',
    payload: {
      stationKey: 's',
      dateLocal: '2026-10-05',
      timezone: 'Asia/Shanghai',
      dailyKwh: null,
      qualityReasons: ['SOURCE_MISSING'],
      series: {
        name: 'load_curve',
        channel: 'value_kwh',
        unit: 'kWh',
        periodSeconds: 3600,
        start: '2026-10-05T00:00:00+08:00',
        values: Array.from({ length: 24 }, (_, h) => (h === 5 ? null : h)),
      },
    },
  };
  const config = createLsTimeseriesConfig([{ id: 'normal', name: '正常' }]),
    task = createLsTimeseriesTask(input);
  await mount(config, task);
  await page.waitForFunction(
    () =>
      window.Htx.annotationStore.selected.names.get('load_curve')?.dataObj
        ?.value_kwh?.length === 24,
  );
  const curve = page
    .locator('path[vector-effect="non-scaling-stroke"]')
    .first();
  await curve.waitFor();
  assert.equal((await curve.getAttribute('d')).split('M').length - 1, 2);
  assert.equal(
    await page.evaluate(
      () =>
        window.Htx.annotationStore.selected.names.get('load_curve').dataObj
          .value_kwh[5],
    ),
    null,
  );
  assert.equal(
    await page.evaluate(
      () =>
        window.Htx.annotationStore.selected.names.get('load_curve').dataObj
          .value_kwh[0],
    ),
    0,
  );
  const ranges = await page.evaluate(() => {
    const object = window.Htx.annotationStore.selected.names.get('load_curve');
    const before = [...object.brushRange];
    window.PlatformLs.adjustTimeseriesView(window.editor, 'in');
    const zoom = [...object.brushRange];
    window.PlatformLs.adjustTimeseriesView(window.editor, 'reset');
    return { before, zoom, reset: [...object.brushRange] };
  });
  assert.ok(ranges.zoom[1] - ranges.zoom[0] < 23);
  assert.deepEqual(ranges.reset, [0, 23]);
  await fs.mkdir('.qa', { recursive: true });
  await page.screenshot({ path: '.qa/ls-gaps.png', fullPage: true });
  // Exercise the native CSV parser too, not only inline JSON.
  const csvConfig = config.replace('valueType="json"', 'valueType="url"');
  const mapped = await page.evaluate(
    ({ config, task }) =>
      window.PlatformLs.createLocalEditorTask(config, task, []),
    { config: csvConfig, task },
  );
  await mount(csvConfig, { id: 'energy:csv-gaps', data: mapped });
  await page.waitForFunction(
    () =>
      window.Htx.annotationStore.selected.names.get('load_curve')?.dataObj
        ?.value_kwh?.length === 24,
  );
  assert.equal(
    await page.evaluate(
      () =>
        window.Htx.annotationStore.selected.names.get('load_curve').dataObj
          .value_kwh[5],
    ),
    null,
  );
  assert.equal(
    (
      await page
        .locator('path[vector-effect="non-scaling-stroke"]')
        .first()
        .getAttribute('d')
    ).split('M').length - 1,
    2,
  );
  const empty = createLsTimeseriesTask({
    ...input,
    payload: {
      ...input.payload,
      series: { ...input.payload.series, values: Array(24).fill(null) },
    },
  });
  await mount(config, empty);
  await page.waitForFunction(
    () =>
      window.Htx.annotationStore.selected.names.get('load_curve')?.dataObj
        ?.value_kwh?.length === 24,
  );
  assert.equal(
    await page
      .locator('path[vector-effect="non-scaling-stroke"]')
      .first()
      .getAttribute('d'),
    null,
  );
  for (const [control, type, label] of [
    ['RectangleLabels', 'rectanglelabels', '目标框'],
    ['PolygonLabels', 'polygonlabels', '轮廓'],
    ['BrushLabels', 'brushlabels', '掩码'],
  ]) {
    const imageConfig = `<View><Image name="image" value="$image"/><${control} name="label" toName="image"><Label value="${label}"/></${control}></View>`;
    await mount(imageConfig, {
      id: `image-${type}`,
      data: { image: '/ls-template-samples/sample.jpg' },
    });
    await page.getByText(label, { exact: true }).first().click();
    const canvas = page.locator('canvas').first();
    await canvas.waitFor();
    await page.waitForFunction(
      () =>
        window.Htx.annotationStore.selected.names.get('image')?.naturalWidth >
        0,
    );
    await canvas.scrollIntoViewIfNeeded();
    const box = await canvas.boundingBox();
    const point = (x, y) => ({
      x: box.x + box.width * x,
      y: box.y + box.height * y,
    });
    if (type === 'polygonlabels') {
      for (const [x, y] of [
        [0.2, 0.2],
        [0.55, 0.2],
        [0.55, 0.55],
      ]) {
        const p = point(x, y);
        await page.mouse.click(p.x, p.y);
      }
      const p = point(0.2, 0.2);
      await page.mouse.click(p.x, p.y);
    } else {
      const a = point(0.2, 0.2),
        b = point(0.55, 0.55);
      await page.mouse.move(a.x, a.y);
      await page.mouse.down();
      await page.mouse.move(b.x, b.y, { steps: 12 });
      await page.mouse.up();
    }
    await page.waitForFunction(
      (type) =>
        window.Htx.annotationStore.selected
          .serializeAnnotation()
          .some((r) => r.type === type),
      type,
    );
    const result = await page.evaluate(() =>
      window.Htx.annotationStore.selected.serializeAnnotation(),
    );
    assert.ok(result.some((r) => r.type === type));
    const zoom = page.getByRole('button', { name: '放大图片', exact: true });
    await zoom.click();
    await page.getByRole('button', { name: '缩小图片', exact: true }).click();
    await mount(
      imageConfig,
      {
        id: `image-${type}-restore`,
        data: { image: '/ls-template-samples/sample.jpg' },
      },
      result,
    );
    await page.waitForFunction(
      (type) =>
        window.Htx.annotationStore.selected
          .serializeAnnotation()
          .some((r) => r.type === type),
      type,
    );
    await page.screenshot({ path: `.qa/ls-${type}.png`, fullPage: true });
  }
  assert.deepEqual(errors, []);
  console.log(
    JSON.stringify({
      missingJsonAndCsv: true,
      allMissing: true,
      zoomAndReset: true,
      rectangle: true,
      polygon: true,
      brush: true,
      restore: true,
      errors,
    }),
  );
} catch (e) {
  await fs.mkdir('.qa', { recursive: true });
  await page.screenshot({ path: '.qa/ls-tools-failure.png', fullPage: true });
  console.error(await page.locator('body').innerText());
  throw e;
} finally {
  await browser.close();
}
