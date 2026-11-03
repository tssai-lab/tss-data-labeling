import assert from 'node:assert/strict';
import test from 'node:test';
import { collectPaginatedCandidates } from './paginatedCandidates.mjs';

test('collects every server-filtered page instead of truncating at 100 items', async () => {
  const all = Array.from({ length: 425 }, (_, index) => ({ id: `item-${index}` }));
  const pages = [];
  const result = await collectPaginatedCandidates(
    async (page, pageSize) => {
      pages.push(page);
      const start = (page - 1) * pageSize;
      return { data: all.slice(start, start + pageSize), total: all.length };
    },
    { keyOf: (item) => item.id, pageSize: 200 },
  );

  assert.deepEqual(pages, [1, 2, 3]);
  assert.equal(result.data.length, 425);
  assert.equal(result.total, 425);
});

test('deduplicates page-boundary repeats and stops on an empty page', async () => {
  const responses = [
    { data: [{ id: 'a' }, { id: 'b' }], total: 6 },
    { data: [{ id: 'b' }, { id: 'c' }], total: 6 },
    { data: [], total: 6 },
  ];
  const result = await collectPaginatedCandidates(
    async (page) => responses[page - 1],
    { keyOf: (item) => item.id, pageSize: 2 },
  );

  assert.deepEqual(
    result.data.map((item) => item.id),
    ['a', 'b', 'c'],
  );
});
