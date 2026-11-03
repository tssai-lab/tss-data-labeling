import assert from 'node:assert/strict';
import test from 'node:test';

import { buildTrainingPlanHyperParams } from './trainingPlanDefaults.mjs';

test('serializes server-provided parameter defaults', () => {
  const result = buildTrainingPlanHyperParams({
    parameters: [
      { name: 'epochs', defaultValue: 2 },
      { name: 'batchSize', defaultValue: 16 },
      { name: 'lr', defaultValue: 0.00003 },
    ],
  });

  assert.deepEqual(JSON.parse(result), {
    epochs: 2,
    batchSize: 16,
    lr: 0.00003,
  });
});

test('omits parameters without defaults and ignores invalid entries', () => {
  const result = buildTrainingPlanHyperParams({
    parameters: [
      { name: 'requiredValue' },
      { name: 'nullable', defaultValue: null },
      { name: '  ' },
      null,
    ],
  });

  assert.deepEqual(JSON.parse(result), { nullable: null });
});

test('uses an empty object when the plan has no parameters', () => {
  assert.equal(buildTrainingPlanHyperParams(undefined), '{}');
});
