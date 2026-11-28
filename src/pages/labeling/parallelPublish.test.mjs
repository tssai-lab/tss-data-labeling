import assert from 'node:assert/strict';
import test from 'node:test';
import { flushHooks, renderHooks } from '../../../scripts/qa/render-hook-component.mjs';

const text = (node) => (node?.props?.children || []).map((child) => typeof child === 'object' ? text(child) : child).join('');
const button = (r, label) => r.nodes('Button').find((node) => text(node).includes(label));

test('freezes the selected scope and submits legacy agents and an online model in one idempotent publication', async () => {
  const samples = ['a', 'b', 'c'].map((sampleId) => ({ sampleId, sourcePath: `${sampleId}.jpg`, metadata: {} }));
  const agents = ['agent-1', 'agent-2'].map((agentId) => ({ agentId, enabled: true, displayName: agentId, modelVersion: 'v1' }));
  const calls = [], selectedCalls = [];
  const input = Object.assign(() => {}, { Search: 'Search', TextArea: 'TextArea' });
  const r = renderHooks(new URL('./ParallelPublish.tsx', import.meta.url), {
    project: { projectId: 'p', status: 'OPEN', taskType: 'IMAGE_CLASSIFICATION' }, samples,
    version: { specVersionId: 'v', versionNo: 1 }, specVersions: [], reviews: [],
    onVersionChange() {}, refresh() {}, onExecutions() {}, onOpenExecution() {},
  }, {
    antd: { Alert: 'Alert', Button: 'Button', Card: 'Card', Checkbox: 'Checkbox', Input: input, InputNumber: 'InputNumber', Select: 'Select', Space: 'Space', Steps: 'Steps', Table: 'Table', Tag: 'Tag', Typography: { Text: 'Text' } },
    '@/services/labeling': { describeLabelingError: (e) => e.message, operationKey: () => `key-${calls.length}-${selectedCalls.length}`, labelingJson: async (path) => path === '/model-connections' ? [{ connectionId: 'online-1', displayName: '在线模型', modelVersion: 'test-model' }] : [], post: async (_url, body, key) => { calls.push({body, key}); if (calls.length === 1) throw Object.assign(Error('请求的资源不存在'), { status: 404, code: 'RESOURCE_NOT_FOUND' }); return { publicationGroupId: 'g', selectionId: 'snapshot', specVersionId: 'v', sampleCount: 3, humanPlan: { packages: [{}, {}] }, executions: [] }; } },
    '@/services/labelingOwner': { ownerApi: { agents: async () => agents, select: async (_project, body, key) => { selectedCalls.push({ body, key }); return { selectionId: 'snapshot', count: 3, expiresAt: 'later' }; } } },
    '@/services/nativeLabeling': { collectPages: async (path) => path.includes('/results?') ? samples.map((s) => ({ sampleId: s.sampleId, reviewRevision: 0, reviewStatus: 'PENDING' })) : samples.map((s) => ({ sampleId: s.sampleId, reviewRevision: 0, resultId: null })) },
    './workspaceUi.less': { default: {} },
  });
  r.render();
  r.nodes('Table')[0].props.rowSelection.columnTitle.props.onChange({ target: { checked: true } });
  r.nodes('Checkbox')[1].props.onChange({ target: { checked: true } });
  r.render(); await flushHooks(); r.render();
  button(r, '下一步：配置任务').props.onClick(); r.render();
  r.nodes('Table')[0].props.rowSelection.onChange(['agent-1', 'agent-2', 'online-1']); r.render();
  button(r, '下一步：确认发布').props.onClick(); await flushHooks(); r.render();
  assert.equal(selectedCalls[0].body.purpose, 'PUBLISH');
  assert.deepEqual(Array.from(selectedCalls[0].body.items, (item) => item.sampleId), ['a','b','c']);
  assert.equal(r.nodes('Steps')[0].props.items[0].disabled, false);
  r.nodes('Steps')[0].props.onChange(1); r.render();
  assert.ok(button(r, '下一步：确认发布'));
  button(r, '下一步：确认发布').props.onClick(); await flushHooks(); r.render();
  assert.equal(selectedCalls.length, 2);
  button(r, '确认发布').props.onClick(); await flushHooks(); r.render();
  assert.ok(r.nodes('Alert').some((node) => String(node.props.message).includes('V28')));
  button(r, '确认发布').props.onClick(); await flushHooks(); r.render();
  assert.equal(calls[0].key, calls[1].key);
  assert.equal(calls[1].body.selectionId, 'snapshot');
  assert.equal(calls[1].body.human, true);
  assert.deepEqual(Array.from(calls[1].body.agentIds), ['agent-1', 'agent-2']);
  assert.deepEqual(Array.from(calls[1].body.connectionIds), ['online-1']);
  r.dispose();
});
