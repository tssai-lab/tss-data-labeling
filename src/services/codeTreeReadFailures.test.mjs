import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScriptModule } from '../../scripts/qa/load-typescript-module.mjs';

function load(file, dependencies) {
  return loadTypeScriptModule(new URL(file, import.meta.url), dependencies);
}
const service = load('./codeV2.ts', { '@umijs/max': {}, '@/constants/request': {} });
const owner = load('../utils/ownerUserLabel.ts', { react: {}, '@/services/system/user': {} });

test('文件内容：真实空字符串保留，错误和未知对象不得伪装为空文本', () => {
  for (const payload of ['', { content: '' }, { text: '' }]) assert.equal(service.extractV2FileText(payload), '');
  for (const payload of [null, {}, [], { content: 3 }, { success: false, content: '' }, { errorCode: 'DENIED', text: '' }]) assert.throws(() => service.extractV2FileText(payload), /文件.*响应/);
});

function editable(v2) {
  return load('./code.ts', {
    '@umijs/max': { request: async () => assert.fail('不得静默读取旧版本') },
    '@/utils/authFileDownload': {}, '@/constants/trainingCode': {}, '@/utils/pendingCodeVersions': {},
    '@/utils/codeUploadReceipt.mjs': {}, '@/utils/apiCompatibility.mjs': { isLegacyEndpointUnavailable: () => false },
    './paginatedCandidates.mjs': {}, './codeV2': { ...service, ...v2 },
  });
}
test('草稿预览：工作区查询失败或结构异常不能静默换成旧版本', async () => {
  for (const listV2CodeWorkspaces of [async () => { throw new Error('timeout'); }, async () => ({ errorCode: 'DENIED' })]) {
    const s = editable({ listV2CodeWorkspaces });
    for (const method of ['fetchCodeEditablePreview', 'previewCodeEditableFile']) {
      await assert.rejects(s[method]({ codeAssetId: 'asset', codeVersionId: 'version', path: 'a.py' }), /timeout|工作区/);
    }
  }
});
test('草稿预览：文件读取失败保留目录及草稿身份，明确报告失败；单文件不回退', async () => {
  const s = editable({
    listV2CodeWorkspaces: async () => [{ id: 'workspace', status: 'OPEN' }],
    getV2CodeWorkspaceTree: async () => [{ path: 'a.py' }],
    getV2CodeWorkspaceFileContent: async () => { throw new Error('read failed'); },
  });
  const result = await s.fetchCodeEditablePreview({ codeAssetId: 'asset', codeVersionId: 'version' });
  assert.equal(result.data.fromWorkspace, true);
  assert.equal(result.data.codeFiles.length, 1);
  assert.match(result.data.loadError, /read failed/);
  await assert.rejects(s.previewCodeEditableFile({ codeAssetId: 'asset', codeVersionId: 'version', path: 'a.py' }), /read failed/);
});
test('草稿预览：空文本文件是合法内容，必须保留已选文件名和路径', async () => {
  const s = editable({
    listV2CodeWorkspaces: async () => [{ id: 'workspace', status: 'OPEN' }],
    getV2CodeWorkspaceTree: async () => [{ path: 'empty.py' }],
    getV2CodeWorkspaceFileContent: async () => '',
  });
  const result = await s.fetchCodeEditablePreview({ codeAssetId: 'asset', codeVersionId: 'version' });
  assert.equal(result.data.codeContent, ''); assert.equal(result.data.codeFilePath, 'empty.py');
});

test('完整目录：根目录和任意子目录失败必须拒绝，不返回伪完整文件表', async () => {
  for (const atRoot of [true, false]) {
    const failure = new Error('403');
    await assert.rejects(service.fetchAllV2CodeTreeFiles(async prefix => {
      if (atRoot || prefix) throw failure;
      return [{ path: 'a.py' }, { path: 'src', directory: true }];
    }), error => error === failure);
  }
});
test('完整目录：正常空列表及已有封装兼容', async () => {
  for (const payload of [[], { data: [] }, { items: [] }, { data: { children: [] } }]) {
    assert.equal((await service.fetchAllV2CodeTreeFiles(async () => payload)).length, 0);
  }
});
test('完整目录：错误对象、畸形节点及混合字符串不能视为正常空目录', async () => {
  for (const payload of [null, {}, 'oops', { success: false, items: [] }, { code: 403, data: [] }, { errorCode: 'DENIED' }, [null], [{}], ['a.py', 3]]) {
    await assert.rejects(service.fetchAllV2CodeTreeFiles(async () => payload), /目录.*响应/);
  }
});
test('完整目录：内联子目录保持父路径，并继续读取深层未展开目录', async () => {
  const calls = [];
  const result = await service.fetchAllV2CodeTreeFiles(async prefix => {
    calls.push(prefix);
    return prefix ? [{ name: 'train.py' }] : [{ name: 'src', directory: true, children: [
      { name: 'utils', directory: true, children: [{ name: 'nested', directory: true }] }, { name: 'empty.py' },
    ] }];
  });
  assert.equal(JSON.stringify(result.map(item => item.path)), JSON.stringify(['src/empty.py', 'src/utils/nested/train.py']));
  assert.deepEqual(calls, [undefined, 'src/utils/nested']);
});
test('完整目录：深度超限显式失败，不把截断目录用于编辑', async () => {
  await assert.rejects(service.fetchAllV2CodeTreeFiles(async prefix => [{ path: `${prefix || 'src'}/child`, directory: true }], { maxDepth: 1 }), /目录.*深度/);
});
test('完整目录：同一前缀不会无限请求；正常文件去重并保留大小零值', async () => {
  let calls = 0;
  const result = await service.fetchAllV2CodeTreeFiles(async () => {
    calls++;
    return [{ path: 'src', directory: true }, { path: 'zero.py', sizeBytes: 0 }, { path: 'zero.py', sizeBytes: 0 }];
  });
  assert.equal(calls, 2); assert.equal(result.length, 1); assert.equal(result[0].sizeBytes, 0);
});
test('归属筛选：空输入不筛选；数字、唯一精确/模糊匹配仍兼容', () => {
  const map = new Map([[1, 'Alice'], [2, 'Bob']]);
  assert.equal(owner.resolveOwnerUserIdFilter(' ', map), undefined);
  assert.equal(owner.resolveOwnerUserIdFilter('42', map), 42);
  assert.equal(owner.resolveOwnerUserIdFilter('alice', map), 1);
  assert.equal(owner.resolveOwnerUserIdFilter('Bo', map), 2);
});
test('归属筛选：未知、重名、用户目录不可读或超大 ID 不得静默取消筛选', () => {
  for (const [raw, map] of [['nobody', new Map([[1, 'Alice']])], ['ali', new Map([[1, 'Alice'], [2, 'Alina']])], ['alice', new Map([[1, 'Alice'], [2, 'ALICE']])], ['alice', new Map()], ['9007199254740993', new Map()]]) {
    assert.throws(() => owner.resolveOwnerUserIdFilter(raw, map), /用户|ID/);
  }
});
