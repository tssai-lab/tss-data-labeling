import assert from 'node:assert/strict';
import test from 'node:test';

import {
  confidencePercent,
  listTextClassificationRows,
} from './textClassificationPresentation.mjs';

test('keeps text prediction rows and ignores malformed preview entries', () => {
  const result = {
    predictionsPreview: [
      {
        text: '训练日志保存完整。',
        label: '正面',
        prediction: '正面',
        confidence: 0.92,
      },
      {
        inputPreview: {
          kind: 'text',
          summary: '这是一条保存在任务原始输入箱中的长文本…',
          path: 'previews/text/1.txt',
          truncated: true,
        },
        label: '正面',
        prediction: '正面',
      },
      { label: '负面', prediction: '负面' },
      { inputPreview: { kind: 'image', path: 'previews/2.png' } },
      null,
    ],
  };

  assert.equal(listTextClassificationRows(result).length, 2);
  assert.equal(
    listTextClassificationRows(result)[0].text,
    '训练日志保存完整。',
  );
});

test('normalizes confidence for a progress bar and rejects non-numbers', () => {
  assert.equal(confidencePercent(0.7534), 75.3);
  assert.equal(confidencePercent(2), 100);
  assert.equal(confidencePercent(-1), 0);
  assert.equal(confidencePercent(Number.NaN), null);
});
