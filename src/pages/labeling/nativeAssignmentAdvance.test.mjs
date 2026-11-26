import assert from 'node:assert/strict';
import test from 'node:test';
import { flushHooks, renderHooks } from '../../../scripts/qa/render-hook-component.mjs';

test('completion advances only after the server saves it, while drafts and failures stay on the sample', async () => {
  const items = [{ sampleId: 'one', draftRevision: 0 }, { sampleId: 'two', draftRevision: 0 }];
  let fail = false;
  const r = renderHooks(new URL('./NativeAssignment.tsx', import.meta.url), {}, {
    '@ant-design/icons': { LeftOutlined: 'Left', RightOutlined: 'Right', UnorderedListOutlined: 'List' },
    '@umijs/max': { history: { block: () => () => {}, push() {} }, Link: 'Link', useParams: () => ({ assignmentId: 'a' }) },
    antd: { Alert: 'Alert', Button: 'Button', Checkbox: 'Checkbox', Drawer: 'Drawer', Empty: { PRESENTED_IMAGE_SIMPLE: 'empty' }, Input: { Search: 'Search', TextArea: 'TextArea' }, Modal: { confirm() {} }, Select: 'Select', Space: 'Space', Spin: 'Spin', Tag: 'Tag', Typography: { Text: 'Text', Title: 'Title', Paragraph: 'Paragraph' } },
    '@/services/labeling': {
      describeLabelingError: (e) => e.message, operationKey: () => 'key', post: async () => ({}),
      labelingJson: async (_path, options) => {
        if (!options?.method) return { specification: { schemaVersion: 'label-studio-v1' }, items, status: 'OPEN' };
        if (fail) throw new Error('保存失败');
        const body = JSON.parse(options.body);
        return { assignmentRevision: 1, revision: 1, explicitDone: body.explicitDone, result: body.result };
      },
    },
    '@/utils/formatDateTime': { formatDisplayDateTime: () => '' },
    './annotationWorkspace.less': { default: {} }, './assignment': { default: 'LegacyAssignment' },
    './NativeEditor': { default: 'NativeEditor' }, './WorkspaceLayout': { default: 'WorkspaceLayout' },
  });
  r.render(); await flushHooks(); r.render();
  let editor = r.nodes('NativeEditor')[0];
  assert.match(editor.props.path, /\/one\/task$/);
  await editor.props.onSave([], false); r.render();
  assert.match(r.nodes('NativeEditor')[0].props.path, /\/one\/task$/);
  fail = true;
  await assert.rejects(r.nodes('NativeEditor')[0].props.onSave([], true)); r.render();
  assert.match(r.nodes('NativeEditor')[0].props.path, /\/one\/task$/);
  fail = false;
  await r.nodes('NativeEditor')[0].props.onSave([], true); r.render();
  assert.match(r.nodes('NativeEditor')[0].props.path, /\/two\/task$/);
  await r.nodes('NativeEditor')[0].props.onSave([], true); r.render();
  assert.match(r.nodes('NativeEditor')[0].props.path, /\/two\/task$/);
  r.dispose();
});
