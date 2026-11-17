import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../../scripts/qa/load-typescript-module.mjs';
import {
  flushHooks,
  renderHooks,
} from '../../../scripts/qa/render-hook-component.mjs';

const { publishingStep, selectedForSpec, canFinalize } = loadTypeScriptModule(
  new URL('../../services/labelingWorkspace.ts', import.meta.url),
);
const spec = {
  specVersionId: 'spec',
  versionNo: 1,
  labels: [],
  instructions: '',
};
const project = {
  projectId: 'project/a',
  name: '项目',
  status: 'OPEN',
  specVersions: [spec, { ...spec, specVersionId: 'old', versionNo: 0 }],
};
const ready = { batchId: 'batch', status: 'READY' };
const row = {
  sampleId: 'sample',
  specVersionId: 'spec',
  reviewStatus: 'ACCEPTED',
  resultId: 'answer',
  selectedResultId: 'answer',
  activeProcessing: false,
};
const textOf = (node) =>
  (node?.props?.children || [])
    .map((item) => (typeof item === 'object' ? textOf(item) : item))
    .join('');

test('publishing is a gated three step flow; explicit data step does not jump on polling', () => {
  assert.equal(publishingStep({ specVersions: [] }, ready, '?step=publish'), 0);
  assert.equal(publishingStep(project, undefined, '?panel=publish'), 1);
  assert.equal(publishingStep(project, ready, '?panel=publish'), 2);
  assert.equal(publishingStep(project, ready, '?step=data&panel=publish'), 1);
  assert.equal(publishingStep(project, ready, '?step=spec'), 0);
  assert.equal(publishingStep(project, undefined, '?step=publish'), 2);
});

test('scope isolation and finalization prevent wrong-spec, unreviewed, superseded or active answers', () => {
  assert.deepEqual(
    JSON.parse(
      JSON.stringify(
        selectedForSpec([row, { ...row, specVersionId: 'old' }], 'spec'),
      ),
    ),
    [row],
  );
  assert.equal(canFinalize([]), false);
  assert.equal(canFinalize([row]), true);
  for (const difference of [
    { reviewStatus: 'PENDING' },
    { resultId: null },
    { selectedResultId: 'other' },
    { activeProcessing: true },
  ]) {
    assert.equal(canFinalize([{ ...row, ...difference }]), false);
  }
});

test('both project breadcrumbs are real links, including encoded project routes', () => {
  const r = renderHooks(
    new URL('./ProjectBreadcrumbs.tsx', import.meta.url),
    { projectId: 'project/a' },
    {
      '@umijs/max': { Link: 'Link' },
      './index.less': { default: { eyebrow: 'eyebrow' } },
    },
  );
  r.render();
  assert.deepEqual(
    r.nodes('Link').map((node) => [textOf(node), node.props.to]),
    [
      ['数据标注平台', '/labeling'],
      ['任务发布', '/labeling/projects/project%2Fa?panel=publish'],
    ],
  );
});

function host(search = '') {
  const location = { search },
    calls = [];
  const dependencies = {
    '@umijs/max': {
      Link: 'Link',
      useLocation: () => location,
      history: {
        push(value) {
          calls.push(value);
          location.search = value.includes('?')
            ? value.slice(value.indexOf('?'))
            : '';
        },
        replace(value) {
          location.search = value.includes('?')
            ? value.slice(value.indexOf('?'))
            : '';
        },
      },
    },
    antd: Object.fromEntries(
      [
        'Alert',
        'Button',
        'Card',
        'Collapse',
        'Empty',
        'Select',
        'Space',
        'Tabs',
        'Tag',
      ].map((name) => [name, name]),
    ),
  };
  for (const name of [
    'LiveAi',
    'LivePublish',
    'LiveResults',
    'LiveRounds',
    'LiveSnapshots',
    'ProjectBreadcrumbs',
    'SubmissionReview',
  ])
    dependencies[`./${name}`] = { default: name };
  const r = renderHooks(
    new URL('./ProjectReviewWorkbench.tsx', import.meta.url),
    { project },
    dependencies,
  );
  return { r, location, calls };
}

test('workbench loads only visited panels and preserves selected scope across review, republish and AI tabs', () => {
  const { r } = host();
  r.render();
  assert.equal(r.nodes('LiveResults').length, 1);
  for (const panel of [
    'LiveAi',
    'LivePublish',
    'LiveSnapshots',
    'SubmissionReview',
  ])
    assert.equal(r.nodes(panel).length, 0);
  r.nodes('LiveResults')[0].props.onSelected([row]);
  r.render();
  r.nodes('Tabs')[0].props.onChange('publish');
  r.render();
  assert.equal(r.nodes('LivePublish')[0].props.selected.length, 1);
  r.nodes('Tabs')[0].props.onChange('ai');
  r.render();
  assert.equal(r.nodes('LiveAi')[0].props.selected[0].sampleId, 'sample');
  assert.equal(r.nodes('LivePublish')[0].props.selected.length, 1);
  assert.equal(r.nodes('LiveSnapshots').length, 0);
});

test('direct AI links do not secretly fetch or reset results; busy writes lock spec and tabs', () => {
  const { r, calls } = host('?view=ai');
  r.render();
  assert.equal(r.nodes('LiveResults').length, 0);
  r.nodes('LiveAi')[0].props.onBusyChange(true);
  r.render();
  assert.equal(r.nodes('Select')[0].props.disabled, true);
  assert.ok(r.nodes('Tabs')[0].props.items.every((item) => item.disabled));
  r.nodes('Tabs')[0].props.onChange('review');
  assert.equal(calls.length, 0);
});

test('switching spec clears scope and unconfirmed selections cannot open save-as-final', () => {
  const { r } = host();
  r.render();
  r.nodes('LiveResults')[0].props.onSelected([
    { ...row, reviewStatus: 'PENDING' },
  ]);
  r.render();
  assert.equal(
    r.nodes('Button').find((node) => textOf(node) === '保存为成果').props
      .disabled,
    true,
  );
  r.nodes('Select')[0].props.onChange('old');
  r.render();
  assert.equal(r.nodes('LiveResults')[0].props.selected.length, 0);
});

test('an outdated republish preview response cannot re-enable a plan for a different selected scope', async () => {
  let resolvePlan;
  const props = { projectId: 'p', spec, selected: [row], onChanged() {} };
  const r = renderHooks(new URL('./LivePublish.tsx', import.meta.url), props, {
    antd: {
      Alert: 'Alert',
      App: { useApp: () => ({ message: { success() {} }, modal: {} }) },
      Button: 'Button',
      Card: 'Card',
      Checkbox: 'Checkbox',
      Input: 'Input',
      InputNumber: 'InputNumber',
      Space: 'Space',
      Table: 'Table',
    },
    '@/services/labeling': {
      operationKey: () => 'key',
      describeLabelingError: (e) => e.message,
    },
    '@/services/labelingOwner': {
      assertChosen() {},
      selectionItem: (value) => value,
      publishSamples: (values) => values,
      ownerApi: {
        select: async () => ({ selectionId: 'scope' }),
        planSelection: () =>
          new Promise((resolve) => {
            resolvePlan = resolve;
          }),
      },
    },
    '@/services/labelingTasks': { tasks: {} },
  });
  r.render();
  r.nodes('Button')
    .find((node) => textOf(node) === '预览分包')
    .props.onClick();
  await flushHooks();
  props.selected = [{ ...row, sampleId: 'different' }];
  r.render();
  resolvePlan({
    planId: 'old',
    status: 'DRAFT',
    packageSampleCounts: [1],
    packages: [],
  });
  await flushHooks();
  r.render();
  assert.equal(
    r.nodes('Button').some((node) => textOf(node) === '确认发布此预览'),
    false,
  );
});
