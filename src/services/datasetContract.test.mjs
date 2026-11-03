import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

// 从兼容入口执行真实服务调用链；网络只返回本用例声明的回执。
const load = request => loadTypeScriptModule(new URL('./dataset.ts', import.meta.url), {
  '@umijs/max': { request }, '@/utils/authFileDownload': {},
});
const receipt = patch => ({ uploadId: 'u/1', status: 'UPLOADING', fileName: 'data.zip',
  fileSize: 4, chunkSize: 2, totalChunks: 2, uploadedChunks: 1, uploadedBytes: 2,
  uploadedPartIndexes: [1], datasetId: 'asset-1', workspaceId: 'draft-1', ...patch });

test('数据集公开上传：恢复已完成分片，只传缺片，随后只完成一次', async () => {
  const calls = [], progress = [], sessions = [];
  const api = load(async (url, options) => {
    calls.push([url, options.method]);
    assert.equal(options.timeout, 300000);
    if (url.endsWith('/init')) {
      assert.equal(options.data.assetId, 'asset-1');
      assert.equal(options.data.fileFingerprint, 'fixed-fingerprint');
      return { data: receipt() };
    }
    if (url.endsWith('/chunks')) {
      assert.equal(options.data.get('partIndex'), '0');
      assert.equal(await options.data.get('file').text(), 'ab');
      return { data: receipt({ uploadedChunks: 2, uploadedBytes: 4, uploadedPartIndexes: [0, 1] }) };
    }
    assert.ok(url.endsWith('/complete'));
    return { data: receipt({ status: 'COMPLETED' }) };
  });
  const result = await api.uploadDataset({ name: 'sample', files: [new File(['abcd'], 'data.zip')],
    type: 'OTHER', assetId: 'asset-1', fileFingerprint: 'fixed-fingerprint',
    onProgress: value => progress.push(value), onUploadSession: value => sessions.push(value) });
  assert.deepEqual(calls, [['/v2/dataset-uploads/init', 'POST'], ['/v2/dataset-uploads/u%2F1/chunks', 'POST'], ['/v2/dataset-uploads/u%2F1/complete', 'POST']]);
  assert.equal(result.data.assetId, 'asset-1');
  assert.equal(result.data.datasetVersionId, 'draft-1');
  assert.equal(sessions.length, 1);
  assert.equal(sessions[0].uploadId, 'u/1');
  assert.ok(progress.every(value => value === 100));
});

test('数据集公开上传：分片失败不会继续完成或重放上传', async () => {
  const calls = [], failure = new Error('network unavailable');
  const api = load(async (url) => {
    calls.push(url);
    if (url.endsWith('/init')) return { data: receipt() };
    throw failure;
  });
  await assert.rejects(api.uploadDataset({ name: 'sample', files: [new File(['abcd'], 'data.zip')], type: 'OTHER' }), error => error === failure);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(url => !url.endsWith('/complete')));
});

test('文件夹上传保留文件顺序与相对路径，返回值不另造成功', async () => {
  const result = { data: { uploadId: 'folder', status: 'COMPLETED' } };
  const api = load(async (url, options) => {
    assert.equal(url, '/dataset/upload/folder');
    assert.equal(options.method, 'POST');
    assert.deepEqual(options.data.getAll('paths'), ['images/a.jpg', 'b.jpg']);
    assert.deepEqual(options.data.getAll('files').map(file => file.name), ['a.jpg', 'b.jpg']);
    assert.equal(options.data.get('annotationFormat'), 'YOLO');
    return result;
  });
  assert.equal(await api.datasetUploadFolder({ datasetName: 'images', type: 'CV', annotationFormat: 'YOLO',
    files: [new File(['a'], 'a.jpg'), new File(['b'], 'b.jpg')], paths: ['images/a.jpg'] }), result);
});

test('资产与版本写接口保留编号编码、版本别名和调用方对象', async () => {
  const calls = [], body = { version: 'v2', remark: 'note' };
  const api = load(async (url, options) => { calls.push([url, options]); return { data: {} }; });
  await api.updateDatasetVersion('v/2', body);
  await api.switchDatasetCurrentVersion('a/1', 'v/2');
  await api.updateDatasetVersionStatus('v/2', 'ARCHIVED');
  assert.equal(calls[0][0], '/dataset-versions/v%2F2');
  assert.equal(calls[0][1].method, 'PUT');
  assert.equal(calls[0][1].data.versionLabel, 'v2');
  assert.equal(body.versionLabel, undefined);
  assert.equal(calls[1][0], '/dataset-assets/a%2F1/current-version');
  assert.equal(calls[1][1].data.versionId, 'v/2');
  assert.equal(calls[2][1].method, 'PATCH');
  assert.equal(calls[2][1].data.status, 'ARCHIVED');
});

test('详情按资产编号补元数据，不受列表前 200 条限制', async () => {
  const api = load(async url => {
    if (url === '/dataset-assets/a') return { data: { id: 'a', name: 'asset', type: 'OTHER' } };
    if (url === '/dataset-versions') return { data: [{ id: 'version-1', assetId: 'a', version: 'v1', sizeBytes: 1024 }] };
    assert.equal(url, '/dataset/a/summary');
    return { success: true, data: { assetId: 'a', versionId: 'version-1', workspaceId: 'draft', workspaceRevision: 3, hasDraft: true } };
  });
  const { data } = await api.fetchDatasetDetail('a');
  assert.equal(data.id, 'a');
  assert.equal(data.versions[0].id, 'version-1');
  assert.equal(data.versions[0].size, '1.00 KB');
  assert.equal(data.currentVersionId, 'version-1');
  assert.equal(data.workspaceId, 'draft');
  assert.equal(data.workspaceRevision, 3);
});

test('详情元数据读取失败必须报错，不能被当作没有草稿', async () => {
  const api = load(async url => {
    if (url === '/dataset-assets/a') return { data: { id: 'a', name: 'asset' } };
    if (url === '/dataset-versions') return { data: [] };
    throw new Error('metadata unavailable');
  });
  await assert.rejects(api.fetchDatasetDetail('a'), /metadata unavailable/);
});

test('详情拒绝业务失败、空回执和其他资产的元数据', async () => {
  for (const receipt of [{ success: false, errorMessage: 'denied' }, { data: null }, { data: { assetId: 'other' } }]) {
    const api = load(async url => {
      if (url === '/dataset-assets/a') return { data: { id: 'a', name: 'asset' } };
      if (url === '/dataset-versions') return { data: [] };
      return receipt;
    });
    await assert.rejects(api.fetchDatasetDetail('a'));
  }
});
