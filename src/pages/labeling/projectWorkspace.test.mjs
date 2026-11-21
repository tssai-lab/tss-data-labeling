import assert from 'node:assert/strict';
import test from 'node:test';
import dayjs from 'dayjs';
import { renderHooks, flushHooks } from '../../../scripts/qa/render-hook-component.mjs';

function workspace(taskType = 'IMAGE_CLASSIFICATION', { root = false, catalogue = [] } = {}) {
  const location = { pathname: root ? '/labeling' : '/labeling/projects/historical', search: '?panel=publish' };
  const spec = { specVersionId: 'frozen', versionNo: 1, schemaVersion: 'image-classification-v1', labels: [{ id: 'normal', name: '正常' }], instructions: '冻结规范', editorConfig: null };
  const project = { projectId: 'historical', name: '历史项目', taskType, status: 'OPEN', specVersions: [spec] };
  const sample = { sampleId: 'sample-1', metadata: {}, sourcePath: 'normal.jpg', mediaType: 'image/jpeg' };
  const requested = [];
  const ui = Object.fromEntries(['Alert', 'Button', 'Card', 'Empty', 'Input', 'Modal', 'Select', 'Space', 'Spin', 'Table', 'Tabs', 'Tag'].map((name) => [name, name]));
  ui.App = { useApp: () => ({ message: { success() {} } }) };
  ui.Form = Object.assign(() => {}, { Item: 'FormItem' });
  ui.Typography = { Text: 'Text', Title: 'Title', Paragraph: 'Paragraph' };
  const dependencies = {
    antd: ui,
    dayjs: { default: dayjs },
    '@ant-design/icons': { PlusOutlined: 'PlusIcon' },
    '@umijs/max': { Link: 'Link', useLocation: () => location, useParams: () => ({ projectId: root ? undefined : project.projectId }), history: { block: () => () => {}, push(path) { location.search = path.slice(path.indexOf('?')); } } },
    './index.less': { default: {} },
    '@/services/labeling': { labelingJson: async (path) => { requested.push(path); return path === '/task-templates' ? catalogue : project; }, describeLabelingError: (error) => error.message, operationKey: () => 'fixture-key', post: async (path, body) => { requested.push({ path, body }); return project; } },
    '@/services/nativeLabeling': { collectPages: async (path) => { requested.push(path); return path.endsWith('/input-samples') ? [sample] : []; } },
  };
  for (const name of ['NativeDataPreparation', 'NativeOutcome', 'NativePublish', 'NativeReview', 'NativeAi', 'NativeSpecSettings', 'StatusBadge', 'WorkspaceLayout', 'ProjectManagement', 'ProjectBreadcrumbs']) dependencies[`./${name}`] = { default: name };
  const runtime = renderHooks(new URL('./ServerWorkspace.tsx', import.meta.url), {}, dependencies);
  return { runtime, requested, project, spec, sample, location };
}

test('historical and native projects use the same five project panels and load every data source', async () => {
  for (const taskType of ['IMAGE_CLASSIFICATION', 'TIMESERIES_ANNOTATION', 'COMPUTER_VISION']) {
    const { runtime: r, requested, spec } = workspace(taskType);
    r.render(); await flushHooks(); r.render();
    assert.equal(r.nodes('Tabs')[0].props.items.map((item) => item.key).join(','), 'data,settings,publish,review,outcome');
    const publish = r.nodes('NativePublish')[0];
    assert.equal(publish.props.version.specVersionId, spec.specVersionId);
    assert.equal(publish.props.version.schemaVersion, spec.schemaVersion);
    assert.equal(publish.props.version.labels[0].id, 'normal');
    for (const endpoint of ['input-samples', 'input-resources', 'sample-reviews', 'submissions', 'final-snapshots']) assert.ok(requested.includes(`/projects/historical/${endpoint}`));
    r.dispose();
  }
});

test('switching AI back to human removes the AI step and clears candidates; returning to publish preserves AI selection', async () => {
  const { runtime: r, spec, sample, location } = workspace();
  r.render(); await flushHooks(); r.render();
  r.nodes('NativePublish')[0].props.onModeChange('AI'); r.render();
  assert.ok(r.nodes('Tabs')[0].props.items.some((item) => item.key === 'ai'));
  r.nodes('NativePublish')[0].props.onPublishAi([sample], spec, { modelId: 'demo-cv-1', instructions: '' });
  r.render(); r.render();
  assert.equal(r.nodes('Tabs')[0].props.activeKey, 'ai');
  assert.equal(r.nodes('NativeAi').length, 1);
  r.nodes('NativeAi')[0].props.onComplete([{ sample, label: '正常', confidence: .97, status: '待核查' }]);
  location.search = '?panel=publish'; r.render(); r.render();
  assert.equal(r.nodes('NativePublish')[0].props.mode, 'AI');
  r.nodes('NativePublish')[0].props.onModeChange('HUMAN'); r.render();
  assert.equal(r.nodes('Tabs')[0].props.items.some((item) => item.key === 'ai'), false);
  assert.equal(r.nodes('NativeAi').length, 0);
  location.search = '?panel=review'; r.render(); r.render();
  assert.equal(r.nodes('NativeReview')[0].props.aiResults, undefined);
  r.dispose();
});


test('project creation exposes only image and time-series families and binds their shared editors', async () => {
  const capabilities = { importer: true, inputReader: true, resultValidator: true, completion: true, exporter: true };
  const catalogue = [
    { templateId: 'energy-station-day', taskType: 'TIMESERIES_ANNOTATION', editorKey: 'energy-station-day-editor' },
    { templateId: 'image-classification', taskType: 'IMAGE_CLASSIFICATION', editorKey: 'image-classification-editor' },
    { templateId: 'computer-vision', taskType: 'COMPUTER_VISION', editorKey: 'label-studio-editor' },
    { templateId: 'time-series', taskType: 'TIME_SERIES', editorKey: 'label-studio-editor' },
  ].map((item) => ({ ...item, templateVersion: '1.0.0', enabled: true, capabilities }));
  for (const plugin of catalogue.slice(2)) {
  const { runtime: r, requested } = workspace('IMAGE_CLASSIFICATION', { root: true, catalogue });
  r.render(); await flushHooks(); r.render();
  const select = r.nodes('Select').find((node) => node.props['aria-label'] === '任务类别');
  assert.deepEqual(Array.from(select.props.options, (option) => option.label), ['图像标注', '时序标注']);
  assert.equal(select.props.value, undefined);
  select.props.onChange(plugin.taskType);
  r.nodes('Input').find((node) => node.props['aria-label'] === '项目名称').props.onChange({ target: { value: '插件项目' } });
  r.render();
  r.nodes('Modal')[0].props.onOk(); await flushHooks();
  const call = requested.find((item) => item?.path === '/projects');
  assert.equal(call.body.taskType, plugin.taskType);
  assert.equal(call.body.templateId, plugin.templateId);
  assert.equal(call.body.templateVersion, '1.0.0');
  r.dispose();
  }
});

test('project creation opens and explains missing backend capabilities without submitting an unavailable plugin', async () => {
  const plugin = { templateId: 'computer-vision', templateVersion: '1.0.0', name: '通用图像标注', taskType: 'COMPUTER_VISION', editorKey: 'label-studio-editor', enabled: true };
  const { runtime: r, requested } = workspace('IMAGE_CLASSIFICATION', { root: true, catalogue: [plugin] });
  r.render(); await flushHooks(); r.render();
  const button = r.nodes('Button').find((node) => node.props.children?.includes('创建项目'));
  assert.equal(button.props.disabled, false);
  button.props.onClick(); r.render();
  assert.equal(r.nodes('Modal')[0].props.open, true);
  assert.ok(r.nodes('Alert').some((node) => node.props.message === '暂时没有可用的任务类型'));
  assert.equal(r.nodes('Select').find((node) => node.props['aria-label'] === '任务类别').props.options[0].disabled, true);
  assert.equal(r.nodes('Modal')[0].props.okButtonProps.disabled, true);
  r.nodes('Modal')[0].props.onOk(); await flushHooks(); r.render();
  assert.equal(requested.some((item) => item?.path === '/projects'), false);
  r.dispose();
});
