import assert from 'node:assert/strict';
import test from 'node:test';
import {
  initialClassification,
  classificationFromChoices,
} from './classificationEditor.mjs';
const labels = [
  { id: 'stable-normal', name: '正常' },
  { id: 'none', name: '无目标' },
];
test('only revision=0 may show reference; saved empty draft stays empty, never auto-completes', () => {
  const reference = { result: { labelId: 'stable-normal' } };
  assert.deepEqual(
    initialClassification({ revision: 0, result: {} }, reference, labels),
    { labelId: 'stable-normal', referenceShown: true },
  );
  assert.deepEqual(
    initialClassification({ revision: 2, result: {} }, reference, labels),
    { labelId: '', referenceShown: false },
  );
  assert.deepEqual(
    initialClassification(
      { revision: 1, result: { labelId: 'none' } },
      reference,
      labels,
    ),
    { labelId: 'none', referenceShown: false },
  );
});
test('strict cropped-control conversion accepts one known category only, stable IDs survive display names', () => {
  const valid = {
    from_name: 'category',
    to_name: 'image',
    type: 'choices',
    value: { choices: ['正常'] },
  };
  assert.deepEqual(classificationFromChoices([valid], labels), {
    labelId: 'stable-normal',
  });
  for (const invalid of [
    [],
    [valid, valid],
    [{ ...valid, to_name: 'other' }],
    [{ ...valid, type: 'rectanglelabels' }],
    [{ ...valid, value: { choices: ['正常', '无目标'] } }],
    [{ ...valid, value: { choices: ['unknown'] } }],
  ])
    assert.throws(() => classificationFromChoices(invalid, labels));
});
