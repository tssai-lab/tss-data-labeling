import assert from 'node:assert/strict';
import test from 'node:test';

import { requireSavedInferenceScriptVersion } from './inferenceScriptUploadResult.mjs';

test('accepts only a persisted inference script version', () => {
  assert.equal(
    requireSavedInferenceScriptVersion({
      success: true,
      data: { scriptVersionId: ' infer-script-ver-1 ' },
    }),
    'infer-script-ver-1',
  );
});

test('keeps a business failure out of the reusable script list', () => {
  assert.throws(
    () =>
      requireSavedInferenceScriptVersion({
        success: false,
        errorMessage: '脚本 ZIP 校验失败',
      }),
    /脚本 ZIP 校验失败/,
  );
});

test('rejects ambiguous success without a script version id', () => {
  assert.throws(
    () => requireSavedInferenceScriptVersion({ success: true, data: {} }),
    /未返回可复用的版本编号/,
  );
});
