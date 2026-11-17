import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import {
  importTimeseriesCsv,
  createGeneralTimeseriesConfig,
  createGeneralTimeseriesTask,
} from '../../src/services/timeseriesInput.mjs';
const require = createRequire(import.meta.url),
  {
    chromium,
  } = require('../../../.tools/browser-tests/node_modules/playwright-core');
const browser = await chromium.launch({
    executablePath:
      'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true,
  }),
  page = await browser.newPage({ viewport: { width: 1500, height: 1000 }, timezoneId:'Asia/Shanghai' }),
  errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.route('**/*', async (r) => {
  const u = new URL(r.request().url());
  if (u.origin !== 'http://127.0.0.1:8019') return r.abort();
  if (u.pathname === '/harness')
    return r.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><meta charset="utf-8"><div id="host"></div><script src="/ls-editor/${JSON.parse(await fs.readFile("public/ls-editor/manifest.json", "utf8")).entry}"></script>`,
    });
  const file = path.resolve('public', `.${u.pathname}`);
  if (!file.startsWith(path.resolve('public') + path.sep)) return r.abort();
  try {
    return r.fulfill({
      body: await fs.readFile(file),
      contentType: file.endsWith('.js')
        ? 'text/javascript'
        : 'application/octet-stream',
    });
  } catch {
    return r.fulfill({ status: 404, body: '' });
  }
});
try {
  await page.goto('http://127.0.0.1:8019/harness');
  const { samples } = await importTimeseriesCsv(
    'timestamp,temperature,pressure\n2026-11-14T08:00:00+08:00,-5,0\n2026-11-14T00:01:00Z,,1\n2026-11-14T08:03:00+08:00,0,2',
    {
      timeColumn: 'timestamp',
      channelColumns: ['temperature', 'pressure'],
      kind: 'timestamp',
      units: { temperature: '℃', pressure: 'kPa' },
    },
  );
  const input = samples[0].input,
    base = createGeneralTimeseriesConfig(input),
    task = createGeneralTimeseriesTask(input);
  for (const type of ['json', 'url']) {
    const config = base.replace('valueType="json"', `valueType="${type}"`),
      start = String(input.payload.time.values[0]),
      end = String(input.payload.time.values[2]);
    const restored = await page.evaluate(
      async ({ config, task, start, end }) => {
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
        const urls = [],
          data = window.PlatformLs.createLocalEditorTask(config, task, urls);
        window.currentUrls = urls;
        window.editor = new window.PlatformLs.LabelStudio(node, {
          config,
          task: {
            id: `date-${crypto.randomUUID()}`,
            data,
            annotations: [],
            predictions: [],
          },
          instanceOptions: { reactVersion: 'v18' },
          interfaces: ['topbar', 'side-column', 'edit-history'],
          customButtons: { _replace: [] },
          onStorageInitialized(store) {
            const a = store.annotationStore.addAnnotation({
              userGenerate: true,
            });
            store.annotationStore.selectAnnotation(a.id);
            a.deserializeResults([
              {
                id: 'range',
                from_name: 'events',
                to_name: 'series_view',
                type: 'timeserieslabels',
                value: {
                  start,
                  end,
                  instant: false,
                  timeserieslabels: ['event'],
                },
              },
            ]);
          },
        });
        await window.editor.ready;
        return window.editor.store.annotationStore.selected.serializeAnnotation();
      },
      { config, task, start, end },
    );
    await page.waitForFunction(
      () =>
        window.Htx?.annotationStore.selected.names.get('series_view')?.dataObj
          ?.time?.length === 3,
    );
    const values = await page.evaluate(() => {
      const s = window.Htx.annotationStore.selected.names.get('series_view');
      return {
        time: [...s.dataObj.time],
        channel: [...s.dataObj.channel_1],
        range: [...s.brushRange],
        isDate: s.isDate,
      };
    });
    assert.deepEqual(values.time, input.payload.time.values);
    assert.deepEqual(values.channel, [-5, null, 0]);
    assert.equal(
      restored.find((r) => r.type === 'timeserieslabels').value.start,
      start,
    );
    assert.equal(
      restored.find((r) => r.type === 'timeserieslabels').value.end,
      end,
    );
    await page.evaluate(() => {
      window.PlatformLs.adjustTimeseriesView(window.editor, 'in');
      window.PlatformLs.adjustTimeseriesView(window.editor, 'reset');
    });
    const overview = await page.locator('.htx-timeseries-overview .tick text').allTextContents();
    assert.ok(overview.length > 0);
    assert.ok(!overview.some(label => /^08(?:\s|:)/.test(label)), 'overview must use UTC like the main plot, not browser timezone');
    await fs.mkdir('.qa', { recursive: true });
    await page.screenshot({
      path: `.qa/ls-general-timestamp-${type}.png`,
      fullPage: true,
    });
    await page.evaluate(() => {
      window.editor.destroy();
      window.editor = null;
      window.currentUrls.forEach(URL.revokeObjectURL);
    });
  }
  assert.deepEqual(errors, []);
  console.log(
    JSON.stringify({
      absoluteTimeJson: true,
      absoluteTimeCsv: true,
      timezoneNormalization: true,
      negativeZeroGap: true,
      intervalRoundtrip: true,
      zoom: true,
      errors,
    }),
  );
} catch (e) {
  await fs.mkdir('.qa', { recursive: true });
  await page.screenshot({
    path: '.qa/ls-general-timestamp-failure.png',
    fullPage: true,
  });
  console.error(errors);
  throw e;
} finally {
  await browser.close();
}
