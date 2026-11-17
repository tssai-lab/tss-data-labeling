import assert from 'node:assert/strict';
import test from 'node:test';
import {
  flushHooks,
  renderHooks,
} from '../../../scripts/qa/render-hook-component.mjs';

const flatten = (value) =>
  (value?.props?.children || [])
    .map((child) => (typeof child === 'object' ? flatten(child) : child))
    .join('');
const spec = {
  specVersionId: 'v',
  versionNo: 1,
  labels: [{ id: 'cat', name: '猫' }],
  instructions: '',
};
const row = {
  sampleId: 's',
  specVersionId: 'v',
  resultId: 'res',
  reviewRevision: 1,
  sourceKind: 'HUMAN',
  result: { labelId: 'cat' },
  reviewable: true,
  activeProcessing: false,
};
function boundary() {
  const confirms = [],
    messages = [];
  let count = 0;
  const ui = Object.fromEntries(
    [
      'Alert',
      'Button',
      'Card',
      'Checkbox',
      'Drawer',
      'Empty',
      'Input',
      'InputNumber',
      'Progress',
      'Select',
      'Space',
      'Table',
      'Tag',
      'Typography',
    ].map((name) => [name, name]),
  );
  ui.Input = Object.assign(() => {}, {
    TextArea: 'TextArea',
    Search: 'Search',
    Password: 'Password',
  });
  ui.Typography = { Paragraph: 'Paragraph' };
  ui.App = {
    useApp: () => ({
      modal: { confirm: (value) => confirms.push(value) },
      message: { success: (value) => messages.push(value) },
    }),
  };
  const deps = {
    antd: ui,
    '@/services/labeling': {
      operationKey: () => `key-${++count}`,
      describeLabelingError: (e) => e.message,
    },
    './StructuredPreview': { default: 'StructuredPreview' },
    '@/services/labelingStructured': { resultLabel: (r) => r?.labelId },
    './OriginalImage': { default: 'OriginalImage' },
  };
  return { confirms, messages, deps };
}
const click = (renderer, label) => {
  const button = renderer
    .nodes('Button')
    .find((item) => flatten(item) === label);
  assert.ok(button, `missing button: ${label}`);
  assert.equal(Boolean(button.props.disabled), false, `disabled: ${label}`);
  return button.props.onClick();
};

test('lost write responses replay the original key and prepared payload without a second preflight', async () => {
  const { deps, messages } = boundary();
  const r = renderHooks(
    new URL('./useOwnerAction.ts', import.meta.url),
    undefined,
    deps,
  );
  let action = r.render(),
    reads = 0,
    writes = 0;
  const sent = [];
  const send = async (key, prepare) => {
    const body = await prepare(async () => ({ revision: ++reads }));
    sent.push({ key, ...body });
    if (++writes === 1) throw new Error('response lost');
    return { ok: true };
  };
  assert.equal(
    await action.run('review', { sampleId: 's' }, send, 'saved'),
    undefined,
  );
  action = r.render();
  assert.equal(action.error, 'response lost');
  assert.deepEqual(
    await action.run('review', { sampleId: 's' }, send, 'saved'),
    { ok: true },
  );
  assert.equal(reads, 1);
  assert.deepEqual(sent, [
    { key: 'key-1', revision: 1 },
    { key: 'key-1', revision: 1 },
  ]);
  assert.deepEqual(messages, ['saved']);
});
test('write lock prevents double sends before React renders loading state', async () => {
  const { deps } = boundary(),
    r = renderHooks(
      new URL('./useOwnerAction.ts', import.meta.url),
      undefined,
      deps,
    );
  const action = r.render();
  let finish,
    writes = 0;
  const first = action.run(
    'write',
    {},
    async () => {
      writes++;
      await new Promise((resolve) => {
        finish = resolve;
      });
      return {};
    },
    'ok',
  );
  await action.run(
    'write',
    {},
    async () => {
      writes++;
      return {};
    },
    'ok',
  );
  assert.equal(writes, 1);
  finish();
  await first;
});

test('definite expired selection rejection refreshes preparation and its idempotency key', async () => {
  const { deps } = boundary();
  const r = renderHooks(
    new URL('./useOwnerAction.ts', import.meta.url),
    undefined,
    deps,
  );
  let reads = 0;
  const sent = [];
  const send = async (key, prepare) => {
    const body = await prepare(async () => ({
      selectionId: `scope-${++reads}`,
    }));
    sent.push({ key, ...body });
    if (sent.length === 1)
      throw Object.assign(new Error('expired'), {
        status: 410,
        code: 'SELECTION_EXPIRED',
      });
    return {};
  };
  await r.render().run('scope', {}, send, 'saved');
  await r.render().run('scope', {}, send, 'saved');
  assert.equal(reads, 2);
  assert.notEqual(sent[0].key, sent[1].key);
  assert.notEqual(sent[0].selectionId, sent[1].selectionId);
});
test('publication preview invalidates on parameter changes and successful receipt survives selection refresh', async () => {
  const { deps, confirms } = boundary();
  const draft = {
    planId: 'plan',
    status: 'DRAFT',
    selectionHash: 'hash',
    sampleCount: 1,
    packageSampleCounts: [1],
    totalWorkload: 1,
    packages: [],
  };
  let published = 0;
  const props = { projectId: 'p', spec, selected: [row], onChanged() {} };
  const r = renderHooks(new URL('./LivePublish.tsx', import.meta.url), props, {
    ...deps,
    '@/services/labelingOwner': {
      assertChosen() {},
      selectionItem: (item) => item,
      publishSamples: (items) =>
        items.map((item) => ({
          sampleId: item.sampleId,
          referenceResultId: null,
        })),
      ownerApi: { select: async () => ({}), planSelection: async () => draft },
    },
    '@/services/labelingTasks': {
      tasks: {
        publish: async () => {
          published++;
          return {
            ...draft,
            status: 'PUBLISHED',
            packages: [{ packageId: 'pkg', roundId: 'round' }],
          };
        },
      },
    },
  });
  r.render();
  await click(r, '预览分包');
  await flushHooks();
  r.render();
  r.nodes('InputNumber')[0].props.onChange(10);
  r.render();
  r.render();
  assert.equal(
    r.nodes('Button').some((item) => flatten(item) === '确认发布此预览'),
    false,
  );
  await click(r, '预览分包');
  await flushHooks();
  r.render();
  click(r, '确认发布此预览');
  await confirms.at(-1).onOk();
  props.selected = [];
  r.render();
  r.render();
  assert.equal(published, 1);
  assert.equal(
    r.nodes('Button').some((item) => flatten(item) === '确认发布此预览'),
    false,
  );
  assert.ok(
    r
      .nodes('Card')
      .some((item) => item.props.title === '上次重新标注任务已发布'),
  );
  assert.equal(r.nodes('Table')[0].props.dataSource[0].roundId, 'round');
});
test('a fixed preview cannot present partial pages or live statistics as a valid frozen scope', async () => {
  const { deps } = boundary();
  const r = renderHooks(
    new URL('./LiveResults.tsx', import.meta.url),
    {
      projectId: 'p',
      spec,
      selected: [row],
      onSelected() {},
      onChanged() {},
      refresh: 0,
    },
    {
      ...deps,
      './StructuredPreview': { default: 'StructuredPreview' },
      './ReviewWorkbench': {
        default: 'ReviewWorkbench',
        sourceNames: {},
        reviewStatusNames: {},
        confidenceText: () => '未知',
      },
      '@/services/labelingOwner': {
        selectionItem: (item) => item,
        readAllPages: async (fn) => (await fn(null)).items,
        ownerApi: {
          results: async () => ({ items: [row], nextCursor: null }),
          statistics: async () => ({ total: 999 }),
          select: async () => ({
            selectionId: 'frozen',
            count: 2,
            expiresAt: '2099-01-01T00:00:00Z',
          }),
          selectionItems: async () => ({
            items: [{ sampleId: 's' }],
            nextCursor: null,
          }),
          selectionStatistics: async () => ({ total: 2 }),
        },
      },
    },
  );
  r.render();
  await flushHooks();
  r.render();
  await click(r, '创建固定预览');
  await flushHooks();
  r.render();
  assert.ok(
    r.nodes('Alert').some((item) => item.props.message?.includes('数量不一致')),
  );
  assert.equal(
    r.nodes('Table').filter((item) => item.props.dataSource?.length).length,
    0,
  );
});
test('AI reads reject mismatched terminal snapshots and never expose provisional answers for review', async () => {
  const { deps } = boundary();
  let mismatch = true;
  const r = renderHooks(
    new URL('./LiveAi.tsx', import.meta.url),
    {
      projectId: 'p',
      spec,
      selected: [],
      active: true,
      onChanged() {},
      onFilter() {},
    },
    {
      ...deps,
      '@/services/labelingOwner': {
        executionTerminal: (status) => status === 'SUCCEEDED',
        ownerApi: {
          agents: async () => [],
          execution: async () => ({
            executionId: 'e',
            projectId: 'p',
            status: 'SUCCEEDED',
            resultSnapshotId: 'snap',
            sampleCount: 1,
            pendingCount: 0,
          }),
          executionResults: async () => ({
            provisional: false,
            snapshotId: mismatch ? 'wrong' : 'snap',
            items: [
              {
                sampleId: 's',
                result: { labelId: 'cat' },
                confidence: null,
                reviewable: true,
                resultId: 'res',
              },
            ],
            nextCursor: null,
          }),
          policies: async () => [],
        },
      },
    },
  );
  r.render();
  await flushHooks();
  r.render();
  r.nodes(deps.antd.Input)[1].props.onChange({ target: { value: 'e' } });
  r.render();
  await click(r, '读取执行');
  await flushHooks();
  r.render();
  assert.ok(
    r
      .nodes('Alert')
      .some((item) => item.props.message?.includes('一致的结果快照')),
  );
  assert.equal(r.nodes('Table').length, 0);
  mismatch = false;
  await click(r, '读取执行');
  await flushHooks();
  r.render();
  const table = r.nodes('Table')[0],
    confidence = table.props.columns.find(
      (column) => column.title === '置信度',
    );
  assert.equal(confidence.render(null, table.props.dataSource[0]), '未知');
  r.dispose();
});
test('submission modification uses that submission frozen spec, preserves original answers and awaits explicit confirmation', async () => {
  const { deps, confirms } = boundary(),
    writes = [];
  const submission = {
    projectId: 'p',
    specVersionId: 'v',
    submissionId: 'sub',
    items: [{ sampleId: 's', result: { labelId: 'dog' } }],
  };
  const r = renderHooks(
    new URL('./SubmissionReview.tsx', import.meta.url),
    { projectId: 'p', specs: [spec], onChanged() {} },
    {
      ...deps,
      '@/services/labelingTasks': {
        tasks: {
          submissions: async () => ({
            items: [{ submissionId: 'sub' }],
            nextCursor: null,
          }),
          submission: async () => submission,
        },
      },
      '@/services/labelingOwner': {
        readAllPages: async () => [],
        ownerApi: {
          humanReview: async () => ({ projectId: 'p', items: [] }),
          reviewItems: async (id, items, key) => {
            writes.push({ id, items, key });
            return {};
          },
        },
      },
    },
  );
  r.render();
  await flushHooks();
  r.render();
  r.nodes('Select')[0].props.onChange('sub');
  r.render();
  await flushHooks();
  r.render();
  r.nodes('Select')
    .find((item) => item.props['aria-label'] === '人工审核修订标签')
    .props.onChange('cat');
  r.nodes('TextArea')[0].props.onChange({ target: { value: '修正标签' } });
  r.render();
  click(r, '修改并接受');
  assert.equal(writes.length, 0);
  await confirms.at(-1).onOk();
  assert.equal(writes[0].id, 'sub');
  assert.equal(writes[0].items[0].sampleId, 's');
  assert.equal(writes[0].items[0].result.labelId, 'cat');
  assert.equal(submission.items[0].result.labelId, 'dog');
});

test('actual bulk review consumes the prepared REVIEW selection and preserves it when the response is lost', async () => {
  const { deps, confirms } = boundary();
  let selections = 0,
    writes = 0;
  const sent = [];
  const r = renderHooks(
    new URL('./LiveResults.tsx', import.meta.url),
    {
      projectId: 'p',
      spec,
      selected: [row],
      onSelected() {},
      onChanged() {},
      refresh: 0,
    },
    {
      ...deps,
      './ReviewWorkbench': {
        default: 'ReviewWorkbench',
        sourceNames: {},
        reviewStatusNames: {},
        confidenceText: () => '',
      },
      '@/services/labelingOwner': {
        selectionItem: (r) => ({
          sampleId: r.sampleId,
          resultId: r.resultId,
          reviewRevision: r.reviewRevision,
        }),
        ownerApi: {
          results: async () => ({ items: [row], nextCursor: null }),
          statistics: async () => ({ total: 1 }),
          select: async (_p, body) => {
            assert.equal(body.purpose, 'REVIEW');
            assert.equal(body.reviewAction, 'ACCEPT');
            assert.equal(body.items[0].resultId, 'res');
            selections++;
            return { selectionId: 'fixed' };
          },
          atomicReview: async (p, body, key) => {
            sent.push({ p, body, key });
            if (++writes === 1) throw new Error('response lost');
            return { reviewedCount: 1 };
          },
        },
      },
    },
  );
  r.render();
  await flushHooks();
  r.render();
  click(r, '批量确认所选答案');
  await confirms.at(-1).onOk();
  r.render();
  click(r, '批量确认所选答案');
  await confirms.at(-1).onOk();
  assert.equal(selections, 1);
  assert.deepEqual(sent[0], sent[1]);
  assert.deepEqual(JSON.parse(JSON.stringify(sent[1].body)), {
    selectionId: 'fixed',
    action: 'ACCEPT',
    reason: '',
  });
});

test('formal export uses frozen snapshot IDs, waits for server success and downloads by authorized export ID', async () => {
  const { deps, confirms } = boundary();
  const sent = [],
    downloads = [],
    clicked = [];
  const job = {
    exportId: 'export',
    projectId: 'p',
    status: 'QUEUED',
    expiresAt: '2099-01-01T00:00:00Z',
  };
  const r = renderHooks(
    new URL('./LiveExport.tsx', import.meta.url),
    {
      projectId: 'p',
      snapshotIds: ['snap'],
      taskType: 'TIMESERIES_ANNOTATION',
      active: false,
    },
    {
      ...deps,
      '@/services/labelingOwner': {
        ownerApi: {
          export: async (p, body) => {
            sent.push({ p, body });
            return job;
          },
          exportStatus: async () => ({
            ...job,
            status: 'SUCCEEDED',
            sha256: 'hash',
          }),
          downloadExport: async (id) => {
            downloads.push(id);
            return 'binary';
          },
        },
      },
    },
    {
      URL: { createObjectURL: () => 'blob:zip', revokeObjectURL() {} },
      document: {
        createElement: () => ({
          click() {
            clicked.push(this.download);
          },
        }),
      },
      setTimeout: (fn) => fn(),
    },
  );
  r.render();
  await click(r, '导出勾选的 1 份成果');
  await confirms.at(-1).onOk();
  r.render();
  assert.deepEqual(JSON.parse(JSON.stringify(sent)), [
    {
      p: 'p',
      body: {
        finalSnapshotIds: ['snap'],
        format: 'energy-jsonl-zip',
        includeOriginals: false,
      },
    },
  ]);
  assert.equal(
    r.nodes('Button').find((n) => flatten(n) === '下载正式 ZIP').props.disabled,
    true,
  );
  await click(r, '刷新状态');
  await flushHooks();
  r.render();
  await click(r, '下载正式 ZIP');
  await flushHooks();
  assert.deepEqual(downloads, ['export']);
  assert.deepEqual(clicked, ['labeling-export.zip']);
});

test('AI retry targets the immutable original execution with its current revision and activates the new execution', async () => {
  const { deps, confirms } = boundary();
  const sent = [],
    opened = [];
  const execution = {
    executionId: 'old',
    projectId: 'p',
    specVersionId: 'v',
    roundId: 'round',
    revision: 8,
    status: 'PARTIAL',
    sampleCount: 2,
    successCount: 1,
    failedCount: 1,
    pendingCount: 0,
    cancelledCount: 0,
    resultSnapshotId: 'snap',
  };
  const r = renderHooks(
    new URL('./LiveAi.tsx', import.meta.url),
    {
      projectId: 'p',
      spec,
      selected: [],
      active: false,
      onChanged() {},
      onFilter() {},
      onExecution: (id) => opened.push(id),
      initialExecutionId: 'old',
    },
    {
      ...deps,
      '@/services/labelingOwner': {
        executionTerminal: (s) => ['PARTIAL', 'QUEUED'].includes(s),
        ownerApi: {
          agents: async () => [],
          execution: async (id) =>
            id === 'old'
              ? execution
              : { ...execution, executionId: 'new', status: 'QUEUED' },
          executionResults: async () => ({
            items: [],
            nextCursor: null,
            provisional: false,
            snapshotId: 'snap',
          }),
          policies: async () => [],
          retry: async (id, body, key) => {
            sent.push({ id, body, key });
            return { ...execution, executionId: 'new', status: 'QUEUED' };
          },
        },
      },
    },
    {
      setInterval() {
        return 1;
      },
      clearInterval() {},
    },
  );
  r.render();
  await flushHooks();
  r.render();
  click(r, '重试失败范围');
  await confirms.at(-1).onOk();
  assert.equal(sent[0].id, 'old');
  assert.deepEqual(JSON.parse(JSON.stringify(sent[0].body)), {
    baseRevision: 8,
  });
  assert.deepEqual(opened, ['new']);
});
