import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

const entry = new URL('./labelingProjectActions.ts', import.meta.url);
const draft = {
  projectId: 'p/one',
  name: '草稿',
  status: 'DRAFT',
  revision: 3,
};
function setup(read) {
  const calls = [];
  class LabelingError extends Error {
    constructor(message, status, code) {
      super(message);
      Object.assign(this, { status, code });
    }
  }
  const api = loadTypeScriptModule(entry, {
    './labeling': {
      LabelingError,
      labelingJson: async (...args) => {
        calls.push(args);
        return read();
      },
    },
  });
  return { api, calls };
}

test('草稿进入发布准备，开放和只读项目进入结果工作台，路由编码资源ID', () => {
  const { api } = setup(() => draft);
  assert.equal(
    api.projectEntryPath(draft),
    '/labeling/projects/p%2Fone?panel=publish',
  );
  for (const status of ['OPEN', 'PAUSED', 'ARCHIVED']) {
    assert.equal(
      api.projectEntryPath({ ...draft, status }),
      '/labeling/projects/p%2Fone/workbench',
    );
  }
});

test('非草稿不能删除，也不会调用网络', async () => {
  const { api, calls } = setup(() => draft);
  for (const status of ['OPEN', 'PAUSED', 'ARCHIVED', 'UNKNOWN']) {
    await assert.rejects(api.previewDraftDeletion({ ...draft, status }), {
      code: 'PROJECT_NOT_DRAFT',
    });
  }
  assert.equal(calls.length, 0);
});

test('确认删除前重查项目；状态或版本变化拒绝预览删除', async () => {
  for (const [current, code] of [
    [{ ...draft, status: 'OPEN' }, 'PROJECT_NOT_DRAFT'],
    [{ ...draft, revision: 4 }, 'REVISION_CONFLICT'],
    [{ ...draft, projectId: 'another' }, 'PROJECT_MISMATCH'],
  ]) {
    const { api } = setup(() => current);
    await assert.rejects(api.previewDraftDeletion(draft), { code });
  }
});

test('合法草稿只产生标明预览的回执，不发送猜测的删除接口', async () => {
  const { api, calls } = setup(() => draft);
  const result = await api.previewDraftDeletion(draft);
  assert.equal(result.mode, 'preview');
  assert.equal(result.projectId, draft.projectId);
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], '/projects/p%2Fone');
  assert.equal(calls[0].length, 1);
});

test('权限及网络错误保留，不自动伪造删除成功', async () => {
  for (const status of [403, 404, 503]) {
    const failure = Object.assign(new Error('读取失败'), { status });
    const { api } = setup(() => {
      throw failure;
    });
    await assert.rejects(
      api.previewDraftDeletion(draft),
      (error) => error === failure,
    );
  }
});
