import assert from 'node:assert/strict';
import test from 'node:test';
import { webcrypto } from 'node:crypto';
import { loadTypeScriptModule } from '../../../../scripts/qa/load-typescript-module.mjs';

const { createTrainingSubmission } = loadTypeScriptModule(new URL('./trainingSubmission.ts', import.meta.url), {}, { crypto: webcrypto });

test('提交同步上锁，结束后允许重试且沿用办理编号', () => {
  const submission = createTrainingSubmission();
  assert.equal(submission.begin(), true);
  assert.equal(submission.begin(), false);
  const key = submission.keyFor('new', { epochs: 2 });
  submission.finish();
  assert.equal(submission.begin(), true);
  assert.equal(submission.keyFor('new', { epochs: 2 }), key);
});

test('修改参数、换实验或主动打开新表单各自生成新编号', () => {
  const submission = createTrainingSubmission();
  const keys = [submission.keyFor('new', { epochs: 2 }), submission.keyFor('new', { epochs: 3 }),
    submission.keyFor('experiment-a', { epochs: 3 }), createTrainingSubmission().keyFor('new', { epochs: 2 })];
  assert.equal(new Set(keys).size, 4);
  assert.ok(keys.every(key => /^[0-9a-f]{32}$/.test(key)));
});

test('仅调整 JSON 字段顺序仍属于同一次提交', () => {
  const submission = createTrainingSubmission();
  assert.equal(submission.keyFor('new', { parameters: { a: 1, b: 2 } }),
    submission.keyFor('new', { parameters: { b: 2, a: 1 } }));
});
