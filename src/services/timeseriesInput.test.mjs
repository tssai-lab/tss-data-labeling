import test from 'node:test';
import assert from 'node:assert/strict';
import {
  importTimeseriesCsv,
  createGeneralTimeseriesTask,
  createGeneralTimeseriesConfig,
  validateTimeseriesInput,
} from './timeseriesInput.mjs';
import { createPlatformTimeseriesTask } from './labelStudioTimeseries.mjs';
import { validateCompletion } from './annotationCompletion.mjs';

test('grouped irregular data preserves negative values, zero, gaps and independent channel units', async () => {
  const text =
    'id,time,温度,pressure\na,0,-5,0\na,0.025,,2\nb,10,0,1\nb,12,-1,2\na,0.1,2,4';
  const options = {
    timeColumn: 'time',
    groupColumn: 'id',
    channelColumns: ['温度', 'pressure'],
    timeUnit: 's',
    units: { 温度: '℃', pressure: 'kPa' },
  };
  const { samples } = await importTimeseriesCsv(text, options);
  assert.equal(samples.length, 2);
  const input = samples[0].input;
  assert.deepEqual(input.payload.time.values, [0, 0.025, 0.1]);
  assert.deepEqual(input.payload.channels[0].values, [-5, null, 2]);
  assert.deepEqual(input.payload.channels[1].values, [0, 2, 4]);
  assert.equal(input.payload.channels[0].unit, '℃');
  const task = createPlatformTimeseriesTask(input);
  assert.deepEqual(task.data.series.channel_1, [-5, null, 2]);
  task.data.series.channel_1[0] = 999;
  assert.equal(input.payload.channels[0].values[0], -5);
  const again = await importTimeseriesCsv(text, options);
  assert.equal(again.samples[0].id, samples[0].id);
  assert.deepEqual(samples[0].source.rows, [2, 3, 6]);
  const config = createGeneralTimeseriesConfig(input);
  assert.match(config, /units="℃"/);
  assert.doesNotMatch(config, /value_kwh|kWh/);
});
test('ISO timestamps require explicit timezone and normalize to epoch milliseconds', async () => {
  const { samples } = await importTimeseriesCsv(
    'timestamp,v\n2026-11-14T08:00:00+08:00,1\n2026-11-14T00:01:00Z,2',
    { timeColumn: 'timestamp', channelColumns: ['v'], kind: 'timestamp' },
  );
  const input = samples[0].input;
  assert.equal(
    input.payload.time.values[0],
    Date.parse('2026-11-14T00:00:00Z'),
  );
  assert.equal(
    input.payload.time.values[1] - input.payload.time.values[0],
    60000,
  );
  assert.match(createGeneralTimeseriesConfig(input), /timeFormat="%Q"/);
  assert.equal(
    createGeneralTimeseriesTask(input).platformTimeseries.kind,
    'timestamp',
  );
  for (const date of [
    '2026-11-14T08:00:00',
    '2026-02-30T00:00:00Z',
    '2026-11-14T24:00:00Z',
  ])
    await assert.rejects(
      importTimeseriesCsv(`t,v\n${date},1\n2026-11-15T01:00:00Z,2`, {
        timeColumn: 't',
        channelColumns: ['v'],
        kind: 'timestamp',
      }),
    );
});
test('duplicates, unordered rows, mapping collisions and invalid channel values are rejected', async () => {
  for (const text of [
    't,v\n0,1\n0,2',
    't,v\n1,1\n0,2',
    't,v\n0,1\n1,NaN',
    't,v\n0,1',
  ])
    await assert.rejects(
      importTimeseriesCsv(text, { timeColumn: 't', channelColumns: ['v'] }),
    );
  await assert.rejects(
    importTimeseriesCsv('t,v\n0,1\n1,2', {
      timeColumn: 't',
      channelColumns: ['t'],
    }),
    /映射/,
  );
  const { samples } = await importTimeseriesCsv('t,v\n0,1\n1,2', {
    timeColumn: 't',
    channelColumns: ['v'],
  });
  const bad = structuredClone(samples[0].input);
  bad.payload.channels[0].values.push(3);
  assert.throws(() => validateTimeseriesInput(bad), /点数/);
});
test('general result validation uses actual fractional time range instead of energy day bounds', async () => {
  const { samples } = await importTimeseriesCsv('t,v\n0,1\n0.01,2\n0.1,3', {
    timeColumn: 't',
    channelColumns: ['v'],
  });
  const r = {
    from_name: 'event',
    to_name: 'series_view',
    type: 'timeserieslabels',
    value: {
      start: 0.01,
      end: 0.1,
      instant: false,
      timeserieslabels: ['event'],
    },
  };
  assert.equal(
    validateCompletion([r], undefined, { input: samples[0].input }),
    true,
  );
  assert.throws(
    () =>
      validateCompletion(
        [{ ...r, value: { ...r.value, end: 0.2 } }],
        undefined,
        { input: samples[0].input },
      ),
    /实际时间范围/,
  );
});
