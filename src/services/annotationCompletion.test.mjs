import test from 'node:test';
import assert from 'node:assert/strict';
import { validateCompletion } from './annotationCompletion.mjs';
const choice = {
  from_name: 'class',
  to_name: 'image',
  type: 'choices',
  value: { choices: ['normal'] },
};
test('completion distinguishes draft, real answer, empty result and explicit negative', () => {
  assert.throws(() => validateCompletion([]), /不允许空/);
  assert.throws(() => validateCompletion([], { allowEmpty: true }), /填写原因/);
  assert.equal(
    validateCompletion(
      [],
      { allowEmpty: true },
      { emptyReason: '检查后无目标' },
    ),
    true,
  );
  assert.throws(
    () =>
      validateCompletion(
        [],
        { allowEmpty: true, required: [{ name: 'class' }] },
        { emptyReason: '没有目标' },
      ),
    /必填/,
  );
  assert.equal(
    validateCompletion([choice], { required: [{ name: 'class' }] }),
    true,
  );
  assert.throws(
    () => validateCompletion([{ ...choice, value: { choices: [] } }]),
    /不允许空/,
  );
});
test('numeric series bounds preserve final hour and reject invented 24th sampling point', () => {
  const result = {
      from_name: 'events',
      to_name: 'series',
      type: 'timeserieslabels',
      value: { start: 23, end: 23, instant: true, timeserieslabels: ['spike'] },
    },
    input = { inputFormat: 'energy-station-day-input-v1' };
  assert.equal(validateCompletion([result], undefined, { input }), true);
  for (const start of [-1, 24, NaN])
    assert.throws(
      () =>
        validateCompletion(
          [{ ...result, value: { ...result.value, start } }],
          undefined,
          { input },
        ),
      /范围/,
    );
  assert.throws(
    () =>
      validateCompletion(
        [{ ...result, value: { ...result.value, start: 2, end: 1 } }],
        undefined,
        { input },
      ),
    /范围/,
  );
});
test('invalid image geometry and malformed answers cannot complete', () => {
  assert.throws(() => validateCompletion([{ old: true }]), /结构/);
  const r = {
    from_name: 'box',
    to_name: 'image',
    type: 'rectanglelabels',
    value: { x: 10, y: 20, width: 0, height: 20, rectanglelabels: ['car'] },
  };
  assert.throws(() => validateCompletion([r]), /宽高/);
  assert.throws(
    () => validateCompletion([{ ...r, value: { ...r.value, x: 101 } }]),
    /坐标/,
  );
});

test('formatted time axis compares calendar labels independently of workstation timezone', () => {
  const r = {
    from_name: 'event',
    to_name: 'series',
    type: 'timeserieslabels',
    value: {
      start: '2026-10-05 23:00:00.000000',
      end: '2026-10-05 23:00:00.000000',
      timeserieslabels: ['spike'],
    },
  };
  const context = {
    input: {
      inputFormat: 'energy-station-day-input-v1',
      payload: { dateLocal: '2026-10-05' },
    },
  };
  assert.equal(validateCompletion([r], undefined, context), true);
  assert.throws(
    () =>
      validateCompletion(
        [{ ...r, value: { ...r.value, end: '2026-10-06 00:00:00.000000' } }],
        undefined,
        context,
      ),
    /范围/,
  );
});
