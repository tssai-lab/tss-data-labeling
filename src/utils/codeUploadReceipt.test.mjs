import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildCodeUploadReceipt,
  persistSuccessfulCodeUpload,
  shouldFallbackToLegacyCodeUpload,
} from './codeUploadReceipt.mjs';

test('successful training-page upload is recorded for the code list', () => {
  const saved = [];
  const receipt = persistSuccessfulCodeUpload(
    {
      success: true,
      data: {
        codeVersionId: ' code-version-1 ',
        codeAssetId: 'code-asset-1',
        fileName: 'train.zip',
        trainingProfile: 'gpu-yolo',
        approvalStatus: 'PENDING',
        sizeBytes: 123,
      },
    },
    { codeName: 'YOLO 训练代码', fileName: 'fallback.zip' },
    (value) => saved.push(value),
  );

  assert.equal(saved.length, 1);
  assert.deepEqual(saved[0], receipt);
  assert.deepEqual(receipt, {
    codeVersionId: 'code-version-1',
    codeAssetId: 'code-asset-1',
    codeAssetName: 'YOLO 训练代码',
    fileName: 'train.zip',
    trainingProfile: 'gpu-yolo',
    approvalStatus: 'PENDING',
    storagePath: undefined,
    sizeBytes: 123,
    source: 'upload',
  });
});

test('server values win and missing display fields use upload metadata', () => {
  assert.deepEqual(
    buildCodeUploadReceipt(
      {
        codeVersionId: 'version-2',
        approvalStatus: 'APPROVED',
      },
      {
        codeName: '通用训练代码',
        fileName: 'portable.zip',
        trainingProfile: 'nlp-bert',
      },
    ),
    {
      codeVersionId: 'version-2',
      codeAssetId: undefined,
      codeAssetName: '通用训练代码',
      fileName: 'portable.zip',
      trainingProfile: 'nlp-bert',
      approvalStatus: 'APPROVED',
      storagePath: undefined,
      sizeBytes: undefined,
      source: 'upload',
    },
  );
});

test('failed uploads and responses without a version id never enter the list', () => {
  const saved = [];
  assert.equal(
    persistSuccessfulCodeUpload(
      { success: false, data: { codeVersionId: 'failed-version' } },
      { codeName: '失败代码' },
      (value) => saved.push(value),
    ),
    undefined,
  );
  assert.equal(
    persistSuccessfulCodeUpload(
      { success: true, data: {} },
      { codeName: '缺少编号' },
      (value) => saved.push(value),
    ),
    undefined,
  );
  assert.equal(saved.length, 0);
});

test('browser storage failure never turns a saved upload into a retry', () => {
  assert.doesNotThrow(() =>
    persistSuccessfulCodeUpload(
      {
        success: true,
        data: { codeVersionId: 'server-saved-version' },
      },
      { codeName: '已由服务端保存' },
      () => {
        throw new Error('localStorage unavailable');
      },
    ),
  );
});

test('legacy upload is used only when the V2 endpoint is explicitly unavailable', () => {
  assert.equal(
    shouldFallbackToLegacyCodeUpload({ response: { status: 404 } }),
    true,
  );
  assert.equal(
    shouldFallbackToLegacyCodeUpload({ info: { status: 405 } }),
    true,
  );
  assert.equal(shouldFallbackToLegacyCodeUpload({ status: 501 }), true);
});

test('ambiguous failures never replay an upload through the legacy endpoint', () => {
  for (const error of [
    new Error('timeout'),
    { response: { status: 400 } },
    { response: { status: 409 } },
    { response: { status: 500 } },
    { response: { status: 503 } },
  ]) {
    assert.equal(shouldFallbackToLegacyCodeUpload(error), false);
  }
});
