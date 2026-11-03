import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const modalSource = fs.readFileSync(path.join(here, 'ApiPolicyModal.tsx'), 'utf8');
const pageSource = fs.readFileSync(path.join(here, 'index.tsx'), 'utf8');
const serviceSource = fs.readFileSync(
  path.join(here, '../../../services/system/user.ts'),
  'utf8',
);

test('super administrator UI exposes grouped enable, concurrency and reset controls', () => {
  assert.match(pageSource, /isSuperAdmin.*record\.role !== SYSTEM_ROLES\.SUPER_ADMIN/s);
  assert.match(pageSource, /API 权限/);
  assert.match(modalSource, /允许调用/);
  assert.match(modalSource, /并发上限/);
  assert.match(modalSource, /恢复默认/);
  assert.match(modalSource, /留空表示不限流/);
});

test('service contract carries optimistic version and all six stable feature groups', () => {
  for (const group of [
    'MODEL_ASSET',
    'DATASET_ASSET',
    'TRAINING_DEFINITION',
    'TRAINING_TASK',
    'INFERENCE_TASK',
    'SYSTEM_ADMIN_AUDIT',
  ]) {
    assert.match(serviceSource, new RegExp(group));
  }
  assert.match(serviceSource, /version\?: number \| null/);
  assert.match(serviceSource, /api-policies/);
});

test('clearing a concurrency limit remains visibly empty instead of restoring the old value', () => {
  assert.match(
    modalSource,
    /value=\{\s*drafts\[policy\.featureGroup\]\?\.maxConcurrentRequests\s*\}/s,
  );
  assert.doesNotMatch(
    modalSource,
    /maxConcurrentRequests\s*\?\?\s*policy\.maxConcurrentRequests/s,
  );
});

test('a late response for the previously selected user cannot replace the current user policies', () => {
  assert.match(modalSource, /activeTargetId\.current !== userId/);
  assert.match(modalSource, /requestSequence !== loadSequence\.current/);
  assert.match(modalSource, /await load\(target\.id\)/);
});
