import { test } from 'node:test';
import assert from 'node:assert/strict';
import { acquireEditorLease } from './editorLifecycle.mjs';

test('preview teardown excludes a new editor and preserves waiting order', async () => {
  const releasePreview = await acquireEditorLease();
  const order = [];
  const editor = acquireEditorLease().then(release => { order.push('editor'); return release; });
  const nextPreview = acquireEditorLease().then(release => { order.push('next-preview'); return release; });
  await Promise.resolve();
  assert.deepEqual(order, []);
  releasePreview();
  const releaseEditor = await editor;
  assert.deepEqual(order, ['editor']);
  releasePreview(); // Repeated cleanup cannot release another instance's lease.
  await Promise.resolve();
  assert.deepEqual(order, ['editor']);
  releaseEditor();
  const releaseNext = await nextPreview;
  assert.deepEqual(order, ['editor', 'next-preview']);
  releaseNext();
});
