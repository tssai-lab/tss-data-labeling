import assert from 'node:assert/strict';
import test from 'node:test';

import {
  INLINE_TEXT_LIMIT,
  resolveRowInputPreview,
  safeRelativePreviewPath,
} from './inputPreviewPresentation.mjs';

test('accepts only task-output relative preview paths', () => {
  assert.equal(
    safeRelativePreviewPath('previews/images/0.jpg'),
    'previews/images/0.jpg',
  );
  for (const unsafe of [
    '../0.jpg',
    'previews/../0.jpg',
    '/previews/0.jpg',
    'workspace/input.jpg',
    'users/2/private.jpg',
    'minio://users/2/private.jpg',
    'https://example.com/0.jpg',
    'previews\\0.jpg',
  ]) {
    assert.equal(safeRelativePreviewPath(unsafe), '');
  }
});

test('normalizes explicit and legacy image previews', () => {
  assert.deepEqual(
    resolveRowInputPreview({
      inputPreview: {
        type: 'image',
        path: 'previews/images/0.jpg',
        name: 'bean.jpg',
      },
    }),
    {
      kind: 'image',
      path: 'previews/images/0.jpg',
      name: 'bean.jpg',
    },
  );
  assert.equal(
    resolveRowInputPreview({ media: { path: 'preview/legacy.png' } }).kind,
    'image',
  );
});

test('keeps short text inline and marks long text for a separate viewer', () => {
  const short = resolveRowInputPreview({ text: '短文本' });
  assert.equal(short.kind, 'text');
  assert.equal(short.summary, '短文本');
  assert.equal(short.truncated, false);

  const longText = '长'.repeat(INLINE_TEXT_LIMIT + 20);
  const long = resolveRowInputPreview({ text: longText });
  assert.equal(long.kind, 'text');
  assert.equal(long.truncated, true);
  assert.equal(long.summary.length, INLINE_TEXT_LIMIT + 1);
  assert.equal(long.text, longText);
});

test('supports text stored as a task output and rejects unsafe explicit paths', () => {
  const stored = resolveRowInputPreview({
    inputPreview: {
      kind: 'text',
      summary: '预览摘要',
      path: 'previews/text/0.txt',
      truncated: true,
      contentTruncated: true,
    },
  });
  assert.equal(stored.kind, 'text');
  assert.equal(stored.path, 'previews/text/0.txt');
  assert.equal(stored.truncated, true);
  assert.equal(stored.contentTruncated, true);

  assert.equal(
    resolveRowInputPreview({
      inputPreview: { kind: 'file', path: '../../secret.txt' },
    }),
    null,
  );
});
