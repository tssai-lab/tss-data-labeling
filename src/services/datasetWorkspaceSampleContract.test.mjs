import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

const load = request =>
  loadTypeScriptModule(new URL('./datasetV2.ts', import.meta.url), {
    '@umijs/max': { request },
  });

test('V2 工作区详情保留样本字段并适配数据组件编号', async () => {
  const api = load(async () => ({
    data: {
      sampleId: 'sample-1',
      workspaceId: 'workspace-1',
      externalId: 'camera-1',
      sampleIndex: 7,
      tags: { scene: 'road' },
      metadata: { weather: 'sunny' },
      createdAt: '2026-09-11T00:00:00Z',
      data: [{ dataId: 'data-1', dataType: 'IMAGE', fileName: '1.jpg' }],
      annotations: [{ annotationId: 'annotation-1', format: 'JSON' }],
    },
  }));

  const result = await api.getDatasetWorkspaceSample(
    'workspace-1',
    'sample-1',
  );
  assert.equal(result.data.datasetVersionId, 'workspace-1');
  assert.equal(result.data.sampleIndex, 7);
  assert.deepEqual(result.data.tags, { scene: 'road' });
  assert.deepEqual(result.data.metadata, { weather: 'sunny' });
  assert.equal(result.data.data[0].sampleDataId, 'data-1');
});

test('没有外层回执时不会把样本的数据数组误当成详情', () => {
  const api = load(async () => ({}));
  const detail = api.normalizeDatasetWorkspaceSample({
    sampleId: 'sample-2',
    workspaceId: 'workspace-2',
    data: [{ dataId: 'data-2' }],
    annotations: [],
  });
  assert.equal(detail.sampleId, 'sample-2');
  assert.equal(detail.data[0].sampleDataId, 'data-2');
});
