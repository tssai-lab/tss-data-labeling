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

test('shared editor configuration escapes label names and restores stable IDs', async () => {
  const { classificationLabelConfig, classificationToChoices } = await import('./classificationEditor.mjs');
  const escaped = [{ id: 'stable-id', name: '正常 & "可用" <A>' }];
  const config = classificationLabelConfig(escaped);
  assert.match(config, /<Image name="image" value="\$image"/);
  assert.match(config, /zoomControl="true"/);
  assert.match(config, /name="category" toName="image" choice="single-radio"/);
  assert.match(config, /正常 &amp; &quot;可用&quot; &lt;A&gt;/);
  assert.deepEqual(classificationFromChoices(classificationToChoices('stable-id', escaped), escaped), { labelId: 'stable-id' });
  assert.deepEqual(classificationToChoices('', escaped), []);
  assert.throws(() => classificationToChoices('removed-label', escaped));
  assert.throws(() => classificationLabelConfig([]));
  assert.throws(() => classificationLabelConfig([{ id: 'a', name: '同名' }, { id: 'b', name: '同名' }]));
});

test('saving a shared-editor draft never implicitly completes a sample', async () => {
  const { classificationToChoices, classificationSaveInput } = await import('./classificationEditor.mjs');
  const raw = classificationToChoices('stable-normal', labels);
  const draft = classificationSaveInput(raw, labels, 4, false);
  assert.deepEqual(draft, { baseRevision: 4, explicitDone: false, resultFormat: 'image-classification-v1', result: { labelId: 'stable-normal' } });
  assert.equal(classificationSaveInput(raw, labels, 4, true).explicitDone, true);
  assert.deepEqual(classificationSaveInput([], labels, 4, false).result, {});
  assert.throws(() => classificationSaveInput([], labels, 4, true));
  assert.throws(() => classificationSaveInput([{ from_name: 'category', to_name: 'image', type: 'choices', value: { choices: ['不在规范中'] } }], labels, 4, false));
});
