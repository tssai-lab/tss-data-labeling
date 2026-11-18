import { test } from 'node:test';
import assert from 'node:assert/strict';
import { importEnergyCsv, parseCsv } from './energyCsv.mjs';
import {
  saveTemplateDraft,
  readTemplateDraft,
} from './lsTemplateWorkspace.mjs';
const header = [
  'task_id',
  'station_key',
  'date_local',
  ...Array.from({ length: 24 }, (_, h) => `h${String(h).padStart(2, '0')}`),
  'daily_kwh',
];
const row = [
  'TASK1',
  'ST1',
  '2026-10-05',
  ...Array.from({ length: 24 }, (_, i) => String(i)),
  '999',
];
const csv = (r = row) => [header.join(','), r.join(',')].join('\r\n');
test('CSV quoting, zero, independent daily value and deterministic input identity', async () => {
  assert.deepEqual(parseCsv('\uFEFFa,b\r\n"x,y","line\nnext"'), [
    ['a', 'b'],
    ['x,y', 'line\nnext'],
  ]);
  const a = await importEnergyCsv(csv()),
    b = await importEnergyCsv(csv());
  const s = a.samples[0];
  assert.equal(s.input.payload.series.values.length, 24);
  assert.equal(s.input.payload.series.values[0], 0);
  assert.equal(s.input.payload.dailyKwh, 999);
  assert.equal(s.source.taskId, 'TASK1');
  assert.equal(s.source.row, 2);
  assert.equal(s.input.inputHash, b.samples[0].input.inputHash);
  const changed = [...row];
  changed[4] = '30';
  assert.notEqual(
    (await importEnergyCsv(csv(changed))).samples[0].input.inputHash,
    s.input.inputHash,
  );
});
test('CSV rejects missing columns, duplicate days, negative numbers and bad dates; missing is not zero', async () => {
  await assert.rejects(importEnergyCsv('task_id\na'), /缺少列/);
  await assert.rejects(importEnergyCsv(`${csv()}\r\n${row.join(',')}`), /重复/);
  const negative = [...row];
  negative[3] = '-1';
  await assert.rejects(importEnergyCsv(csv(negative)), /非负/);
  const missing = [...row];
  missing[3] = '';
  const a = await importEnergyCsv(csv(missing));
  assert.equal(a.samples[0].input.payload.series.values[0], null);
  const bad = [...row];
  bad[2] = '2026-02-30';
  await assert.rejects(importEnergyCsv(csv(bad)), /日期|24个/);
});
test('drafts bind scope and backend-issued template version without a frontend catalog', () => {
  const map = new Map(),
    storage = {
      setItem: (k, v) => map.set(k, v),
      getItem: (k) => map.get(k) || null,
    };
  const draft = {
    templateId: 'ls:computer-vision/image-classification',
    templateVersion: '1.23.2:0123456789abcdef',
    config: '<View><Text name="text" value="$text"/></View>',
    instructions: '说明',
  };
  saveTemplateDraft(storage, 'A', draft);
  assert.equal(readTemplateDraft(storage, 'A').instructions, '说明');
  assert.equal(readTemplateDraft(storage, 'B'), null);
  const k = [...map.keys()][0],
    d = JSON.parse(map.get(k));
  d.templateVersion = '99';
  map.set(k, JSON.stringify(d));
  assert.throws(() => readTemplateDraft(storage, 'A'), /版本/);
  assert.throws(
    () =>
      saveTemplateDraft(
        {
          setItem: () => {
            throw Error('quota');
          },
        },
        'A',
        draft,
      ),
    /quota/,
  );
});
