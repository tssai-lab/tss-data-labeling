import assert from 'node:assert/strict';
import test from 'node:test';
import {
  assertClassificationWork,
  resolveTaskTemplate,
  taskTemplates,
} from './labelingTemplates.mjs';

test('legacy classification remains compatible; unknown versions and bindings never fall through to image editor', () => {
  assert.equal(
    resolveTaskTemplate({ taskType: 'IMAGE_CLASSIFICATION' }).resultFormat,
    'image-classification-v1',
  );
  for (const binding of [
    { taskType: 'TIMESERIES_ANNOTATION' },
    { templateId: 'image-classification', templateVersion: '9.0.0' },
    { editorKey: 'https://evil.invalid/plugin.js' },
    { resultFormat: 'annotation-record-v1' },
  ])
    assert.throws(() => resolveTaskTemplate(binding));
  assert.throws(
    () =>
      resolveTaskTemplate({
        templateId: 'energy-station-day',
        templateVersion: '1.0.0',
      }),
    (e) => e.code === 'TEMPLATE_UNSUPPORTED',
  );
  assert.equal(
    resolveTaskTemplate(
      { templateId: 'energy-station-day', templateVersion: '1.0.0' },
      { preview: true },
    ).enabled,
    false,
  );
  assert.ok(Object.isFrozen(taskTemplates));
});
test('assignment and individual sample metadata are checked before draft or image access', () => {
  assert.equal(
    assertClassificationWork({
      specification: {},
      items: [{ resultFormat: 'image-classification-v1' }],
    }).id,
    'image-classification',
  );
  assert.throws(() =>
    assertClassificationWork({
      specification: {},
      items: [{ templateId: 'image-classification', templateVersion: '2.0.0' }],
    }),
  );
  assert.throws(() =>
    assertClassificationWork({
      specification: {},
      taskType: 'TIMESERIES_ANNOTATION',
    }),
  );
  assert.throws(() =>
    assertClassificationWork({
      specification: {},
      items: [{ inputFormat: 'energy-station-day-input-v1' }],
    }),
  );
});
