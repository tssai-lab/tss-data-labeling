import assert from 'node:assert/strict';
import test from 'node:test';
import { createGapMock } from './labelingGapMock.mjs';
const root = '/api/labeling/v1';
const api = () => createGapMock({ enabled: true, production: false });
const write = (
  mock,
  path,
  body,
  actor = 'demo-owner',
  key = crypto.randomUUID(),
) => mock.request('POST', root + path, { body, actor, key }).data;
const create = (mock, body = {}) =>
  write(mock, '/projects/demo-project/exports', {
    format: 'native-json',
    scope: 'ALL_SUBMITTED',
    includeOriginals: false,
    ...body,
  });
test('disabled / production adapters reject before writes; never accept real project IDs or live APIs', () => {
  for (const mock of [
    createGapMock(),
    createGapMock({ enabled: true, production: true }),
  ]) {
    assert.throws(
      () => mock.reset(),
      (e) => e.code === 'MOCK_DISABLED',
    );
    assert.throws(
      () => create(mock),
      (e) => e.code === 'MOCK_DISABLED',
    );
  }
  const mock = api();
  assert.throws(
    () => write(mock, '/projects/real-project/exports', {}),
    (e) => e.status === 404,
  );
  assert.throws(
    () => mock.request('GET', root + '/me'),
    (e) => e.status === 404,
  );
  assert.throws(
    () => mock.request('PATCH', root + '/admin/users/demo-alice'),
    (e) => e.status === 404,
  );
});
test('export queue, running, success, frozen two-person mapping and valid no-target result', () => {
  const mock = api(),
    job = create(mock);
  assert.equal(job.status, 'QUEUED');
  assert.throws(
    () => mock.request('GET', `${root}/exports/${job.exportId}/download`),
    (e) => e.status === 409,
  );
  assert.equal(
    mock.request('GET', `${root}/exports/${job.exportId}`).data.status,
    'RUNNING',
  );
  assert.equal(
    mock.request('GET', `${root}/exports/${job.exportId}`).data.status,
    'SUCCEEDED',
  );
  const files = mock.request(
    'GET',
    `${root}/exports/${job.exportId}/download`,
  ).data;
  assert.deepEqual(Object.keys(files), [
    'results.json',
    'mapping.csv',
    'manifest.json',
    'labels.json',
  ]);
  const results = JSON.parse(files['results.json']);
  assert.equal(results.submissions.length, 2);
  assert.equal(results.records.length, 20);
  assert.equal(new Set(results.records.map((r) => r.actorId)).size, 2);
  assert.equal(results.submissions[1].items[0].result.labelId, 'none');
  assert.equal(files['mapping.csv'].split('\r\n').length, 21);
  assert.equal(
    JSON.parse(files['manifest.json']).incompleteSampleIds.length,
    2,
  );
  assert.equal(
    JSON.parse(files['labels.json']).specifications[0].labels[1].id,
    'none',
  );
});
test('selected export, no submissions, permission denied, format, failure and expiration', () => {
  const mock = api();
  assert.throws(
    () => create(mock, { scope: 'SELECTED_SUBMISSIONS', submissionIds: [] }),
    (e) => e.code === 'NO_SUBMISSIONS',
  );
  assert.throws(
    () => create(mock, { format: 'unknown' }),
    (e) => e.code === 'UNSUPPORTED_FORMAT',
  );
  assert.throws(
    () => write(mock, '/projects/demo-project/exports', {}, 'demo-admin'),
    (e) => e.status === 404,
  );
  const selected = create(mock, {
    scope: 'SELECTED_SUBMISSIONS',
    submissionIds: ['demo-submission-b'],
  });
  assert.deepEqual(selected.submissionIds, ['demo-submission-b']);
  mock.setExportFault(selected.exportId, { failed: true });
  mock.request('GET', `${root}/exports/${selected.exportId}`);
  assert.equal(
    mock.request('GET', `${root}/exports/${selected.exportId}`).data.status,
    'FAILED',
  );
  const expired = create(mock);
  mock.request('GET', `${root}/exports/${expired.exportId}`);
  mock.request('GET', `${root}/exports/${expired.exportId}`);
  mock.setExportFault(expired.exportId, { expired: true });
  assert.throws(
    () => mock.request('GET', `${root}/exports/${expired.exportId}/download`),
    (e) => e.status === 410 && e.code === 'EXPORT_EXPIRED',
  );
});
test('revision CAS, idempotency replay, pause, release/revoke and archive preserve submitted history', () => {
  const mock = api();
  mock.reset();
  assert.equal(
    mock.assertWorkAccess('demo-alice', 'demo-assignment-open'),
    true,
  );
  const body = { baseRevision: 1, reason: '验收暂停' };
  const paused = write(
    mock,
    '/projects/demo-project/pause',
    body,
    'demo-owner',
    'same',
  );
  assert.deepEqual(
    write(mock, '/projects/demo-project/pause', body, 'demo-owner', 'same'),
    paused,
  );
  assert.throws(
    () =>
      write(
        mock,
        '/projects/demo-project/pause',
        { ...body, reason: '另一次' },
        'demo-owner',
        'same',
      ),
    (e) => e.code === 'IDEMPOTENCY_CONFLICT',
  );
  assert.throws(
    () => mock.assertWorkAccess('demo-alice', 'demo-assignment-open'),
    (e) => e.code === 'PROJECT_PAUSED',
  );
  assert.throws(
    () => write(mock, '/projects/demo-project/resume', { baseRevision: 1 }),
    (e) => e.code === 'REVISION_CONFLICT',
  );
  write(mock, '/projects/demo-project/resume', { baseRevision: 2 });
  assert.throws(
    () =>
      write(mock, '/projects/demo-project/archive', {
        baseRevision: 3,
        reason: '归档',
      }),
    (e) => e.code === 'RESOURCE_BUSY',
  );
  assert.throws(
    () =>
      write(
        mock,
        '/assignments/demo-assignment-a/release',
        { baseRevision: 3, reason: '无效' },
        'demo-alice',
      ),
    (e) => e.code === 'ASSIGNMENT_CLOSED',
  );
  write(
    mock,
    '/admin/assignments/demo-assignment-open/revoke',
    { baseRevision: 2, reason: '异常回收' },
    'demo-admin',
  );
  assert.throws(
    () => mock.assertWorkAccess('demo-alice', 'demo-assignment-open'),
    (e) => e.code === 'ASSIGNMENT_CLOSED',
  );
  write(mock, '/projects/demo-project/archive', {
    baseRevision: 3,
    reason: '归档',
  });
  assert.equal(mock.snapshot().submissions.length, 2);
  assert.throws(
    () => mock.request('GET', root + '/admin/audit-events'),
    (e) => e.status === 403,
  );
  assert.equal(
    mock.request('GET', root + '/admin/audit-events', { actor: 'demo-admin' })
      .data.items.length,
    4,
  );
  mock.reset();
  write(
    mock,
    '/assignments/demo-assignment-open/release',
    { baseRevision: 2, reason: '本人放弃' },
    'demo-alice',
  );
  assert.throws(
    () => mock.assertWorkAccess('demo-alice', 'demo-assignment-open'),
    (e) => e.code === 'ASSIGNMENT_CLOSED',
  );
});
