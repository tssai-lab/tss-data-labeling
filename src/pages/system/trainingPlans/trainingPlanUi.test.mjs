import assert from 'node:assert/strict';
import test from 'node:test';
import {
  MAX_TRAINING_PLAN_YAML_BYTES,
  canPublishTrainingPlan,
  getTrainingPlanRequestError,
  validateTrainingPlanYamlFile,
} from './trainingPlanUi.mjs';

test('rejects missing, wrong-extension, empty and oversized files', () => {
  assert.equal(validateTrainingPlanYamlFile(), '请选择 YAML 文件');
  assert.equal(
    validateTrainingPlanYamlFile({ name: 'plan.json', size: 10 }),
    '只支持 .yaml 或 .yml 文件',
  );
  assert.equal(
    validateTrainingPlanYamlFile({ name: 'plan.yaml', size: 0 }),
    'YAML 文件不能为空',
  );
  assert.equal(
    validateTrainingPlanYamlFile({
      name: 'plan.yml',
      size: MAX_TRAINING_PLAN_YAML_BYTES + 1,
    }),
    'YAML 文件不能超过 256 KiB',
  );
});

test('accepts both YAML extensions at the exact size boundary', () => {
  assert.equal(
    validateTrainingPlanYamlFile({
      name: 'PLAN.YAML',
      size: MAX_TRAINING_PLAN_YAML_BYTES,
    }),
    undefined,
  );
  assert.equal(
    validateTrainingPlanYamlFile({ name: 'plan.yml', size: 1 }),
    undefined,
  );
});

test('publish remains locked without the same valid preview result', () => {
  const file = { name: 'plan.yaml', size: 1 };
  const sha256 = 'a'.repeat(64);
  const definition = { id: 'cv_cpu', version: 'v2' };
  assert.equal(canPublishTrainingPlan(undefined, undefined), false);
  assert.equal(canPublishTrainingPlan(file, { publishable: true, sha256: '' }), false);
  assert.equal(
    canPublishTrainingPlan(file, { publishable: true, sha256 }),
    false,
  );
  assert.equal(
    canPublishTrainingPlan(file, { publishable: false, sha256, definition }),
    false,
  );
  assert.equal(
    canPublishTrainingPlan(file, { publishable: true, sha256, definition }),
    true,
  );
});

test('surfaces stable backend errors and permission failures', () => {
  assert.equal(
    getTrainingPlanRequestError({ response: { status: 403 } }, '失败'),
    '仅超级管理员可以执行此操作',
  );
  assert.equal(
    getTrainingPlanRequestError({
      response: {
        status: 409,
        data: { errorMessage: '同一版本内容不可覆盖' },
      },
    }),
    '同一版本内容不可覆盖',
  );
});
