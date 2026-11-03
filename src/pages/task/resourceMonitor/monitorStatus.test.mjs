import assert from 'node:assert/strict';
import test from 'node:test';
import { getMetricsStatusMeta, getNodeWarnings } from './monitorStatus.mjs';

test('unknown metrics are never presented as fresh', () => {
  assert.equal(getMetricsStatusMeta(undefined).label, '无指标');
  assert.equal(getMetricsStatusMeta('future-status').label, '无指标');
});

test('scheduling and pressure warnings do not hide each other', () => {
  assert.deepEqual(
    getNodeWarnings({
      nodeHealthStatus: 'warning',
      nodeReady: true,
      nodeUnschedulable: true,
      nodeDiskPressure: true,
    }),
    ['已禁止调度', '磁盘压力'],
  );
});

test('missing node health is explicit', () => {
  assert.deepEqual(getNodeWarnings({ nodeHealthStatus: 'unavailable' }), [
    '节点状态不可用',
  ]);
  assert.deepEqual(getNodeWarnings({}), ['节点状态不可用']);
});
