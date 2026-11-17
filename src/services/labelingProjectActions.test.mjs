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
    './labelingOwner': { ownerApi: {} },
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

test('draft deletion sends the actual DELETE contract with revision and reason, and preserves failures', async () => {
  const writes = [];
  const api = loadTypeScriptModule(entry, {
    './labeling': { LabelingError: class extends Error {} },
    './labelingOwner': {
      ownerApi: {
        state: async (...args) => {
          writes.push(args);
          return { deleted: true };
        },
      },
    },
  });
  assert.equal(
    (await api.deleteDraftProject(draft, '  清理草稿  ', 'key')).deleted,
    true,
  );
  assert.deepEqual(JSON.parse(JSON.stringify(writes)), [
    ['p/one', 'delete', { baseRevision: 3, reason: '清理草稿' }, 'key'],
  ]);
  for (const status of ['OPEN', 'PAUSED', 'ARCHIVED'])
    assert.throws(() =>
      api.deleteDraftProject({ ...draft, status }, 'reason', 'key'),
    );
  assert.throws(() => api.deleteDraftProject(draft, '   ', 'key'));
  assert.equal(writes.length, 1);
  const failure = new Error('network');
  const failed = loadTypeScriptModule(entry, {
    './labeling': {},
    './labelingOwner': {
      ownerApi: {
        state: async () => {
          throw failure;
        },
      },
    },
  });
  await assert.rejects(
    failed.deleteDraftProject(draft, 'reason', 'key'),
    (e) => e === failure,
  );
});
