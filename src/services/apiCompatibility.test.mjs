import assert from 'node:assert/strict';
import test from 'node:test';
import * as receipt from '../utils/codeUploadReceipt.mjs';
import * as compatibility from '../utils/apiCompatibility.mjs';
import * as pagination from './paginatedCandidates.mjs';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

// 执行真实服务源码，只替换网络与无关浏览器依赖；避免仅测试判断函数却漏掉调用处。
function loadService(file, request, codeV2 = {}) {
  const dependencies = {
    '@umijs/max': { request },
    '@/utils/authFileDownload': {},
    '@/constants/trainingCode': {},
    '@/utils/pendingCodeVersions': {},
    '@/utils/codeUploadReceipt.mjs': receipt,
    '@/utils/apiCompatibility.mjs': compatibility,
    './paginatedCandidates.mjs': pagination,
    './codeV2': codeV2,
  };
  return loadTypeScriptModule(new URL(file, import.meta.url), dependencies);
}

const operations = [
  ['modelUploadInit', [{ fileName: 'model.zip', fileSize: 1 }], '/model/upload/init'],
  ['modelUploadChunk', ['upload-1', 0, new Blob(['x'])], '/model/upload/chunk'],
  ['modelUploadProgress', ['upload-1'], '/model/upload/progress'],
  ['modelUploadComplete', [{ uploadId: 'upload-1' }], '/model/upload/complete'],
  ['switchModelCurrentVersion', ['asset-1', 'version-1'], '/model-assets/asset-1/current-version'],
];
const failures = [400, 401, 403, 409, 429, 500, 503].map(status => ({ response: { status } }));
failures.push(new Error('timeout'), new Error('cancelled'), {
  response: { status: 404, data: { success: false, errorCode: 'MODEL_UPLOAD_NOT_FOUND' } },
});

for (const [method, args, legacyUrl] of operations) {
  test(`${method}：业务拒绝、超时和资源不存在不触发第二次请求`, async () => {
    for (const failure of failures) {
      const calls = [];
      const service = loadService('./model.ts', async url => {
        calls.push(url);
        if (url.startsWith('/v2/')) throw failure;
        return { data: {} };
      });
      await assert.rejects(service[method](...args), error => error === failure);
      assert.equal(calls.length, 1);
    }
  });
  test(`${method}：旧服务确实不支持接口时仍可兼容`, async () => {
    for (const status of [404, 405, 501]) {
      const calls = [];
      const result = { data: { uploadId: 'legacy-upload' } };
      const service = loadService('./model.ts', async url => {
        calls.push(url);
        if (url.startsWith('/v2/')) throw { response: { status } };
        return result;
      });
      assert.equal(await service[method](...args), result);
      assert.equal(calls.length, 2);
      assert.equal(calls[1], legacyUrl);
    }
  });
  if (method !== 'switchModelCurrentVersion') {
    test(`${method}：成功响应缺少回执时不重复提交`, async () => {
      const calls = [];
      const service = loadService('./model.ts', async url => {
        calls.push(url);
        return { data: {} };
      });
      await assert.rejects(service[method](...args), /回执/);
      assert.equal(calls.length, 1);
    });
  }
}

test('审批详情读取失败时不绕过详情验证进行写入', async () => {
  for (const failure of failures) {
    let writes = 0;
    const write = async () => { writes++; return {}; };
    const service = loadService('./code.ts', write, {
      getAdminCodeReviewTaskDetail: async () => { throw failure; },
      approveV2CodeVersion: write,
      normalizeV2ApprovalStatus: value => value,
    });
    await assert.rejects(service.decideCodeVersion('version-1', 'APPROVE'), error => error === failure);
    assert.equal(writes, 0);
  }
});

test('V2 审批失败不向旧接口重放审批', async () => {
  for (const failure of failures) {
    let legacyWrites = 0;
    const service = loadService('./code.ts', async () => { legacyWrites++; }, {
      getAdminCodeReviewTaskDetail: async () => { throw { response: { status: 404 } }; },
      approveV2CodeVersion: async () => { throw failure; },
    });
    await assert.rejects(service.decideCodeVersion('version-1', 'APPROVE'), error => error === failure);
    assert.equal(legacyWrites, 0);
  }
});

test('旧后端缺少 V2 审批接口时保留旧审批兼容', async () => {
  const calls = [];
  const missing = async () => { throw { response: { status: 404 } }; };
  const service = loadService('./code.ts', async url => { calls.push(url); return { success: true }; }, {
    getAdminCodeReviewTaskDetail: missing, approveV2CodeVersion: missing,
  });
  assert.equal((await service.decideCodeVersion('version-1', 'APPROVE')).success, true);
  assert.deepEqual(calls, ['/code/version/version-1/approve']);
});

test('V2 上传正常回执只使用一次请求并保留版本编号', async () => {
  for (const [method, args] of operations) {
    let calls = 0;
    const service = loadService('./model.ts', async () => {
      calls++;
      return { data: { uploadId: 'upload-1', modelVersionId: 'version-1' } };
    });
    const result = await service[method](...args);
    assert.equal(calls, 1);
    if (method === 'modelUploadComplete') assert.equal(result.data.id, 'version-1');
    else assert.equal(result.data.uploadId, 'upload-1');
  }
});

test('有 V2 审批证据时沿用证据请求，不访问旧接口', async () => {
  const detail = { riskAssessment: { status: 'COMPLETED' } };
  const body = { decision: 'APPROVE', expectedHash: 'evidence' };
  let approvals = 0;
  const service = loadService('./code.ts', async () => assert.fail('不应调用旧接口'), {
    getAdminCodeReviewTaskDetail: async () => detail,
    hasV2ApprovalEvidence: value => value === detail,
    buildV2ApprovalRequest: value => { assert.equal(value, detail); return body; },
    approveV2CodeVersion: async (id, request) => {
      assert.equal(id, 'version-1'); assert.equal(request, body); approvals++;
      return { approvalStatus: 'APPROVED' };
    },
    normalizeV2ApprovalStatus: value => value,
  });
  assert.equal((await service.decideCodeVersion('version-1', 'APPROVE')).data.approvalStatus, 'APPROVED');
  assert.equal(approvals, 1);
});

test('空详情或证据未就绪不允许提交审批', async () => {
  for (const detail of [undefined, {}]) {
    const service = loadService('./code.ts', async () => assert.fail('不应写入'), {
      getAdminCodeReviewTaskDetail: async () => detail,
      hasV2ApprovalEvidence: () => false,
      approveV2CodeVersion: async () => assert.fail('不应写入'),
    });
    await assert.rejects(service.decideCodeVersion('version-1', 'APPROVE'), /响应为空|证据未就绪/);
  }
});
