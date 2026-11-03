import assert from 'node:assert/strict';
import test from 'node:test';
import {
  filterDatasetCandidates,
  filterModelCandidates,
  isSpecDrivenInput,
} from './trainingAssetCompatibility.mjs';

test('v2 candidates require READY state and an exact server specification', () => {
  const input = { acceptedSpecIds: ['model.cv.hf-image/v1'] };
  const candidates = filterModelCandidates(
    [
      { id: 'ok', status: 'READY', artifactSpecId: 'model.cv.hf-image/v1' },
      {
        id: 'wrong',
        status: 'READY',
        artifactSpecId: 'model.cv.yolo-weight/v1',
      },
      { id: 'unknown', status: 'READY' },
      { id: 'draft', status: 'DRAFT', artifactSpecId: 'model.cv.hf-image/v1' },
    ],
    input,
  );
  assert.equal(isSpecDrivenInput(input), true);
  assert.deepEqual(
    candidates.map((item) => item.id),
    ['ok'],
  );
});

test('v2 dataset candidates never fall back to symbolic directory categories', () => {
  const candidates = filterDatasetCandidates(
    [
      {
        id: 'ok',
        type: 'CV',
        versionId: 'v1',
        versionStatus: 'READY',
        artifactSpecId: 'dataset.cv.imagefolder/v1',
      },
      {
        id: 'other',
        type: 'OTHER',
        versionId: 'v2',
        versionStatus: 'READY',
      },
    ],
    { acceptedSpecIds: ['dataset.cv.imagefolder/v1'] },
  );
  assert.deepEqual(
    candidates.map((item) => item.id),
    ['ok'],
  );
});

test('legacy plans keep task type filtering but still hide non-ready versions', () => {
  const candidates = filterModelCandidates(
    [
      { id: 'cv-ready', type: 'CV', status: 'READY' },
      { id: 'nlp-ready', type: 'NLP', status: 'READY' },
      { id: 'cv-draft', type: 'CV', status: 'DRAFT' },
    ],
    { taskTypes: ['CV'] },
  );
  assert.equal(isSpecDrivenInput({ taskTypes: ['CV'] }), false);
  assert.deepEqual(
    candidates.map((item) => item.id),
    ['cv-ready'],
  );
  assert.deepEqual(filterModelCandidates(candidates, undefined), []);
});
