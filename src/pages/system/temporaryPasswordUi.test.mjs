import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const notice = fs.readFileSync(path.join(here, 'TemporaryPasswordNotice.tsx'), 'utf8');
const userPage = fs.readFileSync(path.join(here, 'user/index.tsx'), 'utf8');
const adminPage = fs.readFileSync(path.join(here, 'admin/index.tsx'), 'utf8');
const labels = fs.readFileSync(
  path.join(here, '../../constants/systemLabels.ts'),
  'utf8',
);

test('account creation displays only the temporary password returned by the server', () => {
  assert.match(userPage, /response\.data\?\.temporaryPassword/);
  assert.match(adminPage, /response\.data\?\.temporaryPassword/);
  assert.match(notice, /只显示这一次/);
  assert.match(notice, /copyable/);
});

test('frontend no longer contains a shared default account password', () => {
  assert.doesNotMatch(labels, /SYSTEM_DEFAULT_PASSWORD/);
  assert.doesNotMatch(userPage, /123456/);
  assert.doesNotMatch(adminPage, /123456/);
});
