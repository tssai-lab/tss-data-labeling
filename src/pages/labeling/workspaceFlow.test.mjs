import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../../scripts/qa/load-typescript-module.mjs';

// Execute the real components without a browser or signed-in session.
// Only React's hook boundary, UI primitives and network services are replaced.
function renderer(file, props, dependencies = {}, storage = new Map()) {
  const hooks = [];
  let cursor = 0;
  let effects = [];
  let tree;
  const react = {
    createElement: (type, attributes, ...children) => ({
      type,
      props: { ...attributes, children: children.flat(Infinity) },
    }),
    useState(initial) {
      const index = cursor++;
      if (!(index in hooks))
        hooks[index] = typeof initial === 'function' ? initial() : initial;
      return [
        hooks[index],
        (value) => {
          hooks[index] =
            typeof value === 'function' ? value(hooks[index]) : value;
        },
      ];
    },
    useRef(initial) {
      const index = cursor++;
      if (!(index in hooks)) hooks[index] = { current: initial };
      return hooks[index];
    },
    useEffect(effect, deps) {
      const index = cursor++;
      if (!hooks[index] || deps.some((dep, i) => dep !== hooks[index][i])) {
        hooks[index] = deps;
        effects.push(effect);
      }
    },
  };
  const entry = new URL(file, import.meta.url);
  const { default: Component } = loadTypeScriptModule(
    entry,
    {
      react,
      './index.less': new Proxy({}, { get: (_, key) => key }),
      ...dependencies,
    },
    {
      React: react,
      AbortController,
      localStorage: {
        getItem: (key) => storage.get(key) ?? null,
        setItem: (key, value) => storage.set(key, value),
        removeItem: (key) => storage.delete(key),
      },
    },
  );
  return {
    props,
    render(runEffects = true) {
      cursor = 0;
      effects = [];
      tree = Component(props);
      if (runEffects) for (const effect of effects) effect();
      return tree;
    },
    nodes(type) {
      const result = [];
      function visit(node) {
        if (!node || typeof node !== 'object') return;
        if (node.type === type) result.push(node);
        for (const child of node.props?.children || []) visit(child);
      }
      visit(tree);
      return result;
    },
  };
}
const text = (node) =>
  typeof node === 'string'
    ? node
    : (node?.props?.children || []).map(text).join('');
const flush = () => new Promise((resolve) => setImmediate(resolve));

test('全局导航去除重复和旧入口，审核与导出只在项目内出现', () => {
  const location = { pathname: '/labeling', search: '' };
  const runtime = renderer(
    './Navigation.tsx',
    {},
    {
      '@ant-design/icons': new Proxy({}, { get: (_, key) => key }),
      '@umijs/max': {
        Link: 'Link',
        useLocation: () => location,
        useModel: () => ({ initialState: {} }),
      },
      '@/components': {
        AvatarDropdown: 'AvatarDropdown',
        AvatarName: 'AvatarName',
      },
      '@/services/labelingTasks': { tasks: {} },
    },
  );
  runtime.render(false);
  assert.deepEqual(
    runtime
      .nodes('Link')
      .filter((node) => node.props['aria-current'] === 'page')
      .map(text),
    ['任务发布'],
  );
  for (const label of ['项目数据', '旧版 Demo', '结果审核', '成果导出']) {
    assert.equal(
      runtime.nodes('Link').some((node) => text(node) === label),
      false,
      label,
    );
  }
  location.pathname = '/labeling/projects/p%2Fone/workbench';
  runtime.render(false);
  assert.equal(
    runtime.nodes('Link').find((node) => text(node) === '结果审核').props.to,
    '/labeling/projects/p%2Fone/workbench',
  );
  assert.equal(
    runtime.nodes('Link').find((node) => text(node) === '成果导出').props.to,
    '/labeling/projects/p%2Fone/workbench?view=export',
  );
});

test('品牌包含 TSS / AI PLATFORM，点击返回平台选择', () => {
  const runtime = renderer(
    './PlatformBrand.tsx',
    {},
    { '@umijs/max': { Link: 'Link' } },
  );
  runtime.render(false);
  const brand = runtime.nodes('Link')[0];
  assert.equal(brand.props.to, '/platforms');
  assert.match(text(brand), /TSS.*\/.*AI PLATFORM.*数据标注平台/);
});

function publisher(overrides = {}, saved = new Map()) {
  const calls = [];
  let published = 0;
  let plan = {
    planId: 'plan-1',
    status: 'DRAFT',
    sampleCount: 12,
    packageSampleCounts: [10, 2],
    totalWorkload: 12,
    selectionHash: 'fixed-hash',
    packages: [],
  };
  const tasks = {
    plan: async (...args) => {
      calls.push(['plan', ...args]);
      return plan;
    },
    publish: async (...args) => {
      calls.push(['publish', ...args]);
      plan = { ...plan, status: 'PUBLISHED' };
      return plan;
    },
    getPlan: async (...args) => {
      calls.push(['getPlan', ...args]);
      return plan;
    },
    ...overrides,
  };
  const Form = Object.assign('Form', {
    useForm: () => [
      {
        validateFields: async () => ({
          packageSize: 10,
          annotatorsPerSample: 1,
        }),
      },
    ],
  });
  const props = {
    projectId: 'project-1',
    batchId: 'batch-1',
    specVersionId: 'spec-1',
    writable: true,
    onPublished: () => {
      published++;
    },
  };
  const runtime = renderer(
    './PublishPlan.tsx',
    props,
    {
      '@umijs/max': { Link: 'Link' },
      antd: {
        Alert: 'Alert',
        Button: 'Button',
        Card: 'Card',
        Form,
        InputNumber: 'InputNumber',
        Space: 'Space',
        Table: 'Table',
        Typography: { Paragraph: 'Paragraph', Text: 'Text' },
      },
      '@/services/labeling': {
        describeLabelingError: (error) => error.message,
        operationKey: () => 'stable-operation-key',
      },
      '@/services/labelingTasks': { tasks },
    },
    saved,
  );
  const button = (label) =>
    runtime.nodes('Button').find((node) => text(node) === label);
  runtime.render();
  return {
    runtime,
    tasks,
    props,
    calls,
    button,
    form: () => runtime.nodes(Form)[0],
    published: () => published,
    async preview() {
      runtime.nodes(Form)[0].props.onFinish();
      await flush();
      runtime.render();
    },
  };
}

test('草稿可预览 12 张为 10+2 包；重复点击只发送一个预览请求', async () => {
  const p = publisher();
  const form = p.form();
  form.props.onFinish();
  form.props.onFinish();
  await flush();
  p.runtime.render();
  assert.equal(p.calls.filter(([method]) => method === 'plan').length, 1);
  const body = p.calls[0][2];
  assert.equal(body.batchId, 'batch-1');
  assert.equal(body.specVersionId, 'spec-1');
  assert.equal(body.annotatorsPerSample, 1);
  assert.equal(p.button('确认发布此预览').props.disabled, false);
});

test('缺规范或只读项目不能生成预览，更换规范后旧预览不能发布', async () => {
  for (const change of [{ specVersionId: undefined }, { writable: false }]) {
    const p = publisher();
    Object.assign(p.props, change);
    p.runtime.render();
    await p.preview();
    assert.equal(p.calls.length, 0);
  }
  const p = publisher();
  await p.preview();
  p.props.specVersionId = 'spec-2';
  p.runtime.render();
  assert.equal(p.button('确认发布此预览').props.disabled, true);
  p.button('确认发布此预览').props.onClick();
  await flush();
  assert.equal(
    p.calls.some(([method]) => method === 'publish'),
    false,
  );
  p.button('调整打包参数').props.onClick();
  p.runtime.render();
  await p.preview();
  assert.equal(p.calls.at(-1)[2].specVersionId, 'spec-2');
  assert.equal(p.button('确认发布此预览').props.disabled, false);
});

test('发布成功但读回失败仍保留已发布回执，重复点击不会重复发布', async () => {
  const p = publisher({
    getPlan: async () => {
      throw new Error('读回暂时失败');
    },
  });
  await p.preview();
  const confirm = p.button('确认发布此预览');
  confirm.props.onClick();
  confirm.props.onClick();
  await flush();
  p.runtime.render();
  assert.equal(p.calls.filter(([method]) => method === 'publish').length, 1);
  assert.equal(p.published(), 1);
  assert.equal(p.button('确认发布此预览'), undefined);
  assert.ok(
    p.runtime
      .nodes('Alert')
      .some((node) =>
        node.props.message.includes('任务已发布，读取最新包列表失败'),
      ),
  );
});

test('预览失败后可按原参数和幂等键重试，不假称成功', async () => {
  const requests = [];
  const p = publisher({
    plan: async (...args) => {
      requests.push(args);
      throw new Error('存储暂时不可用');
    },
  });
  await p.preview();
  await p.preview();
  assert.equal(requests.length, 2);
  assert.equal(requests[0][2], requests[1][2]);
  assert.equal(p.button('确认发布此预览'), undefined);
  assert.ok(
    p.runtime
      .nodes('Alert')
      .some((node) => node.props.message === '存储暂时不可用'),
  );
});
