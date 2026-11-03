import assert from 'node:assert/strict';
import test from 'node:test';

import {
  firstTrainingHardwareOption,
  formatTrainingHardwareOptionLabel,
  isTrainingHardwareTargetAllowed,
} from './hardwareOptionPresentation.mjs';

const options = [
  {
    hardwareTargetId: 'hw-cpu',
    displayName: 'CPU 计算资源',
    resourceProfileId: 'cpu-small',
    deviceType: 'CPU',
    cpu: { limitCores: 4 },
    memory: { limitMiB: 8192 },
    gpuCount: 0,
  },
  {
    hardwareTargetId: 'hw-4080',
    displayName: 'NVIDIA GeForce RTX 4080',
    resourceProfileId: 'gpu-small',
    deviceType: 'NVIDIA_GPU',
    cpu: { limitCores: 8 },
    memory: { limitMiB: 32768 },
    gpuCount: 1,
  },
];

test('keeps one unified list and can preserve an inherited profile', () => {
  assert.equal(firstTrainingHardwareOption(options)?.hardwareTargetId, 'hw-cpu');
  assert.equal(
    firstTrainingHardwareOption(options, 'gpu-small')?.hardwareTargetId,
    'hw-4080',
  );
});

test('rejects a stale or forged hardware target id', () => {
  assert.equal(isTrainingHardwareTargetAllowed(options, 'hw-4080'), true);
  assert.equal(isTrainingHardwareTargetAllowed(options, 'hw-forged'), false);
  assert.equal(isTrainingHardwareTargetAllowed([], 'hw-4080'), false);
});

test('presents concrete model without exposing node identity', () => {
  const label = formatTrainingHardwareOptionLabel(
    options[1],
    (value) => `${value / 1024} GiB`,
  );
  assert.equal(label, 'NVIDIA GeForce RTX 4080 · 1 卡 · 8 核 · 32 GiB');
  assert.doesNotMatch(label, /node|10\.|worker/i);
});
