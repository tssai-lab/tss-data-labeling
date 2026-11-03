import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildTrainingOutputArtifactItems,
  readHyperParamSummary,
  resolveTrainingPlanDisplayName,
} from './trainingDetailPresentation.mjs';

test('reads camelCase YAML parameter names used by custom plans', () => {
  assert.deepEqual(
    readHyperParamSummary({ epochs: 1, batchSize: 4, lr: 0.00003 }),
    { epochs: 1, batch: 4, lr: 0.00003 },
  );
});

test('keeps supporting legacy hyper parameter names', () => {
  assert.deepEqual(
    readHyperParamSummary({ num_epochs: 2, batch_size: 8, learning_rate: 0.1 }),
    { epochs: 2, batch: 8, lr: 0.1 },
  );
});

test('uses the server training plan display name and falls back to the id', () => {
  const plans = [
    {
      id: 'custom_cv_image_classification',
      displayName: '自定义 CV 图像分类（CPU）',
    },
  ];
  assert.equal(
    resolveTrainingPlanDisplayName('custom_cv_image_classification', plans),
    '自定义 CV 图像分类（CPU）',
  );
  assert.equal(
    resolveTrainingPlanDisplayName('unknown-plan', plans),
    'unknown-plan',
  );
});

test('shows only attested non-model artifacts from structured training output', () => {
  assert.deepEqual(
    buildTrainingOutputArtifactItems({
      artifacts: [
        {
          role: 'PRIMARY_MODEL',
          path: 'model.zip',
          objectName: 'training-results/task/artifacts/model.zip',
        },
        {
          role: 'METRICS',
          path: 'metrics.json',
          objectName: 'training-results/task/artifacts/metrics.json',
        },
        {
          role: 'LOG',
          path: 'train.log',
          objectName: 'training-results/task/artifacts/train.log',
        },
      ],
    }),
    [
      {
        name: 'metrics.json',
        desc: 'minio://training-results/task/artifacts/metrics.json',
        objectName: 'training-results/task/artifacts/metrics.json',
      },
      {
        name: 'train.log',
        desc: 'minio://training-results/task/artifacts/train.log',
        objectName: 'training-results/task/artifacts/train.log',
      },
    ],
  );
});

test('does not invent artifact names when structured output is absent', () => {
  assert.deepEqual(buildTrainingOutputArtifactItems(undefined), []);
});
