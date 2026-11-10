import assert from 'node:assert/strict';
import test from 'node:test';
import {
  decodeEnergyResult,
  emptyEnergyValue,
  encodeEnergyResult,
  energySummary,
  hourTimestamp,
  validateEnergyInput,
  validateEnergyResult,
} from './labelingEnergy.mjs';
import { contentHash, createEnergyDemo } from './labelingEnergyDemo.mjs';

const setup = () => createEnergyDemo({ enabled: true, production: false });
test('energy input preserves 24 hourly kWh buckets, independent daily value, zeros and explicit gaps', async () => {
  const workspace = await setup();
  assert.equal(workspace.inputs.length, 10);
  const [zero, gap] = workspace.inputs;
  assert.equal(zero.inputHash, await contentHash(zero.payload));
  assert.deepEqual(energySummary(zero), {
    missing: 0,
    knownSum: 0,
    completeSum: 0,
    difference: 0,
  });
  assert.equal(energySummary(gap).completeSum, null);
  assert.equal(energySummary(gap).missing, 1);
  for (const change of [
    (v) => v.payload.series.values.pop(),
    (v) => {
      v.payload.series.values[0] = -1;
    },
    (v) => {
      v.payload.series.values[0] = NaN;
    },
    (v) => {
      v.payload.timezone = 'UTC';
    },
    (v) => {
      v.payload.series.unit = 'kW';
    },
    (v) => {
      v.payload.series.start = '2026-01-02T00:00:00Z';
    },
    (v) => {
      v.payload.dateLocal = '2026-02-31';
    },
  ]) {
    const copy = structuredClone(zero);
    change(copy);
    assert.throws(() => validateEnergyInput(copy));
  }
  assert.equal(hourTimestamp(zero, 24), '2026-01-03T00:00:00+08:00');
});
test('typed annotations roundtrip primary, secondary, point, half-open interval and human certainty without AI confidence', async () => {
  const input = (await setup()).inputs[0];
  const value = {
    ...emptyEnergyValue(),
    primary: 'spike',
    secondary: ['high_load'],
    notes: '合成定位依据',
    certainty: 'UNKNOWN',
    points: [{ id: 'point-1', hour: 14, labelId: 'spike' }],
    intervals: [
      { id: 'interval-1', startHour: 22, endHour: 24, labelId: 'partial_zero' },
    ],
  };
  const result = encodeEnergyResult(value, input);
  assert.deepEqual(decodeEnergyResult(result, input), value);
  assert.equal(validateEnergyResult(result, input, true), true);
  assert.equal(result.confidence, undefined);
  assert.equal(
    result.annotations.find((a) => a.type === 'timeseries_interval').value.end,
    '2026-01-03T00:00:00+08:00',
  );
  for (const change of [
    (r) => {
      r.extra = 'drop me';
    },
    (r) => {
      r.schemaVersion = '9';
    },
    (r) => {
      r.annotations[0].value.labelIds = ['invented'];
    },
    (r) => {
      r.annotations[0].to_name = 'another';
    },
    (r) => {
      r.annotations.push(r.annotations[0]);
    },
    (r) => {
      r.annotations.find((a) => a.type === 'timeseries_point').value.timestamp =
        '2026-01-02T14:30:00+08:00';
    },
    (r) => {
      r.annotations.find((a) => a.type === 'timeseries_interval').value.end =
        '2026-01-02T21:00:00+08:00';
    },
  ]) {
    const copy = structuredClone(result);
    change(copy);
    assert.throws(() => validateEnergyResult(copy, input));
  }
});
test('structural validity and completion differ; unknown human certainty is allowed, missing reasons cannot masquerade as done', async () => {
  const input = (await setup()).inputs[0];
  const empty = encodeEnergyResult(emptyEnergyValue(), input);
  assert.equal(validateEnergyResult(empty, input), true);
  assert.throws(() => validateEnergyResult(empty, input, true));
  const abnormal = encodeEnergyResult(
    { ...emptyEnergyValue(), primary: 'idle_zero', certainty: 'UNKNOWN' },
    input,
  );
  assert.throws(() => validateEnergyResult(abnormal, input, true));
  const whole = encodeEnergyResult(
    {
      ...emptyEnergyValue(),
      primary: 'idle_zero',
      certainty: 'UNKNOWN',
      notes: '试标依据',
      wholeDayReason: '整日疑似，等待owner核查',
    },
    input,
  );
  assert.equal(validateEnergyResult(whole, input, true), true);
});
test('synthetic energy isolation, CAS, saved empty draft, refresh, 9/10 refusal and 10/10 independent handback', async () => {
  await assert.rejects(() => createEnergyDemo(), /MOCK_DISABLED/);
  await assert.rejects(
    () => createEnergyDemo({ enabled: true, production: true }),
    /MOCK_DISABLED/,
  );
  const w = await setup();
  assert.throws(() => w.draft('real-sample-id'));
  for (let i = 0; i < 9; i++) {
    const input = w.inputs[i];
    w.save(input.sampleId, input.inputHash, {
      baseRevision: 0,
      explicitDone: true,
      resultFormat: 'annotation-record-v1',
      result: encodeEnergyResult(
        { ...emptyEnergyValue(), primary: 'normal', certainty: 'UNKNOWN' },
        input,
      ),
    });
  }
  const manifest = () =>
    w.inputs.map((i) => ({
      sampleId: i.sampleId,
      revision: w.draft(i.sampleId).revision,
    }));
  assert.throws(() => w.submit(manifest()), /INCOMPLETE_PACKAGE/);
  assert.equal(w.snapshot().results.length, 0);
  const last = w.inputs[9];
  const saved = {
    baseRevision: 0,
    explicitDone: false,
    resultFormat: 'annotation-record-v1',
    result: encodeEnergyResult(emptyEnergyValue(), last),
  };
  assert.throws(
    () => w.save(last.sampleId, 'changed-input', saved),
    /INPUT_VERSION_CONFLICT/,
  );
  w.save(last.sampleId, last.inputHash, saved);
  const restored = await createEnergyDemo({
    enabled: true,
    production: false,
    serialized: w.serialize(),
  });
  assert.equal(restored.draft(last.sampleId).revision, 1);
  assert.deepEqual(restored.draft(last.sampleId).result.annotations, []);
  assert.throws(
    () => w.save(last.sampleId, last.inputHash, saved),
    /REVISION_CONFLICT/,
  );
  w.save(last.sampleId, last.inputHash, {
    ...saved,
    baseRevision: 1,
    explicitDone: true,
    result: encodeEnergyResult(
      { ...emptyEnergyValue(), primary: 'normal', certainty: 'UNKNOWN' },
      last,
    ),
  });
  w.submit(manifest());
  assert.equal(w.snapshot().results.length, 10);
  assert.equal(Object.keys(w.snapshot().accepted).length, 0);
  assert.throws(
    () => w.save(last.sampleId, last.inputHash, saved),
    /ASSIGNMENT_CLOSED/,
  );
  assert.throws(() => w.finalize(), /FINAL_RESULTS_INCOMPLETE/);
});
test('owner revision uses owner CAS, preserves old result; confirmed final JSONL/CSV and hashes are immutable', async () => {
  const w = await setup();
  for (const input of w.inputs)
    w.save(input.sampleId, input.inputHash, {
      baseRevision: 0,
      explicitDone: true,
      resultFormat: 'annotation-record-v1',
      result: encodeEnergyResult(
        { ...emptyEnergyValue(), primary: 'normal', certainty: 'HIGH' },
        input,
      ),
    });
  w.submit(w.inputs.map((i) => ({ sampleId: i.sampleId, revision: 1 })));
  const reviews = () => {
    const s = w.snapshot();
    return w.inputs.map((i) => ({
      sampleId: i.sampleId,
      resultId: s.current[i.sampleId],
      reviewRevision: s.reviewRevisions[i.sampleId],
    }));
  };
  w.review(reviews());
  const final = w.finalize();
  const files = await w.export(final.finalSnapshotId);
  assert.equal(files['annotations.jsonl'].trim().split('\n').length, 10);
  const manifest = JSON.parse(files['manifest.json']);
  for (const item of manifest.files)
    assert.equal(item.sha256, await contentHash(files[item.name]));
  const first = w.inputs[0];
  const original = w.snapshot().current[first.sampleId];
  const revised = w.revise(
    first.sampleId,
    1,
    encodeEnergyResult(
      {
        ...emptyEnergyValue(),
        primary: 'holiday',
        notes: '合成专家复核说明',
        wholeDayReason: '整日疑似待核查',
        certainty: 'UNKNOWN',
      },
      first,
    ),
    '人工修订',
  );
  assert.equal(revised.sourceKind, 'OWNER');
  assert.equal(revised.parentResultId, original);
  assert.equal(revised.confidence, null);
  assert.throws(() => w.finalize(), /FINAL_RESULTS_INCOMPLETE/);
  assert.equal(
    (await w.export(final.finalSnapshotId))['annotations.jsonl'],
    files['annotations.jsonl'],
  );
  assert.throws(
    () => w.review(reviews().map((i) => ({ ...i, reviewRevision: 0 }))),
    /REVIEW_CONFLICT/,
  );
});
