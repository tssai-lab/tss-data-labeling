import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
const api = {};
vm.runInNewContext(
  ts.transpileModule(
    readFileSync(new URL('./labelingEditor.ts', import.meta.url), 'utf8'),
    {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    },
  ).outputText,
  { exports: api, URL, Date, require: () => ({}) },
);
const context = {
  assignmentId: 'assignment',
  actorId: 'actor',
  sampleId: 'sample',
  specVersionId: 'frozen',
};
const session = {
  ...context,
  projectId: 'project',
  editorSessionId: 'session',
  editorUrl: '/api/labeling/v1/editor-ui/session/launch?ticket=synthetic-once',
  mode: 'ASSIGNMENT_IMAGE_CLASSIFICATION',
  launchExpiresAt: new Date(Date.now() + 60000).toISOString(),
  expiresAt: new Date(Date.now() + 1800000).toISOString(),
};
test('launch validates origin, exact session path, expiry and every known business context ID', () => {
  assert.equal(
    new URL(api.editorLaunch(session, context, 'http://localhost')).origin,
    'http://localhost',
  );
  for (const invalid of [
    { ...session, actorId: 'other' },
    { ...session, specVersionId: 'latest' },
    { ...session, assignmentId: 'old' },
    { ...session, projectId: '' },
    { ...session, mode: 'unknown' },
    {
      ...session,
      editorUrl:
        'https://evil.example/api/labeling/v1/editor-ui/session/launch?ticket=x',
    },
    { ...session, editorUrl: '/api/labeling/v1/editor-ui/old/launch?ticket=x' },
    {
      ...session,
      editorUrl:
        '/api/labeling/v1/editor-ui/session/launch?ticket=x&token=secret',
    },
    { ...session, editorUrl: '/api/labeling/v1/editor-ui/session/launch' },
    { ...session, launchExpiresAt: 'invalid' },
    { ...session, expiresAt: 'invalid' },
    { ...session, launchExpiresAt: '2000-01-01' },
  ])
    assert.throws(() => api.editorLaunch(invalid, context, 'http://localhost'));
});
test('old or mismatching messages cannot change state; incomplete load errors only update loading status', () => {
  const message = {
    ...context,
    projectId: 'project',
    editorSessionId: 'session',
    type: 'labeling:saved',
    revision: 1,
  };
  assert.equal(api.validEditorMessage(message, session), true);
  for (const key of [
    'actorId',
    'assignmentId',
    'projectId',
    'sampleId',
    'specVersionId',
    'editorSessionId',
  ]) {
    assert.equal(
      api.validEditorMessage({ ...message, [key]: 'old-or-other' }, session),
      false,
    );
    assert.equal(
      api.validEditorMessage({ ...message, [key]: undefined }, session),
      false,
    );
  }
  assert.equal(
    api.validEditorMessage(
      { editorSessionId: 'session', type: 'labeling:load-failed' },
      session,
    ),
    true,
  );
  assert.equal(
    api.validEditorMessage(
      { editorSessionId: 'session', type: 'labeling:state', ready: false },
      session,
    ),
    true,
  );
  assert.equal(
    api.validEditorMessage(
      { editorSessionId: 'session', type: 'labeling:state', ready: true },
      session,
    ),
    false,
  );
  assert.equal(
    api.validEditorMessage({ ...message, type: 'unknown' }, session),
    false,
  );
});
