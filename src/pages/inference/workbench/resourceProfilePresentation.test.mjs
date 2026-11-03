import assert from 'node:assert/strict';
import test from 'node:test';

import {
  defaultInferenceResourceProfileId,
  listUsableInferenceProfiles,
  listUsableCpuInferenceProfiles,
} from './resourceProfilePresentation.mjs';

test('keeps only unique enabled CPU profiles for the CPU-only phase', () => {
  const profiles = [
    { id: 'cpu-small', deviceType: 'CPU', gpuCount: 0 },
    { id: 'gpu-1', deviceType: 'NVIDIA_GPU', gpuCount: 1 },
    { id: 'cpu-small', deviceType: 'CPU', gpuCount: 0 },
  ];

  assert.deepEqual(
    listUsableCpuInferenceProfiles(profiles).map((item) => item.id),
    ['cpu-small'],
  );
  assert.equal(defaultInferenceResourceProfileId(profiles), 'cpu-small');
});

test('returns no default when the backend provides no usable CPU profile', () => {
  assert.deepEqual(listUsableCpuInferenceProfiles(undefined), []);
  assert.equal(defaultInferenceResourceProfileId([]), undefined);
});

test('accepts one-card GPU profiles and rejects unsupported multi-GPU profiles', () => {
  const profiles = listUsableInferenceProfiles([
    { id: 'cpu-small', deviceType: 'CPU', gpuCount: 0 },
    { id: 'gpu-one', deviceType: 'NVIDIA_GPU', gpuCount: 1 },
    { id: 'gpu-two', deviceType: 'NVIDIA_GPU', gpuCount: 2 },
  ]);
  assert.deepEqual(profiles.map((item) => item.id), ['cpu-small', 'gpu-one']);
});
