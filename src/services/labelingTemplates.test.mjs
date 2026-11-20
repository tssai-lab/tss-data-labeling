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
  const energy = resolveTaskTemplate({
    templateId: 'energy-station-day',
    templateVersion: '1.0.0',
  });
  assert.equal(energy.enabled, true);
  assert.equal(energy.editorKey, 'energy-station-day-editor');
  assert.equal(
    resolveTaskTemplate({
      inputSchemaId: 'image-classification-input@1.0.0',
      resultSchemaId: 'image-classification-result@1.0.0',
    }).id,
    'image-classification',
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

test('plugin registration rejects duplicate IDs and keeps versions isolated', async () => {
  const { createTaskPluginRegistry, taskTemplates } = await import('./labelingTemplates.mjs');
  const image = taskTemplates.find((template) => template.id === 'image-classification');
  assert.throws(() => createTaskPluginRegistry([image, image]));
  assert.throws(() => createTaskPluginRegistry([{ ...image, editorKey: '' }]));
  const registry = createTaskPluginRegistry([image, { ...image, version: '2.0.0' }]);
  assert.equal(registry.resolve({ templateId: image.id, templateVersion: '1.0.0' }).version, '1.0.0');
  assert.throws(() => registry.resolve({ templateId: image.id, templateVersion: '3.0.0' }));
});

test('server availability and installed frontend versions jointly control selectable plugins', async () => {
  const { availableTaskPlugins } = await import('./labelingTemplates.mjs');
  const capabilities = { importer: true, inputReader: true, resultValidator: true, completion: true, exporter: true };
  const registered = { templateId: 'energy-station-day', templateVersion: '1.0.0', taskType: 'TIMESERIES_ANNOTATION', editorKey: 'energy-station-day-editor', enabled: true, capabilities };
  assert.equal(availableTaskPlugins([registered])[0].enabled, true);
  assert.equal(availableTaskPlugins([{ ...registered, capabilities: { ...capabilities, importer: false } }])[0].enabled, false);
  assert.equal(availableTaskPlugins([{ ...registered, enabled: false }])[0].enabled, false);
  assert.equal(availableTaskPlugins([{ ...registered, templateVersion: '2.0.0' }])[0].installed, false);
  assert.equal(availableTaskPlugins([{ ...registered, editorKey: 'https://untrusted.example/editor.js' }])[0].enabled, false);
});
