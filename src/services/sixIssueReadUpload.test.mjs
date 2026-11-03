import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

const progress = (status = 'UPLOADING') => ({ uploadId: 'u1', status, fileName: 'data.zip',
  fileSize: 1, chunkSize: 1, totalChunks: 1, uploadedChunks: 1, uploadedBytes: 1,
  uploadedPartIndexes: [0], datasetId: 'asset1', workspaceId: 'version1' });
function service(file, request) {
  return loadTypeScriptModule(new URL(file, import.meta.url), {
    '@umijs/max': { request }, '@/utils/authFileDownload': {},
  }, { setTimeout: fn => { fn(); return 1; }, clearTimeout() {} });
}
const operations = [
  ['datasetUploadInit', [{ fileName: 'data.zip', fileSize: 1, datasetName: 'd', type: 'OTHER' }]],
  ['datasetUploadChunk', ['u1', 0, new Blob(['x'])]],
  ['datasetUploadProgress', ['u1']],
  ['datasetUploadComplete', ['u1']],
];
for (const [name, args] of operations) {
  test(`${name}: 拒绝、超时及业务 404 不触发旧接口`, async () => {
    for (const error of [400, 401, 403, 409, 429, 500, 503].map(status => ({ response: { status } }))
      .concat([new Error('timeout'), { response: { status: 404, data: { errorCode: 'UPLOAD_NOT_FOUND' } } }])) {
      let calls = 0;
      const api = service('./dataset.ts', async () => { calls++; throw error; });
      await assert.rejects(api[name](...args), e => e === error);
      assert.equal(calls, 1);
    }
  });
  test(`${name}: 真正不支持的端点才回退，且必须校验旧回执`, async () => {
    for (const status of [404, 405, 501]) {
      const calls = [];
      const api = service('./dataset.ts', async url => {
        calls.push(url); if (calls.length === 1) throw { response: { status } };
        return { data: progress(name === 'datasetUploadComplete' ? 'COMPLETED' : 'UPLOADING') };
      });
      const result = await api[name](...args);
      assert.equal(result.data.uploadId, 'u1'); assert.equal(calls.length, 2);
      assert.ok(!calls[1].startsWith('/v2/'));
    }
  });
  test(`${name}: 空对象/失败回执/缺少状态/错会话不冒充成功`, async () => {
    const invalid = [{}, { data: null }, { success: false, data: progress() }, { data: { uploadId: 'u1' } }];
    if (name !== 'datasetUploadInit') invalid.push({ data: { ...progress(), uploadId: 'other' } });
    for (const raw of invalid) {
      let calls = 0;
      const api = service('./dataset.ts', async () => { calls++; return raw; });
      await assert.rejects(api[name](...args), /回执|上传请求/); assert.equal(calls, 1);
    }
  });
}
test('上传完成超时后只查询，不重复 complete；V2 展示状态不能代替上传状态', async () => {
  const calls = [];
  const api = service('./dataset.ts', async (url, options) => {
    calls.push([url, options.method]);
    if (options.method === 'POST') throw new Error('timeout');
    return { data: { ...progress('COMPLETED'), displayStatus: 'IMPORTING' } };
  });
  const res = await api.datasetUploadCompleteWithPolling('u1');
  assert.equal(res.data.status, 'COMPLETED'); assert.equal(res.data.datasetVersionId, 'version1');
  assert.equal(calls.filter(c => c[1] === 'POST').length, 1);
});
test('明确拒绝和畸形 complete 不使用查询掩盖错误；终态失败不宣告成功', async () => {
  for (const raw of [{}, { data: progress('FAILED') }, { data: progress('DISCARDED') }]) {
    let calls = 0; const api = service('./dataset.ts', async () => { calls++; return raw; });
    await assert.rejects(api.datasetUploadCompleteWithPolling('u1')); assert.equal(calls, 1);
  }
  for (const status of [401, 403, 409, 429]) {
    let calls = 0; const api = service('./dataset.ts', async () => { calls++; throw { response: { status } }; });
    await assert.rejects(api.datasetUploadCompleteWithPolling('u1')); assert.equal(calls, 1);
  }
});
test('分片回执拒绝计数越界、重复索引和非法数值', () => {
  const { normalizeDatasetUploadProgress: normalize } = service('./datasetUploadResponse.ts', () => {});
  for (const patch of [{ fileSize: 0 }, { totalChunks: 0 }, { chunkSize: NaN }, { uploadedBytes: 2 },
    { uploadedPartIndexes: [0, 0] }, { uploadedPartIndexes: [-1] }, { uploadedChunks: 0 }]) {
    assert.throws(() => normalize({ data: { ...progress(), ...patch } }, 'u1'), /回执/);
  }
});
test('模型读取：失败不伪装为空，也不访问旧端点', async () => {
  for (const [name,args] of [['fetchModelConsumerManifest',['v']],['listModelCodeFiles',['v']],['previewModelCode',['v','a.py']],['fetchModelVersionCodePreview',['v']]]) {
    for (const failure of [{ response: { status: 403 } }, { response: { status: 500 } }, new Error('timeout')]) {
      let calls=0; const api=service('./model.ts',async()=>{ calls++;throw failure; });
      await assert.rejects(api[name](...args), e=>e===failure);assert.equal(calls,1);
    }
  }
});
test('模型读取：真正空列表/空文件可以展示，畸形数据不能展示为代码', async () => {
  const normalizer = service('./modelReadResponse.ts', () => {});
  assert.equal(normalizer.modelFiles({ data: [] }).length, 0);
  assert.equal(normalizer.modelPreview({ data: { path: 'a.py', content: '' } }, 'a.py').content, '');
  assert.equal(normalizer.modelPreview({ data: 'hello' }, 'a.py').content, 'hello');
  for (const raw of [{}, { data: {} }, { data: null }, { success: false, data: [] }]) {
    assert.throws(() => normalizer.modelFiles(raw)); assert.throws(() => normalizer.modelPreview(raw, 'a.py'));
  }
  assert.throws(() => normalizer.modelPreview({ data: { path: 'b.py', content: 'secret' } }, 'a.py'), /不匹配/);
});
test('消费清单返回其他版本必须拒绝', async () => {
  const api=service('./model.ts',async()=>({ data: { modelVersionId: 'other' } }));
  await assert.rejects(api.fetchModelConsumerManifest('v'), /不匹配/);
});

test('嵌套业务失败和不同请求库错误结构都不被隐藏', async () => {
  const normalizer = service('./modelReadResponse.ts', () => {});
  assert.throws(() => normalizer.modelPreview({data:{success:false,content:''}}, 'a.py'), /失败/);
  assert.throws(() => normalizer.modelFiles({data:{errorCode:'DENIED',files:[]}}), /失败/);
  const api = service('./model.ts',async()=>({success:false,data:{id:'v'}}));
  await assert.rejects(api.fetchModelVersionCodePreview('v'), /失败/);
  const {mayReconcileDatasetUpload} = service('./datasetUploadResponse.ts',()=>{});
  for(const error of [{info:{status:403}},{status:429},{errorCode:'DENIED'},{info:{errorCode:'UPLOAD_BAD'}}]) {
    assert.equal(mayReconcileDatasetUpload(error), false);
  }
});
