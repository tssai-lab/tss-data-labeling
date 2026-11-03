import assert from 'node:assert/strict';
import test from 'node:test';

import { normalizeMlflowMetricHistory } from './mlflowMetricHistory.mjs';

test('sorts points and keeps the last value for a duplicated step', () => {
  assert.deepEqual(
    normalizeMlflowMetricHistory([
      { step: 2, value: 0.5 },
      { step: 1, value: 0.9 },
      { step: 2, value: 0.4 },
    ]),
    [
      { step: 1, value: 0.9 },
      { step: 2, value: 0.4 },
    ],
  );
});

test('drops malformed points instead of drawing fake values', () => {
  assert.deepEqual(
    normalizeMlflowMetricHistory([
      { step: 1, value: 0.8 },
      { step: Number.NaN, value: 0.1 },
      { step: 2, value: Number.POSITIVE_INFINITY },
    ]),
    [{ step: 1, value: 0.8 }],
  );
});
