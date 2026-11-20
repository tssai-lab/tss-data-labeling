import assert from 'node:assert/strict';
import test from 'node:test';
import {
  normalizeWorkspaceProject,
  isLegacyProjectSpec,
  hasProjectAnswer,
  projectPanel,
  legacyProjectSpecRequest,
} from './projectCompatibility.mjs';

test('historical projects enter the workspace without changing frozen formats or label IDs', () => {
  const old = {
    projectId: 'p1',
    taskType: 'IMAGE_CLASSIFICATION',
    specVersions: [
      {
        specVersionId: 'v1',
        schemaVersion: 'image-classification-v1',
        labels: [{ id: 'stable-id', name: '正常' }],
        editorConfig: null,
      },
    ],
  };
  const before = JSON.stringify(old);
  const project = normalizeWorkspaceProject(old);
  assert.equal(JSON.stringify(old), before);
  assert.equal(
    project.specVersions[0].schemaVersion,
    'image-classification-v1',
  );
  assert.equal(project.specVersions[0].labels[0].id, 'stable-id');
  assert.deepEqual(project.specVersions[0].editorConfig, {});
  assert.equal(
    isLegacyProjectSpec(project.specVersions[0], project.taskType),
    true,
  );
  assert.equal(
    isLegacyProjectSpec(
      { schemaVersion: 'label-studio-v1' },
      'IMAGE_CLASSIFICATION',
    ),
    false,
  );
  assert.equal(
    isLegacyProjectSpec(
      { schemaVersion: 'annotation-record-v1' },
      'TIMESERIES_ANNOTATION',
    ),
    true,
  );
  assert.deepEqual(
    normalizeWorkspaceProject({ projectId: 'empty' }).specVersions,
    [],
  );
});

test('old settings and workbench links map to the unified steps', () => {
  const panel = (path, search) =>
    projectPanel(path, new URLSearchParams(search));
  assert.equal(panel('/projects/p', 'panel=publish&step=spec'), 'settings');
  assert.equal(panel('/projects/p', 'panel=publish&step=data'), 'data');
  assert.equal(panel('/projects/p', 'panel=publish&step=publish'), 'publish');
  assert.equal(panel('/projects/p', 'panel=settings'), 'settings');
  assert.equal(panel('/projects/p', ''), 'data');
  assert.equal(panel('/projects/p/workbench', 'view=export'), 'outcome');
  assert.equal(panel('/projects/p/workbench', 'view=publish'), 'publish');
  assert.equal(panel('/projects/p/workbench', 'view=sources'), 'review');
});

test('classification, energy and native results remain visible to review filters', () => {
  for (const value of [
    { labelId: 'stable-id' },
    { annotations: [{ type: 'choices' }] },
    { result: [{ type: 'choices' }] },
    { emptyReason: '无目标' },
  ])
    assert.equal(hasProjectAnswer(value), true);
  for (const value of [
    undefined,
    null,
    {},
    { annotations: [] },
    { result: [] },
  ])
    assert.equal(hasProjectAnswer(value), false);
});

test('editing legacy rules creates a compatible version and preserves old snapshots', () => {
  const old = {
    schemaVersion: 'image-classification-v1',
    templateId: 'image-classification',
    templateVersion: '1.0.0',
    editorConfig: {},
    labels: [{ id: 'stable-id', name: '正常' }],
  };
  const request = legacyProjectSpecRequest(
    old,
    'IMAGE_CLASSIFICATION',
    [{ id: 'stable-id', name: ' 正常样本 ' }],
    '新说明',
  );
  assert.equal(request.schemaVersion, old.schemaVersion);
  assert.deepEqual(request.labels, [{ id: 'stable-id', name: '正常样本' }]);
  assert.equal(old.labels[0].name, '正常');
  assert.equal(request.templateId, old.templateId);
  assert.throws(() =>
    legacyProjectSpecRequest(old, 'IMAGE_CLASSIFICATION', [], ''),
  );
  assert.throws(() =>
    legacyProjectSpecRequest(
      old,
      'IMAGE_CLASSIFICATION',
      [
        { id: 'a', name: '正常' },
        { id: 'b', name: ' 正常 ' },
      ],
      '',
    ),
  );
});
