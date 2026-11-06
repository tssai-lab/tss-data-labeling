import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { hashFile, uploadChunks } from './labelingUpload.mjs';

test('archive hash matches SHA-256 across multiple blocks', async () => {
  const bytes = Buffer.alloc(4 * 1024 * 1024 + 31, 0xa7);
  const actual = await hashFile(
    new Blob([bytes]),
    new AbortController().signal,
  );
  assert.equal(actual, createHash('sha256').update(bytes).digest('hex'));
});

test('resume uses server offset, hashes exact chunks, and sends short final chunk', async () => {
  const file = new Blob(['abcdefghij']);
  const calls = [];
  await uploadChunks(
    file,
    { batchId: 'batch', sizeBytes: 10, receivedBytes: 4, chunkSizeBytes: 4 },
    async (path, init) => {
      calls.push({ path, body: Buffer.from(init.body).toString() });
      assert.equal(
        init.headers['X-Chunk-SHA256'],
        createHash('sha256').update(init.body).digest('hex'),
      );
      return { receivedBytes: calls.length === 1 ? 8 : 10 };
    },
    new AbortController().signal,
  );
  assert.deepEqual(calls, [
    { path: '/imports/batch/chunks?offset=4', body: 'efgh' },
    { path: '/imports/batch/chunks?offset=8', body: 'ij' },
  ]);
});

test('invalid progress, failed upload, and cancellation cannot silently complete', async () => {
  const file = new Blob(['abcd']);
  const batch = {
    batchId: 'batch',
    sizeBytes: 4,
    receivedBytes: 0,
    chunkSizeBytes: 4,
  };
  await assert.rejects(
    uploadChunks(
      file,
      batch,
      async () => ({ receivedBytes: 0 }),
      new AbortController().signal,
    ),
    /进度无效/,
  );
  await assert.rejects(
    uploadChunks(
      file,
      batch,
      async () => {
        throw new Error('network failure');
      },
      new AbortController().signal,
    ),
    /network failure/,
  );
  const abort = new AbortController();
  abort.abort();
  await assert.rejects(
    uploadChunks(
      file,
      batch,
      async () => assert.fail('must not upload'),
      abort.signal,
    ),
    { name: 'AbortError' },
  );
});
