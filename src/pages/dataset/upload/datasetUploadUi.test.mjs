import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DATASET_DIRECTORY_OPTIONS,
  directoryFromBackendType,
  inheritedDatasetIdentity,
  resolveDatasetUploadMetadata,
  VISUAL_FILE_LAYOUT_OPTIONS,
  visualLayoutFromSpecId,
  visualUploadViolation,
} from './datasetUploadUi.mjs';

test('the upload page exposes backend-supported directory categories', () => {
  assert.deepEqual(
    DATASET_DIRECTORY_OPTIONS.map((item) => item.value),
    ['VISUAL', 'TEXT', 'POINT_CLOUD', 'ROBOT', 'MULTIMODAL', 'OTHER'],
  );
  assert.equal(directoryFromBackendType('CV'), 'VISUAL');
  assert.equal(directoryFromBackendType('LEROBOT'), 'ROBOT');
  assert.equal(directoryFromBackendType('OTHER'), 'OTHER');
});

test('visual upload has one unambiguous selector instead of two combinable fields', () => {
  assert.deepEqual(
    VISUAL_FILE_LAYOUT_OPTIONS.map((item) => item.value),
    ['UNLABELED', 'IMAGE_FOLDER', 'YOLO'],
  );
  assert.deepEqual(resolveDatasetUploadMetadata('VISUAL', 'UNLABELED'), {
    type: 'CV',
    cvTaskType: 'UNLABELED',
    annotationFormat: 'NONE',
  });
  assert.deepEqual(resolveDatasetUploadMetadata('VISUAL', 'IMAGE_FOLDER'), {
    type: 'CV',
    cvTaskType: 'IMAGE_CLASSIFICATION',
    annotationFormat: 'FOLDER_CLASSIFICATION',
  });
  assert.deepEqual(resolveDatasetUploadMetadata('VISUAL', 'YOLO'), {
    type: 'CV',
    cvTaskType: 'OBJECT_DETECTION',
    annotationFormat: 'YOLO',
  });
  assert.equal(resolveDatasetUploadMetadata('VISUAL'), undefined);
  assert.equal(
    visualUploadViolation('IMAGE_FOLDER', ['cat.jpg', 'dog.jpg']),
    'ImageFolder 须上传单个 zip',
  );
  assert.equal(
    visualUploadViolation('IMAGE_FOLDER', ['classes.zip']),
    undefined,
  );
  assert.equal(
    visualUploadViolation('YOLO', ['dataset.yaml']),
    'YOLO 须上传单个 zip',
  );
});

test('robot format and inherited evidence resolve conservatively', () => {
  assert.deepEqual(resolveDatasetUploadMetadata('ROBOT', undefined, 'CONFIG'), {
    type: 'ROBOT',
  });
  assert.deepEqual(
    resolveDatasetUploadMetadata('ROBOT', undefined, 'LEROBOT'),
    {
      type: 'LEROBOT',
    },
  );
  assert.deepEqual(resolveDatasetUploadMetadata('OTHER'), { type: 'OTHER' });
  assert.equal(
    visualLayoutFromSpecId('dataset.cv.imagefolder/v1'),
    'IMAGE_FOLDER',
  );
  assert.equal(visualLayoutFromSpecId('unknown'), undefined);
  assert.deepEqual(
    inheritedDatasetIdentity({
      id: 'dataset-1',
      name: 'images',
      type: 'CV',
      latestVersion: { artifactSpecId: 'dataset.cv.yolo/v1' },
    }),
    {
      id: 'dataset-1',
      name: 'images',
      type: 'CV',
      directory: 'VISUAL',
      artifactSpecId: 'dataset.cv.yolo/v1',
    },
  );
});
