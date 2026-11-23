import assert from 'node:assert/strict';
import test from 'node:test';
import { formatAiCandidateLabel } from './aiCandidateLabel.mjs';

test('resolves stored labels by the result control and the supplied spec version', () => {
  const regions = [
    { from_name: 'classification', value: { choices: ['label_a'] } },
    { from_name: 'energy_events', value: { timeserieslabels: ['label_a'], start: 12, end: 12 } },
    { from_name: 'classification', value: { choices: ['label_a'] } },
  ];
  const savedSpec = {
    classification: { labels_attrs: { label_a: { value: '异常' } } },
    energy_events: { labels_attrs: { label_a: { value: '尖峰' } } },
  };
  const renamedSpec = {
    ...savedSpec, classification: { labels_attrs: { label_a: { value: '缺陷' } } },
  };
  assert.equal(formatAiCandidateLabel(regions, savedSpec), '异常、尖峰（12–12）');
  assert.equal(formatAiCandidateLabel(regions, renamedSpec), '缺陷、尖峰（12–12）');
});

test('preserves labels from older results and ignores rationale text', () => {
  assert.equal(formatAiCandidateLabel([{ value: { choices: ['正常'] } }]), '正常');
  assert.equal(formatAiCandidateLabel([{ value: { text: ['判断依据'] } }]), '未生成标注区域');
});
