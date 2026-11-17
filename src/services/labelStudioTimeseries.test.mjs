import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createLsTimeseriesConfig,
  createLsTimeseriesTask,
} from './labelStudioTimeseries.mjs';

const input = () => ({
  sampleId: 'sample-1',
  inputFormat: 'energy-station-day-input-v1',
  schemaId: 'energy-station-day-input@1.0.0',
  inputHash: 'fixture-hash',
  payload: {
    stationKey: 'ST-0001',
    dateLocal: '2026-10-05',
    timezone: 'Asia/Shanghai',
    dailyKwh: 42,
    series: {
      name: 'load_curve',
      channel: 'value_kwh',
      unit: 'kWh',
      start: '2026-10-05T00:00:00+08:00',
      periodSeconds: 3600,
      values: Array.from({ length: 24 }, (_, h) => (h === 0 ? 0 : h / 10)),
    },
  },
});

test('project labels use stable serialized aliases and XML-escaped display text', () => {
  const config = createLsTimeseriesConfig([
    { id: 'normal', name: '正常 & "待查" <检查>' },
  ]);
  assert.match(
    config,
    /<Choice value="正常 &amp; &quot;待查&quot; &lt;检查&gt;" alias="normal"/,
  );
  assert.match(
    config,
    /<Label value="正常 &amp; &quot;待查&quot; &lt;检查&gt;" alias="normal"/,
  );
  assert.match(config, /value="\$series" valueType="json"/);
  assert.doesNotMatch(config, /https?:|<script/);
});

test('rejects ambiguous labels rather than silently mapping answers to another ID', () => {
  for (const labels of [
    [],
    [{ id: 'bad id', name: 'a' }],
    [
      { id: 'a', name: 'x' },
      { id: 'a', name: 'y' },
    ],
    [
      { id: 'a', name: 'x' },
      { id: 'b', name: 'x' },
    ],
  ]) {
    assert.throws(
      () => createLsTimeseriesConfig(labels),
      /LS_TIMESERIES_CONTRACT/,
    );
  }
});

test('inline task preserves zero, all 24 buckets, daily value and input identity without mutation', () => {
  const source = input();
  const task = createLsTimeseriesTask(source);
  assert.equal(task.id, source.sampleId);
  assert.deepEqual(
    task.data.series.hour,
    Array.from({ length: 24 }, (_, h) => h),
  );
  assert.deepEqual(task.data.series.value_kwh, source.payload.series.values);
  assert.equal(task.data.series.value_kwh.length, 24);
  assert.equal(task.data.daily_kwh, 42);
  assert.equal(task.data.input_hash, source.inputHash);
  assert.deepEqual(task.predictions, []);
  task.data.series.value_kwh[0] = 100;
  assert.equal(source.payload.series.values[0], 0);
});

test('gaps remain null instead of becoming false zero readings', () => {
  const source = input();
  source.payload.series.values[2] = null;
  source.payload.qualityReasons = ['missing-hour'];
  const task = createLsTimeseriesTask(source);
  assert.equal(task.data.series.value_kwh[2], null);
  assert.equal(task.data.series.value_kwh[0], 0);
});

test('rejects inconsistent timezone, missing identity and invalid units before renderer loading', () => {
  const source = input();
  source.payload.timezone = 'UTC';
  assert.throws(() => createLsTimeseriesTask(source));
  source.payload.timezone = 'Asia/Shanghai';
  source.sampleId = '';
  assert.throws(() => createLsTimeseriesTask(source), /sampleId/);
  source.sampleId = 'sample-1';
  source.payload.series.unit = 'kW';
  assert.throws(() => createLsTimeseriesTask(source));
});
