import assert from 'node:assert/strict';
import test from 'node:test';

import {
  firstTrainingResourceProfileId,
  formatTrainingResourceProfileLabel,
  isTrainingResourceProfileIdAllowed,
  listTrainingResourceProfiles,
} from './resourceProfilePresentation.mjs';

test('exposes valid CPU and GPU profiles from one training plan', () => {
  const plan = {
    runtimes: [
      {
        id: 'gpu-runtime',
        deviceType: 'NVIDIA_GPU',
        resourceProfiles: [
          {
            id: 'gpu-1',
            gpuCount: 1,
            cpuLimit: '8',
            memoryLimit: '16Gi',
          },
          { id: 'invalid-gpu-zero', gpuCount: 0 },
        ],
      },
      {
        id: 'cpu-runtime',
        deviceType: 'CPU',
        resourceProfiles: [
          { id: 'cpu-small', gpuCount: 0 },
          { id: 'invalid-cpu-gpu', gpuCount: 1 },
        ],
      },
    ],
  };

  assert.deepEqual(
    listTrainingResourceProfiles(plan).map((item) => item.id),
    ['gpu-1', 'cpu-small'],
  );
  assert.equal(firstTrainingResourceProfileId(plan), 'cpu-small');
});

test('GPU-only plan remains selectable and empty plan does not', () => {
  assert.deepEqual(listTrainingResourceProfiles(undefined), []);
  assert.equal(
    firstTrainingResourceProfileId({
      runtimes: [
        {
          id: 'gpu-runtime',
          deviceType: 'NVIDIA_GPU',
          resourceProfiles: [{ id: 'gpu-1', gpuCount: 1 }],
        },
      ],
    }),
    'gpu-1',
  );
  assert.equal(firstTrainingResourceProfileId({ runtimes: [] }), undefined);
});

test('rejects invalid device counts and duplicate profile ids', () => {
  const profiles = listTrainingResourceProfiles({
    runtimes: [
      {
        id: 'cpu-runtime',
        deviceType: 'CPU',
        resourceProfiles: [
          { id: 'cpu-small', gpuCount: 0 },
          { id: 'cpu-with-gpu', gpuCount: 1 },
        ],
      },
      {
        id: 'gpu-runtime',
        deviceType: 'NVIDIA_GPU',
        resourceProfiles: [
          { id: 'cpu-small', gpuCount: 1 },
          { id: 'fractional-gpu', gpuCount: 0.5 },
          { id: 'missing-gpu-count' },
        ],
      },
    ],
  });
  assert.deepEqual(
    profiles.map((item) => item.id),
    ['cpu-small'],
  );
});

test('accepts only the preserved form value that belongs to the current profiles', () => {
  const profiles = [{ id: 'cpu-small' }];

  assert.equal(
    isTrainingResourceProfileIdAllowed(profiles, 'cpu-small'),
    true,
  );
  assert.equal(
    isTrainingResourceProfileIdAllowed(profiles, undefined),
    false,
  );
  assert.equal(isTrainingResourceProfileIdAllowed(profiles, 'gpu-1'), false);
});

test('formats resource labels without exposing internal profile ids', () => {
  assert.equal(
    formatTrainingResourceProfileLabel({
      id: 'gpu-internal',
      deviceType: 'NVIDIA_GPU',
      gpuCount: 1,
      cpuLimit: '8',
      memoryLimit: '16Gi',
    }),
    'GPU · 1 卡 · 8 核 · 16Gi',
  );
  assert.equal(
    formatTrainingResourceProfileLabel({
      id: 'cpu-internal',
      deviceType: 'CPU',
      gpuCount: 0,
      cpuLimit: '4',
      memoryLimit: '8Gi',
    }),
    'CPU · 4 核 · 8Gi',
  );
});
