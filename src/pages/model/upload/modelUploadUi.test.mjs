import assert from 'node:assert/strict';
import test from 'node:test';
import {
  inheritedModelIdentity,
  isModelUploadCategory,
  MODEL_UPLOAD_CATEGORY_OPTIONS,
} from './modelUploadUi.mjs';

test('new model uploads expose backend taskType categories', () => {
  assert.deepEqual(
    MODEL_UPLOAD_CATEGORY_OPTIONS.map((item) => item.value),
    ['CV', 'NLP', 'POINT_CLOUD', 'ROBOT', 'OTHER'],
  );
  assert.equal(isModelUploadCategory('cv'), true);
  assert.equal(isModelUploadCategory('POINT_CLOUD'), true);
  assert.equal(isModelUploadCategory('ROBOT'), true);
  assert.equal(isModelUploadCategory('other'), true);
});

test('new versions inherit identity and verified specification from the asset', () => {
  assert.deepEqual(
    inheritedModelIdentity({
      id: 'asset-1',
      name: 'existing-model',
      type: 'CV',
      remark: 'asset remark',
      latestVersion: { artifactSpecId: 'model.cv.hf-image/v1' },
    }),
    {
      id: 'asset-1',
      name: 'existing-model',
      type: 'CV',
      remark: 'asset remark',
      artifactSpecId: 'model.cv.hf-image/v1',
    },
  );
  assert.equal(inheritedModelIdentity({ id: 'asset-1' }), undefined);
});
