import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildTrainingResourceRequest,
  formatMiB,
  formatTrainingResourceSummary,
  resourceStatusPresentation,
} from './trainingResourcePresentation.mjs';

const capability = {
  displayName: 'RTX 4080',
  deviceType: 'NVIDIA_GPU',
  cpu: { requestCores: 1, limitCores: 4 },
  memory: { requestMiB: 2048, limitMiB: 8192 },
  gpu: { metricsComplete: true, safeTotalMemoryMiB: 16384 },
  dataStatus: 'AVAILABLE',
  eligibleNodeCount: 2,
  message: '资源可能变化',
};

test('recommended mode keeps the existing request contract', () => {
  assert.deepEqual(
    { hardwareTargetId: 'hw-4080' },
    buildTrainingResourceRequest(
      'recommended',
      {},
      { gpuCount: 1 },
      capability,
      'hw-4080',
    ),
  );
});

test('custom mode builds bounded CPU memory GPU and soft memory request', () => {
  assert.deepEqual(
    buildTrainingResourceRequest(
      'custom',
      { cpuCores: 2, memoryMiB: 4096, gpuMemoryLimitMiB: 8192 },
      { gpuCount: 1 },
      capability,
      'hw-4080',
    ),
    {
      hardwareTargetId: 'hw-4080',
      cpuCores: 2,
      memoryMiB: 4096,
      gpuCount: 1,
      gpuMemoryLimitMiB: 8192,
    },
  );
});

test('custom mode rejects stale GPU details and values outside the plan', () => {
  assert.throws(
    () =>
      buildTrainingResourceRequest(
        'custom',
        { cpuCores: 0.5, memoryMiB: 4096 },
        { gpuCount: 1 },
        capability,
        'hw-4080',
      ),
    /CPU 核数/,
  );
  assert.throws(
    () =>
      buildTrainingResourceRequest(
        'custom',
        { cpuCores: 2, memoryMiB: 4096, gpuMemoryLimitMiB: 1024 },
        { gpuCount: 1 },
        { ...capability, gpu: { ...capability.gpu, metricsComplete: false } },
        'hw-4080',
      ),
    /GPU 显存预算/,
  );
});

test('resource request rejects a missing hardware target', () => {
  assert.throws(
    () =>
      buildTrainingResourceRequest(
        'recommended',
        {},
        { gpuCount: 1 },
        capability,
      ),
    /硬件型号/,
  );
});

test('resource status and memory labels never invent missing values', () => {
  assert.equal('16 GiB', formatMiB(16384));
  assert.equal('无数据', formatMiB(undefined));
  assert.equal('success', resourceStatusPresentation(capability).type);
  assert.equal('error', resourceStatusPresentation(undefined, '接口超时').type);
});

test('confirmation displays the resources that will actually be submitted', () => {
  assert.equal(
    'RTX 4080 / 1 核 / 4 GiB',
    formatTrainingResourceSummary(
      'custom',
      { cpuCores: 1, memoryMiB: 4096 },
      capability,
    ),
  );
  assert.equal(
    'RTX 4080 / 4 核 / 8 GiB',
    formatTrainingResourceSummary('recommended', {}, capability),
  );
});
