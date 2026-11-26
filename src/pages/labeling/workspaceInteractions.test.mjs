import assert from 'node:assert/strict';
import test from 'node:test';
import {
  flushHooks,
  renderHooks,
} from '../../../scripts/qa/render-hook-component.mjs';

const text = (node) =>
  (node?.props?.children || [])
    .map((child) => (typeof child === 'object' ? text(child) : child))
    .join('');
const buttons = (r, label) =>
  r.nodes('Button').find((node) => text(node).includes(label));

test('publish selects and deselects only the filtered samples and sends an explicit reopen choice', async () => {
  const input = Object.assign(() => {}, { Search: 'Search' });
  const radio = { Group: 'RadioGroup' };
  const sample = (sampleId, displayName) => ({
    sampleId,
    sourcePath: displayName,
    metadata: { displayName },
  });
  const samples = [
    sample('a', 'cat.jpg'),
    sample('b', 'dog.jpg'),
    sample('c', 'cat2.jpg'),
  ];
  let sent;
  const keys = [];
  const r = renderHooks(
    new URL('./NativePublish.tsx', import.meta.url),
    {
      project: { projectId: 'p', status: 'OPEN' },
      samples,
      version: { specVersionId: 'v', labels: [], editorConfig: {} },
      specVersions: [],
      reviews: [],
      run: async (fn) => { try { await fn(); } catch {} },
      refresh() {},
      busy: false,
      mode: 'HUMAN',
      onModeChange() {},
      onVersionChange() {},
      onPublishAi() {},
    },
    {
      antd: {
        Alert: 'Alert',
        Button: 'Button',
        Card: 'Card',
        Checkbox: 'Checkbox',
        Input: input,
        InputNumber: 'InputNumber',
        Radio: radio,
        Select: 'Select',
        Space: 'Space',
        Table: 'Table',
        Typography: { Text: 'Text', Title: 'Title', Paragraph: 'Paragraph' },
      },
      './workspaceUi.less': { default: {} },
      './NativeAi': { default: 'NativeAi' },
      '@/services/labeling': {
        operationKey: () => `key-${keys.length}`,
        post: async (_url, body, key) => {
          keys.push(key);
          sent = body;
          if (keys.length === 1) throw new Error('network failure');
          return { sampleCount: 2, packageSampleCounts: [2], totalWorkload: 2 };
        },
      },
    },
  );
  r.render();
  r.nodes('Search')[0].props.onChange({ target: { value: 'cat' } });
  r.render();
  r.nodes('Table')[0].props.rowSelection.columnTitle.props.onChange({ target: { checked: true } });
  r.render();
  assert.equal(
    r.nodes('Table')[0].props.rowSelection.selectedRowKeys.join(','),
    'a,c',
  );
  r.nodes('Table')[0].props.rowSelection.onChange(['a']);
  r.render();
  assert.equal(r.nodes('Table')[0].props.rowSelection.columnTitle.props.indeterminate, true);
  r.nodes('Table')[0].props.rowSelection.columnTitle.props.onChange({ target: { checked: true } });
  r.render();
  assert.equal(r.nodes('Table')[0].props.rowSelection.columnTitle.props.checked, true);
  r.nodes('Table')[0].props.rowSelection.columnTitle.props.onChange({ target: { checked: false } });
  r.render();
  assert.equal(
    r.nodes('Table')[0].props.rowSelection.selectedRowKeys.length,
    0,
  );
  r.nodes('Table')[0].props.rowSelection.columnTitle.props.onChange({ target: { checked: true } });
  r.render();
  buttons(r, '生成发布计划').props.onClick();
  await flushHooks();
  r.render();
  buttons(r, '生成发布计划').props.onClick();
  await flushHooks();
  r.render();
  assert.deepEqual(keys, ['key-0', 'key-0']);
  assert.equal(sent.reopenAccepted, false);
  assert.equal(sent.samples.map((item) => item.sampleId).join(','), 'a,c');
  r.dispose();
});

test('AI prelabel forwards the choice to reopen accepted samples', () => {
  let config;
  const input = Object.assign(() => {}, { Search: 'Search' });
  const r = renderHooks(new URL('./NativePublish.tsx', import.meta.url), {
    project: { projectId: 'p', status: 'OPEN' },
    samples: [{ sampleId: 'a', sourcePath: 'a.jpg', metadata: {} }],
    version: { specVersionId: 'v', labels: [], editorConfig: {} },
    specVersions: [], reviews: [{ sampleId: 'a', specVersionId: 'v', status: 'ACCEPTED' }],
    run: async (fn) => fn(), refresh() {}, busy: false, mode: 'AI',
    onModeChange() {}, onVersionChange() {}, onPublishAi: (_samples, _version, value) => { config = value; },
  }, {
    antd: { Alert: 'Alert', Button: 'Button', Card: 'Card', Checkbox: 'Checkbox', Input: input,
      InputNumber: 'InputNumber', Radio: { Group: 'RadioGroup' }, Select: 'Select', Space: 'Space', Table: 'Table',
      Typography: { Text: 'Text', Title: 'Title', Paragraph: 'Paragraph' } },
    './workspaceUi.less': { default: {} }, './NativeAi': { default: 'NativeAi' },
    '@/services/labeling': { operationKey: () => 'key', post: async () => ({}) },
  }, { window: { scrollTo() {} } });
  r.render();
  r.nodes('Checkbox')[0].props.onChange({ target: { checked: true } });
  r.render();
  r.nodes('Table')[0].props.rowSelection.columnTitle.props.onChange({ target: { checked: true } });
  r.render();
  r.nodes('Button').find((node) => node.props.type === 'primary').props.onClick();
  r.render();
  r.nodes('NativeAi')[0].props.onPublish({ modelId: 'qwen-image', instructions: '' });
  assert.equal(config.reopenAccepted, true);
  r.dispose();
});

test('review uses project filenames, preserves the selected sample on refresh, and protects navigation', async () => {
  const rows = [
    { sampleId: 'a', result: {} },
    { sampleId: 'b', result: {} },
  ];
  let permit = true,
    refreshed = 0;
  const input = Object.assign(() => {}, { TextArea: 'TextArea' });
  const r = renderHooks(
    new URL('./NativeReview.tsx', import.meta.url),
    {
      project: {
        projectId: 'p',
        status: 'OPEN',
        specVersions: [{ specVersionId: 'v' }],
      },
      samples: [
        { sampleId: 'a', sourcePath: 'folder/one.jpg', metadata: {} },
        { sampleId: 'b', sourcePath: 'folder/two.jpg', metadata: {} },
      ],
      submissions: [{ submissionId: 'sub' }],
      run: async (fn) => fn(),
      refresh: () => {
        refreshed++;
      },
      rework() {},
      busy: false,
      onGuard() {},
      onAiResultsChange: async () => {},
    },
    {
      '@ant-design/icons': {
        LeftOutlined: 'LeftIcon',
        RightOutlined: 'RightIcon',
        ReloadOutlined: 'ReloadIcon',
      },
      antd: {
        Alert: 'Alert',
        Button: 'Button',
        Card: 'Card',
        Empty: Object.assign(() => {}, { PRESENTED_IMAGE_SIMPLE: 'empty' }),
        Input: input,
        Select: 'Select',
        Space: 'Space',
        Spin: 'Spin',
        Tag: 'Tag',
        Typography: { Paragraph: 'Paragraph' },
      },
      './workspaceUi.less': { default: {} },
      './ProjectSampleEditor': { default: 'ProjectSampleEditor' },
      './ReviewAudit': { default: 'ReviewAudit' },
      './AiDemoReview': { default: 'AiDemoReview' },
      '@/services/labeling': {
        labelingJson: async (path) =>
          path.endsWith('/review')
            ? { items: [] }
            : { items: rows, specVersionId: 'v' },
        describeLabelingError: (e) => e.message,
      },
      '@/services/projectCompatibility.mjs': { hasProjectAnswer: () => true },
      '@/utils/formatDateTime': { formatDisplayDateTime: () => '-' },
    },
  );
  r.render();
  await flushHooks();
  r.render();
  assert.ok(r.nodes('button').some((node) => text(node).includes('one.jpg')));
  r.nodes('ProjectSampleEditor')[0].props.onGuard(() => permit);
  permit = false;
  buttons(r, '下一张').props.onClick();
  r.render();
  assert.equal(r.nodes('ProjectSampleEditor')[0].props.sampleId, 'a');
  permit = true;
  buttons(r, '下一张').props.onClick();
  r.render();
  assert.equal(r.nodes('ProjectSampleEditor')[0].props.sampleId, 'b');
  const listCard = r
    .nodes('Card')
    .find((node) => node.props.title === '样本列表');
  listCard.props.extra.props.children
    .find((node) => node.props['aria-label'] === '刷新结果')
    .props.onClick();
  r.render();
  await flushHooks();
  r.render();
  assert.equal(r.nodes('ProjectSampleEditor')[0].props.sampleId, 'b');
  assert.equal(refreshed, 1);
  r.dispose();
});
