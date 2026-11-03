import assert from 'node:assert/strict';
import test from 'node:test';

import {
  formatTrainingMode,
  isSingleTrainingMode,
} from './trainingModePresentation.mjs';

test('presents the platform training modes in plain language', () => {
  assert.equal(formatTrainingMode('FULL_FINETUNE'), '完整微调');
  assert.equal(formatTrainingMode('FROM_SCRATCH'), '从零训练');
  assert.equal(formatTrainingMode('CUSTOM_MODE'), 'CUSTOM_MODE');
});

test('only hides the selector when the plan fixes exactly one mode', () => {
  assert.equal(isSingleTrainingMode(['FULL_FINETUNE']), true);
  assert.equal(isSingleTrainingMode(['FULL_FINETUNE', 'FROM_SCRATCH']), false);
  assert.equal(isSingleTrainingMode([]), false);
});
