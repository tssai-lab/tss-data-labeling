import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildInferenceInputPresentation,
  fileNameFromObjectName,
  previewKindFromObjectName,
} from './inferenceInputPresentation.mjs';

test('single object only exposes a friendly file name and an explicit preview kind', () => {
  const presentation = buildInferenceInputPresentation({
    inputMode: 'SINGLE_OBJECT',
    inputObjectName: 'users/7/files/inference-inputs/1-CAT.JPG',
  });

  assert.deepEqual(presentation, {
    kind: 'object',
    identifier: 'users/7/files/inference-inputs/1-CAT.JPG',
    displayName: '1-CAT.JPG',
    previewKind: 'image',
  });
});

test('dataset mode never treats a dataset version as an object to download', () => {
  const presentation = buildInferenceInputPresentation({
    inputMode: 'DATASET_VERSION',
    datasetVersionId: 'dataset-version-1',
    datasetDisplayName: '新闻分类 / v1',
    inputObjectName: 'must-not-be-used.txt',
  });

  assert.deepEqual(presentation, {
    kind: 'dataset',
    identifier: 'dataset-version-1',
    displayName: '新闻分类 / v1',
  });
});

test('text formats used by CV and NLP assets are rendered as escaped text', () => {
  for (const path of ['sample.JSONL', 'labels.xml', 'config.yaml']) {
    assert.equal(previewKindFromObjectName(path), 'text');
  }
});

test('unknown binaries are download-only and path separators are normalized', () => {
  assert.equal(previewKindFromObjectName('weights/model.bin'), 'unsupported');
  assert.equal(previewKindFromObjectName('cloud.pcd'), 'unsupported');
  assert.equal(fileNameFromObjectName('users\\7\\files\\input.bin'), 'input.bin');
  assert.equal(fileNameFromObjectName(undefined), '');
});
