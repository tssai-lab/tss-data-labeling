import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  ArchiveImportSession,
  imageImportAdapter,
  validateArchive,
} from './archiveImport.mjs';
const zip = () =>
  new File(
    [new Uint8Array([80, 75, 3, 4, 1, 2, 3, 4, 5, 6, 7, 8])],
    'data.zip',
  );
const storage = () => {
  const data = new Map();
  return {
    getItem: (k) => data.get(k) || null,
    setItem: (k, v) => data.set(k, v),
    removeItem: (k) => data.delete(k),
  };
};
function server() {
  let batch;
  const calls = [];
  const request = async (path, init = {}) => {
    calls.push({ path, init });
    init.signal?.throwIfAborted();
    if (path.startsWith('/projects/')) {
      const file = JSON.parse(init.body);
      batch ||= {
        ...file,
        batchId: 'b',
        status: 'UPLOADING',
        receivedBytes: 0,
        chunkSizeBytes: 4,
        validSampleCount: 0,
        errorCount: 0,
        errors: [],
        originalComplete: false,
      };
      return { ...batch };
    }
    if (init.method === 'PUT') {
      assert.equal(
        Number(new URL(`http://local${path}`).searchParams.get('offset')),
        batch.receivedBytes,
      );
      batch.receivedBytes += init.body.length;
      return { receivedBytes: batch.receivedBytes };
    }
    if (path.endsWith('/complete')) {
      batch.status = 'PROCESSING';
      batch.originalComplete = true;
      return { ...batch };
    }
    if (path.includes('/retry')) {
      batch.status = 'PROCESSING';
      return { ...batch };
    }
    return { ...batch };
  };
  return {
    calls,
    adapter: imageImportAdapter('p', request),
    get batch() {
      return batch;
    },
  };
}
test('ZIP preflight rejects wrong content/size and only reads the header of a large file', async () => {
  await assert.rejects(
    validateArchive(new File(['not zip'], 'a.zip')),
    /文件头/,
  );
  await assert.rejects(validateArchive(new File([''], 'a.zip')), /非空/);
  const reads = [];
  const file = {
    name: 'big.zip',
    size: 2 * 1024 ** 3,
    slice: (a, b) => {
      reads.push([a, b]);
      return new Blob([new Uint8Array([80, 75, 3, 4])]);
    },
  };
  await validateArchive(file);
  assert.deepEqual(reads, [[0, 4]]);
  await assert.rejects(
    validateArchive({ ...file, size: file.size + 1 }),
    /大小/,
  );
});
test('pause and refresh resume from authoritative server offset and preserve original batch', async () => {
  const api = server(),
    memory = storage(),
    file = zip();
  let session;
  session = new ArchiveImportSession({
    scope: 'p',
    adapter: api.adapter,
    storage: memory,
    onChange: (s) => {
      if (s.stage === 'UPLOADING' && s.uploadProgress === 33) session.pause();
    },
  });
  await session.run(file);
  assert.equal(session.state.stage, 'PAUSED');
  assert.equal(api.batch.receivedBytes, 4);
  session.dispose();
  const next = new ArchiveImportSession({
    scope: 'p',
    adapter: api.adapter,
    storage: memory,
  });
  await next.refresh();
  assert.equal(next.state.batch.receivedBytes, 4);
  await next.run(file);
  assert.equal(next.state.stage, 'PROCESSING');
  assert.equal(api.batch.receivedBytes, file.size);
  assert.equal(
    api.calls.filter((c) => c.path.startsWith('/projects/')).length,
    1,
  );
  const puts = api.calls.filter((c) => c.init.method === 'PUT');
  assert.deepEqual(
    puts.map((c) =>
      Number(new URL(`http://local${c.path}`).searchParams.get('offset')),
    ),
    [0, 4, 8],
  );
  api.batch.status = 'READY';
  api.batch.validSampleCount = 2;
  await next.refresh();
  assert.equal(next.state.stage, 'READY');
});
test('different archive cannot resume; parsing failures and retry use real returned states', async () => {
  const api = server(),
    session = new ArchiveImportSession({
      scope: 'p',
      adapter: api.adapter,
      storage: storage(),
    });
  await session.run(zip());
  const calls = api.calls.length;
  await session.run(new File([new Uint8Array([80, 75, 3, 4, 9])], 'data.zip'));
  assert.match(session.state.error, /不一致/);
  assert.equal(api.calls.length, calls);
  api.batch.status = 'FAILED';
  api.batch.errorCode = 'INVALID_IMAGE';
  api.batch.errors = [
    { sourcePath: 'bad.jpg', message: '图片损坏', code: 'INVALID_IMAGE' },
  ];
  api.batch.errorCount = 1;
  await session.refresh();
  assert.equal(session.state.stage, 'FAILED');
  assert.equal(session.state.batch.errors.length, 1);
  await session.retry();
  assert.equal(session.state.stage, 'PROCESSING');
});
test('no adapter never uploads or produces success, and quota failures stop before creating a batch', async () => {
  const unconnected = new ArchiveImportSession({
    scope: 'local',
    storage: storage(),
  });
  await unconnected.run(zip());
  assert.match(unconnected.state.error, /没有上传/);
  assert.equal(unconnected.state.batch, null);
  const api = server(),
    session = new ArchiveImportSession({
      scope: 'p',
      adapter: api.adapter,
      storage: {
        getItem: () => null,
        setItem: () => {
          throw Error('quota');
        },
      },
    });
  await session.run(zip());
  assert.match(session.state.error, /存储/);
  assert.equal(api.calls.length, 0);
});
