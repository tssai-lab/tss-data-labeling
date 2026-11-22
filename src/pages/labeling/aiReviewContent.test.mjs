import assert from 'node:assert/strict';
import test from 'node:test';
import { renderHooks } from '../../../scripts/qa/render-hook-component.mjs';

const text = (node) =>
  (node?.props?.children || [])
    .map((item) => (typeof item === 'object' ? text(item) : item))
    .join('');

test('AI review shows loaded candidates and only permits changes on writable projects', async () => {
  const writes = [];
  const props = {
    results: [
      { sample: { sampleId: 's' }, status: '待核查', reviewable: true },
    ],
    onChange: async (rows) => writes.push(rows),
    readOnly: true,
  };
  const input = Object.assign(() => {}, {});
  const r = renderHooks(new URL('./AiDemoReview.tsx', import.meta.url), props, {
    antd: {
      App: { useApp: () => ({ message: { error() {} } }) },
      Button: 'Button',
      Card: 'Card',
      Input: input,
      Space: 'Space',
      Table: 'Table',
      Tag: 'Tag',
      Typography: { Paragraph: 'Paragraph', Text: 'Text' },
    },
    './workspaceUi.less': { default: {} },
    '@/services/labeling': { describeLabelingError: (error) => error.message },
  });
  r.render();
  assert.match(text(r.nodes('Paragraph')[0]), /已读取 1 条候选/);
  assert.equal(
    r.nodes('Table')[0].props.rowSelection.getCheckboxProps(props.results[0])
      .disabled,
    true,
  );
  props.readOnly = false;
  r.render();
  r.nodes('Table')[0].props.rowSelection.onChange(['s']);
  r.render();
  const confirm = r
    .nodes('Button')
    .find((node) => text(node) === '确认选中结果');
  assert.equal(confirm.props.disabled, false);
  await confirm.props.onClick();
  assert.equal(writes.length, 1);
  assert.equal(writes[0][0].status, '已确认');
  r.dispose();
});

test('AI execution displays server status and does not present failed or queued runs as completed', () => {
  const props = {
    project: { projectId: 'p' }, samples: [], results: [], progress: 25,
    running: false, executionStatus: 'FAILED', modelName: 'model', instructions: '',
  };
  const r = renderHooks(new URL('./AiExecutionShowcase.tsx', import.meta.url), props, {
    '@ant-design/icons': new Proxy({}, { get: (_target, name) => String(name) }),
    antd: { Table: 'Table', Tag: 'Tag' },
    './aiExecution.less': { default: {} },
    '@/services/labeling': {},
  });
  r.render();
  assert.match(text(r.nodes('section')[0]), /执行失败/);
  assert.match(text(r.nodes('footer')[0]), /没有可核查的候选结果/);
  assert.equal(r.nodes('button').at(-1).props.disabled, true);
  assert.equal(r.nodes('Table')[0].props.locale.emptyText, '本轮暂无候选结果。');
  props.running = true;
  props.executionStatus = 'QUEUED';
  r.render();
  assert.match(text(r.nodes('section')[0]), /等待执行/);
  assert.match(text(r.nodes('footer')[0]), /等待执行/);
  r.dispose();
});
