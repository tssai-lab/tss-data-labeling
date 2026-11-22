import assert from 'node:assert/strict';
import test from 'node:test';
import dayjs from 'dayjs';
import {
  flushHooks,
  renderHooks,
} from '../../../scripts/qa/render-hook-component.mjs';

const text = (node) =>
  (node?.props?.children || [])
    .map((child) => (typeof child === 'object' ? text(child) : child))
    .join('');
const styles = { './workspaceUi.less': { default: {} } };

test('native focus mode accepts the project navigation context and keeps the content landmark', () => {
  const project = { projectId: 'project', name: '项目' };
  const r = renderHooks(
    new URL('./WorkspaceLayout.tsx', import.meta.url),
    { project, focusMode: true, workbench: true },
    {
      antd: { ConfigProvider: 'ConfigProvider' },
      './Navigation': { default: 'Navigation' },
      './index.less': {
        default: {
          page: 'page',
          workspacePage: 'workspace',
          focusPage: 'focus',
          content: 'content',
          workbenchContent: 'workbench',
        },
      },
      './workspaceTheme': { workspaceTheme: {}, workspaceVariables: {} },
    },
  );
  r.render();
  assert.match(r.nodes('main')[0].props.className, /focus/);
  assert.equal(r.nodes('Navigation')[0].props.project, project);
  assert.equal(r.nodes('section')[0].props.id, 'labeling-main-content');
});
const spec = {
  specVersionId: 'spec',
  versionNo: 1,
  labels: [],
  instructions: '',
};
const sample = {
  sampleId: 'sample',
  specVersionId: 'spec',
  resultId: 'answer',
  selectedResultId: 'answer',
  reviewRevision: 3,
  reviewable: true,
  reviewStatus: 'PENDING',
  result: {},
};
const click = (r, label) => {
  const button = r.nodes('Button').find((node) => text(node) === label);
  assert.ok(button, label);
  assert.equal(Boolean(button.props.disabled), false, label);
  return button.props.onClick();
};
function resultHost() {
  const calls = [],
    confirms = [];
  const props = {
    projectId: 'project',
    spec,
    selected: [sample],
    onSelected() {},
    onChanged() {},
    refresh: 0,
  };
  const ui = Object.fromEntries(
    [
      'Alert',
      'Button',
      'Card',
      'Drawer',
      'Select',
      'Space',
      'Table',
      'Tag',
    ].map((name) => [name, name]),
  );
  ui.Input = Object.assign(() => {}, {
    Search: 'Search',
    TextArea: 'TextArea',
  });
  ui.App = {
    useApp: () => ({
      modal: { confirm: (value) => confirms.push(value) },
      message: { success() {} },
    }),
  };
  const r = renderHooks(new URL('./LiveResults.tsx', import.meta.url), props, {
    ...styles,
    antd: ui,
    './ResultMetrics': { default: 'ResultMetrics' },
    './ReviewWorkbench': {
      default: 'ReviewWorkbench',
      sourceNames: {},
      reviewStatusNames: {},
      confidenceText: () => '',
    },
    './OriginalImage': { default: 'OriginalImage' },
    './StructuredPreview': { default: 'StructuredPreview' },
    '@/services/labeling': {
      operationKey: () => 'key',
      describeLabelingError: (e) => e.message,
    },
    '@/services/labelingStructured': { resultLabel: (value) => value?.labelId },
    '@/services/labelingOwner': {
      selectionItem: (value) => value,
      readAllPages: async (fn) => (await fn(null)).items,
      ownerApi: {
        results: async (_project, filter) => {
          calls.push(['results', { ...filter }]);
          return { items: [sample], nextCursor: null };
        },
        statistics: async (_project, filter) => {
          calls.push(['statistics', { ...filter }]);
          return {
            total: 8,
            hasResultCount: 5,
            noResultCount: 3,
            acceptedCount: 2,
          };
        },
        select: async (_project, body) => {
          calls.push(['select', body]);
          return {
            selectionId: 'fixed',
            selectionHash: 'hash',
            count: 1,
            expiresAt: '2099-01-01T00:00:00Z',
          };
        },
        selectionItems: async () => ({ items: [sample], nextCursor: null }),
        selectionStatistics: async () => ({
          total: 1,
          hasResultCount: 1,
          acceptedCount: 0,
        }),
        atomicReview: async (_project, body) => {
          calls.push(['review', body]);
          return {};
        },
      },
    },
  });
  return { r, props, calls, confirms };
}

test('statistics cards expose real counts, keyboard button semantics and a distinct selected filter', () => {
  const selected = [];
  const props = { active: 'all', onChange: (value) => selected.push(value) };
  const r = renderHooks(
    new URL('./ResultMetrics.tsx', import.meta.url),
    props,
    { ...styles, '@ant-design/icons': { FilterOutlined: 'FilterIcon' } },
  );
  r.render();
  assert.ok(r.nodes('button').every((node) => node.props.disabled));
  props.statistics = {
    total: 8,
    hasResultCount: 5,
    noResultCount: 3,
    acceptedCount: 0,
  };
  r.render();
  assert.equal(
    r.nodes('button').filter((node) => node.props['aria-pressed']).length,
    1,
  );
  assert.match(text(r.nodes('button')[3]), /已确认0/);
  assert.equal(r.nodes('button')[3].props.disabled, false);
  r.nodes('button')[3].props.onClick();
  assert.deepEqual(selected, ['accepted']);
  props.active = 'accepted';
  r.render();
  assert.equal(r.nodes('button')[3].props['aria-pressed'], true);
});

test('metric clicks narrow results while statistics retain the source scope and do not collapse into the active metric', async () => {
  const { r, calls } = resultHost();
  r.render();
  await flushHooks();
  r.render();
  r.nodes('ReviewWorkbench')[0].props.onFilter('sourceKind', 'AI');
  r.render();
  await flushHooks();
  r.render();
  r.nodes('ResultMetrics')[0].props.onChange('accepted');
  r.render();
  await flushHooks();
  r.render();
  const last = (type) => calls.filter((call) => call[0] === type).at(-1)[1];
  assert.equal(last('results').reviewStatus, 'ACCEPTED');
  assert.equal(last('results').sourceKind, 'AI');
  assert.equal(last('statistics').reviewStatus, undefined);
  assert.equal(last('statistics').sourceKind, 'AI');
  assert.equal(last('statistics').specVersionId, 'spec');
  r.nodes('ResultMetrics')[0].props.onChange('noResult');
  r.render();
  await flushHooks();
  r.render();
  assert.equal(last('results').hasResult, 'false');
  assert.equal(last('results').reviewStatus, undefined);
  r.dispose();
});

test('scope drawer freezes filter exclusions, requires a revision reason, submits the frozen id and invalidates edited scope', async () => {
  const { r, calls, confirms } = resultHost();
  r.render();
  await flushHooks();
  r.render();
  assert.equal(r.nodes('Drawer')[0].props.open, false);
  r.nodes('ReviewWorkbench')[0].props.onScopePreview();
  r.render();
  assert.equal(r.nodes('Drawer')[0].props.open, true);
  r.nodes('Select')
    .find((node) => node.props['aria-label'] === '预览范围')
    .props.onChange('filter');
  r.nodes('Select')
    .find((node) => node.props['aria-label'] === '预览用途')
    .props.onChange('NEEDS_REVISION');
  r.render();
  r.nodes('TextArea')
    .find((node) => node.props['aria-label'] === '本次排除样本')
    .props.onChange({ target: { value: 'exclude-a\nexclude-a，exclude-b' } });
  r.render();
  await click(r, '创建固定预览');
  await flushHooks();
  r.render();
  assert.equal(
    calls.find((call) => call[0] === 'select')[1].excludeSampleIds.join(','),
    'exclude-a,exclude-b',
  );
  assert.equal(
    r.nodes('Button').find((node) => text(node) === '执行固定范围审核').props
      .disabled,
    true,
  );
  r.nodes('TextArea')
    .find((node) => node.props['aria-label'] === '固定范围待修正原因')
    .props.onChange({ target: { value: '请复核边界' } });
  r.render();
  click(r, '执行固定范围审核');
  await confirms.at(-1).onOk();
  await flushHooks();
  r.render();
  assert.equal(
    calls.find((call) => call[0] === 'review')[1].selectionId,
    'fixed',
  );
  assert.equal(
    calls.find((call) => call[0] === 'review')[1].reason,
    '请复核边界',
  );
  assert.equal(r.nodes('Drawer')[0].props.open, false);
  await click(r, '创建固定预览');
  await flushHooks();
  r.render();
  r.nodes('TextArea')
    .find((node) => node.props['aria-label'] === '本次排除样本')
    .props.onChange({ target: { value: 'changed' } });
  r.render();
  r.render();
  assert.equal(
    r.nodes('Button').some((node) => text(node) === '执行固定范围审核'),
    false,
  );
  r.dispose();
});

test('authorized original image opens and closes its controlled preview and releases the old blob when the sample changes', async () => {
  const released = [];
  let sequence = 0;
  const props = { projectId: 'p', sampleId: 'first' };
  const r = renderHooks(
    new URL('./OriginalImage.tsx', import.meta.url),
    props,
    {
      ...styles,
      antd: Object.fromEntries(
        ['Alert', 'Button', 'Empty', 'Image', 'Spin'].map((name) => [
          name,
          name,
        ]),
      ),
      '@ant-design/icons': { ZoomInOutlined: 'ZoomIcon' },
      '@/services/labeling': {
        sampleBlob: async () => new Blob(['fixture']),
        describeLabelingError: (e) => e.message,
      },
    },
    {
      URL: {
        createObjectURL: () => `blob:${++sequence}`,
        revokeObjectURL: (url) => released.push(url),
      },
    },
  );
  r.render();
  await flushHooks();
  r.render();
  assert.equal(r.nodes('Image')[0].props.src, 'blob:1');
  r.nodes('button')[0].props.onClick();
  r.render();
  assert.equal(r.nodes('Image')[0].props.preview.visible, true);
  r.nodes('Image')[0].props.preview.onVisibleChange(false);
  r.render();
  assert.equal(r.nodes('Image')[0].props.preview.visible, false);
  props.sampleId = 'second';
  r.render();
  await flushHooks();
  r.render();
  assert.equal(r.nodes('Image')[0].props.src, 'blob:2');
  assert.deepEqual(released, ['blob:1']);
  r.dispose();
  assert.deepEqual(released, ['blob:1', 'blob:2']);
});

test('project search and status filtering use loaded records; each management action receives its own project without changing the list route', async () => {
  const projects = [
    {
      projectId: 'a',
      name: '图片检查',
      description: '车间',
      status: 'DRAFT',
      taskType: 'IMAGE_CLASSIFICATION',
      createdAt: '2026-11-18T01:00:00Z',
    },
    {
      projectId: 'b',
      name: 'Energy',
      description: '站点',
      status: 'OPEN',
      taskType: 'TIMESERIES_ANNOTATION',
      createdAt: '2026-11-17T01:00:00Z',
    },
  ];
  const location = { pathname: '/labeling', search: '' },
    routes = [];
  const deps = {
    dayjs: { default: dayjs },
    ...styles,
    './index.less': { default: {} },
    '@ant-design/icons': {
      CloudUploadOutlined: 'UploadIcon',
      PlusOutlined: 'PlusIcon',
      ReloadOutlined: 'ReloadIcon',
    },
    '@umijs/max': {
      Link: 'Link',
      useLocation: () => location,
      useParams: () => ({}),
      history: {
        location,
        push: (route) => routes.push(route),
        replace: (route) => routes.push(route),
      },
    },
    antd: Object.fromEntries(
      [
        'Alert',
        'Button',
        'Card',
        'Empty',
        'Image',
        'Input',
        'Modal',
        'Progress',
        'Select',
        'Space',
        'Spin',
        'Steps',
        'Table',
        'Tag',
      ].map((name) => [name, name]),
    ),
    '@/services/labeling': {
      LabelingError: class extends Error {},
      pageQuery: () => '',
      labelingJson: async (path) =>
        path === '/me'
          ? { actorId: 'actor', canCreateProject: true }
          : { items: projects, nextCursor: null },
    },
    '@/services/labelingOwner': { ownerApi: { templates: async () => [] } },
    '@/services/labelingUpload.mjs': { hashFile() {}, uploadChunks() {} },
  };
  deps.antd.DatePicker = { RangePicker: 'RangePicker' };
  deps.antd.Input = Object.assign(() => {}, { TextArea: 'TextArea' });
  deps.antd.App = { useApp: () => ({ message: {} }) };
  deps.antd.Form = Object.assign(() => {}, {
    useForm: () => [{}],
    Item: 'FormItem',
  });
  deps.antd.Typography = { Text: 'Text', Paragraph: 'Paragraph' };
  for (const name of [
    'ProjectBreadcrumbs',
    'ProjectHistory',
    'ProjectManagement',
    'PublishPlan',
    'SpecVersions',
    'StructuredPreview',
    'WorkspaceLayout',
    'StatusBadge',
  ])
    deps[`./${name}`] = { default: name };
  const r = renderHooks(new URL('./index.tsx', import.meta.url), {}, deps);
  r.render();
  await flushHooks();
  r.render();
  await flushHooks();
  r.render();
  assert.equal(r.nodes('Table')[0].props.dataSource.length, 2);
  r.nodes(deps.antd.Input)
    .find((node) => node.props['aria-label'] === '按项目名称搜索')
    .props.onChange({ target: { value: 'energy' } });
  r.render();
  assert.equal(r.nodes('Table')[0].props.dataSource[0].projectId, 'b');
  r.nodes('Select')
    .find((node) => node.props['aria-label'] === '筛选已加载项目状态')
    .props.onChange('DRAFT');
  r.render();
  assert.equal(r.nodes('Table')[0].props.dataSource.length, 0);
  r.nodes('Select')
    .find((node) => node.props['aria-label'] === '筛选已加载项目状态')
    .props.onChange('ALL');
  r.render();
  const managementCell = r.nodes('Table')[0].props.columns.find((column) => column.title === '项目操作').render(undefined, projects[1]);
  const management = managementCell;
  assert.equal(management.type, 'ProjectManagement');
  assert.equal(management.props.project.projectId, 'b');
  assert.deepEqual(routes, []);
  r.dispose();
});
