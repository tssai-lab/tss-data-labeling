import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

// Exercise the actual TypeScript client without booting Umi or accessing a real account.
const source = ts.transpileModule(
  readFileSync(new URL('./labeling.ts', import.meta.url), 'utf8'),
  {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  },
).outputText;

function client(fetch) {
  const state = { redirected: false, removed: false };
  const exports = {};
  vm.runInNewContext(source, {
    exports,
    Headers,
    AbortSignal,
    URLSearchParams,
    crypto,
    fetch,
    require: (name) => {
      if (name.endsWith('/storage'))
        return {
          STORAGE_KEYS: { TOKEN: 'token' },
          storage: {
            get: () => 'test-token',
            remove: () => {
              state.removed = true;
            },
          },
        };
      if (name.endsWith('/loginRedirect'))
        return {
          redirectToLogin: () => {
            state.redirected = true;
          },
        };
      throw new Error(`Unexpected import ${name}`);
    },
  });
  return { api: exports, state };
}

test('complete sends no body, reuses supplied idempotency key, and unwraps data', async () => {
  const { api } = client(async (path, init) => {
    assert.equal(path, '/api/labeling/v1/imports/batch/complete');
    assert.equal(init.headers.get('Authorization'), 'Bearer test-token');
    assert.equal(init.headers.get('Idempotency-Key'), 'stable-key');
    assert.equal(init.headers.has('Content-Type'), false);
    assert.equal(init.body, undefined);
    return Response.json(
      { data: { status: 'PROCESSING' }, requestId: 'request-1' },
      { status: 202 },
    );
  });
  assert.equal(
    (await api.post('/imports/batch/complete', undefined, 'stable-key')).status,
    'PROCESSING',
  );
});

test('structured upload conflicts retain status, code and request ID', async () => {
  const { api } = client(async () =>
    Response.json(
      {
        error: { code: 'OFFSET_MISMATCH', message: 'wrong offset' },
        requestId: 'request-2',
      },
      { status: 409 },
    ),
  );
  await assert.rejects(
    api.labelingJson('/imports/batch'),
    (error) =>
      error.status === 409 &&
      error.code === 'OFFSET_MISMATCH' &&
      error.requestId === 'request-2',
  );
});

test('expired session clears token and redirects instead of pretending to load projects', async () => {
  const { api, state } = client(async () =>
    Response.json(
      { error: { code: 'UNAUTHENTICATED', message: '请先登录' } },
      { status: 401 },
    ),
  );
  await assert.rejects(
    api.labelingJson('/projects'),
    (error) => error.status === 401,
  );
  assert.equal(state.removed, true);
  assert.equal(state.redirected, true);
});

test('403 retains token, code and requestId; only the explicit admin mutation permits data:null', async () => {
  const denied = client(async () => Response.json({ error: { code: 'FORBIDDEN', message: '无权限' }, requestId: 'permission-check' }, { status: 403 }));
  await assert.rejects(denied.api.labelingJson('/admin/users'), e => e.status === 403 && e.requestId === 'permission-check');
  assert.equal(denied.state.removed, false); assert.equal(denied.state.redirected, false);
  const empty = client(async () => Response.json({ data: null }));
  assert.equal(await empty.api.labelingJson('/admin/users/actor', { method: 'PATCH' }, true), null);
  await assert.rejects(empty.api.labelingJson('/projects'));
  const list = client(async () => Response.json({ data: [] }));
  assert.equal((await list.api.labelingJson('/admin/users')).length, 0);
  for (const data of ['text', 1, false]) {
    await assert.rejects(client(async () => Response.json({ data })).api.labelingJson('/projects'));
  }
});

test('preview authenticates without putting token into URL and rejects gateway HTML', async () => {
  const { api } = client(async (path, init) => {
    assert.equal(path, '/api/labeling/v1/projects/project/samples/sample/file');
    assert.equal(init.headers.get('Authorization'), 'Bearer test-token');
    return new Response('<html>gateway fallback</html>', {
      headers: { 'Content-Type': 'text/html' },
    });
  });
  await assert.rejects(
    api.sampleBlob('project', 'sample', new AbortController().signal),
    /未返回 JPEG\/PNG/,
  );
});

test('future export download retains bearer authorization and rejects gateway HTML', async () => {
  const { api } = client(async (path, init) => {
    assert.equal(path, '/api/labeling/v1/exports/fixed/download');
    assert.equal(init.headers.get('Authorization'), 'Bearer test-token');
    return new Response('zip-bytes', { headers: { 'Content-Type': 'application/zip' } });
  });
  assert.equal((await api.labelingZip('/exports/fixed/download')).type, 'application/zip');
  const gateway = client(async () => new Response('<html/>', { headers: { 'Content-Type': 'text/html' } }));
  await assert.rejects(gateway.api.labelingZip('/exports/fixed/download'), /未返回 ZIP/);
});
