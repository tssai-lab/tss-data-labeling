import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../../main/resources/editor-ui/bridge.js', import.meta.url), 'utf8');
const result = category => [{ from_name: 'category', to_name: 'image', type: 'choices', value: { choices: [category] } }];
async function fixture(saveResponse = async () => ({ ok: true, json: async () => ({ data: { revision: 1, result: { labelId: 'normal' } } }) }), contextOverrides = {}, failure = {}) {
  const messages = [], handlers = {};
  const parent = { postMessage: value => messages.push(value) };
  let answer = [], callbacks, savedVersions;
  const annotation = { id: 'a', serializeAnnotation: () => structuredClone(answer), deserializeAnnotation: value => { answer = value; }, addVersions: value => { savedVersions = value; } };
  const store = { annotationStore: { selected: annotation, addAnnotation: () => annotation, selectAnnotation: () => {} } };
  const context = { editorSessionId: 'session', assignmentId: 'assignment', sampleId: 'sample', actorId: 'actor', draft: { revision: 0, result: {} }, ...contextOverrides };
  const window = { parent, location: { origin: 'http://localhost', href: 'http://localhost/api/labeling/v1/editor-ui/session/index.html' },
    addEventListener: (name, handler) => { handlers[name] = handler; },
    LabelStudio: function (_id, options) { callbacks = options; options.onLabelStudioLoad(store); },
  };
  const sandbox = { window, URL, document: { createElement: () => ({}), head: { appendChild: script => failure.script ? script.onerror() : script.onload() } },
    fetch: async route => route === 'context' ? failure.context || { ok: true, json: async () => ({ data: context }) } : saveResponse(),
  };
  await vm.runInNewContext(source, sandbox);
  return { messages, context, handlers, parent,
    callbacks: () => callbacks,
    versions: () => savedVersions,
    answer: () => structuredClone(answer),
    setAnswer: category => { answer = result(category); },
    command: (type, overrides = {}) => handlers.message({ origin: 'http://localhost', source: parent,
      data: { type, editorSessionId: 'session', requestId: 'request', ...overrides } }),
  };
}
const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)); };

test('restores adapted LS choices instead of passing platform labelId object to the editor', async () => {
  const f = await fixture(undefined, { draft: { revision: 3, result: { labelId: 'broken' } }, editorResult: result('破损') });
  assert.equal(f.answer()[0].value.choices[0], '破损');
  assert.equal(f.messages[0].assignmentId, 'assignment');
  assert.equal(f.messages[0].revision, 3);
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).dirty, false);
});

test('iframe reports binding and detects unsaved changes, rejects untrusted commands', async () => {
  let writes = 0;
  const f = await fixture(async () => { writes++; throw new Error('unexpected'); });
  assert.equal(f.messages[0].type, 'labeling:ready');
  assert.equal(f.messages[0].sampleId, 'sample');
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).dirty, false);
  f.setAnswer('正常');
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).dirty, true);
  for (const change of [{ origin: 'http://evil' }, { source: {} }, { data: { type: 'labeling:save', editorSessionId: 'other', requestId: 'x' } }]) {
    f.handlers.message({ origin: 'http://localhost', source: f.parent,
      data: { type: 'labeling:save', editorSessionId: 'session', requestId: 'x' }, ...change });
  }
  await settle();
  assert.equal(writes, 0);
});

test('successful save acknowledges server revision; changes made during save stay dirty', async () => {
  let finish;
  const f = await fixture(() => new Promise(resolve => { finish = resolve; }));
  f.setAnswer('正常');
  f.command('labeling:save');
  assert.equal(f.messages.some(m => m.type === 'labeling:saved'), false);
  f.setAnswer('破损');
  finish({ ok: true, json: async () => ({ data: { revision: 7, result: result('正常') } }) });
  await settle();
  const saved = f.messages.find(m => m.type === 'labeling:saved');
  assert.equal(saved.revision, 7);
  assert.equal(saved.requestId, 'request');
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).dirty, true);
});

test('failed save reports failure and preserves unsaved answer', async () => {
  const f = await fixture(async () => ({ ok: false, json: async () => ({ error: { message: '保存失败' } }) }));
  f.setAnswer('正常'); f.command('labeling:save'); await settle();
  assert.equal(f.messages.some(m => m.type === 'labeling:saved'), false);
  assert.equal(f.messages.at(-1).type, 'labeling:save-failed');
  assert.equal(f.messages.at(-1).requestId, 'request');
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).dirty, true);
  assert.equal(f.messages.at(-1).saving, false);
});

test('context load failure reaches host and remains discoverable by a later handshake', async () => {
  const f = await fixture(undefined, {}, { context: { ok: false, status: 401, json: async () => ({ error: { code: 'UNAUTHENTICATED', message: 'expired' } }) } });
  assert.equal(f.messages[0].type, 'labeling:load-failed');
  assert.equal(f.messages[0].editorSessionId, 'session');
  assert.equal(f.messages[0].code, 'UNAUTHENTICATED');
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).ready, false);
  assert.equal(f.messages.at(-1).error.status, 401);
  f.command('labeling:save');
  assert.equal(f.messages.at(-1).code, 'EDITOR_NOT_READY');
});

test('asset load failure reports bound context without business UI elements', async () => {
  const f = await fixture(undefined, {}, { script: true });
  assert.equal(f.messages[0].type, 'labeling:load-failed');
  assert.equal(f.messages[0].assignmentId, 'assignment');
  assert.equal(f.messages[0].code, 'EDITOR_LOAD_FAILED');
});

test('host can recover ready state and distinguish busy saves', async () => {
  let finish;
  const f = await fixture(() => new Promise(resolve => { finish = resolve; }));
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).ready, true);
  f.setAnswer('正常'); f.command('labeling:save');
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).saving, true);
  f.command('labeling:save', { requestId: 'second-request' });
  assert.equal(f.messages.at(-1).code, 'EDITOR_BUSY');
  finish({ ok: true, json: async () => ({ data: { revision: 1, result: { labelId: 'normal' } } }) });
  await settle();
  assert.equal(f.messages.at(-1).type, 'labeling:saved');
  assert.equal(f.messages.at(-1).dirty, false);
});

test('native Submit and Update share verified saving and publish host notifications', async () => {
  let revision = 0;
  const f = await fixture(async () => ({ ok: true, json: async () => ({ data: { revision: ++revision, result: { labelId: revision === 1 ? 'normal' : 'broken' } } }) }));
  assert.ok(f.callbacks().interfaces.includes('submit'));
  assert.ok(f.callbacks().interfaces.includes('update'));
  f.setAnswer('正常'); await f.callbacks().onSubmitAnnotation();
  assert.equal(f.messages.at(-1).type, 'labeling:saved');
  assert.equal(f.messages.at(-1).revision, 1);
  assert.equal(f.versions().result[0].value.choices[0], '正常');
  f.setAnswer('破损'); await f.callbacks().onUpdateAnnotation();
  assert.equal(f.messages.at(-1).revision, 2);
  assert.equal(f.messages.at(-1).dirty, false);
});

test('native save failure rejects the SDK promise and keeps unsaved changes', async () => {
  const f = await fixture(async () => ({ ok: false, status: 503, json: async () => ({ error: { code: 'EDITOR_UNAVAILABLE', message: 'unavailable' } }) }));
  f.setAnswer('正常');
  await assert.rejects(f.callbacks().onSubmitAnnotation(), /unavailable/);
  assert.equal(f.messages.at(-1).type, 'labeling:save-failed');
  assert.equal(f.versions(), undefined);
  f.command('labeling:request-state');
  assert.equal(f.messages.at(-1).dirty, true);
  assert.equal(f.messages.at(-1).saving, false);
});
