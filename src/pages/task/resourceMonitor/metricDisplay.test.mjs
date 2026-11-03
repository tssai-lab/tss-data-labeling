import assert from 'node:assert/strict';
import test from 'node:test';
import { formatMetric, isMetricAvailable } from './metricDisplay.mjs';

test('real zero remains zero', () => {
  assert.equal(isMetricAvailable(0), true);
  assert.equal(formatMetric(0, '%'), '0%');
});

test('missing or invalid samples are explicit', () => {
  assert.equal(isMetricAvailable(null), false);
  assert.equal(isMetricAvailable(undefined), false);
  assert.equal(isMetricAvailable(Number.NaN), false);
  assert.equal(formatMetric(null, '%'), '无数据');
});
