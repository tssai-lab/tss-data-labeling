import assert from 'node:assert/strict';
import test from 'node:test';
import {
  confirmedDraft,
  parsePendingSubmission,
  submissionManifest,
} from './labelingClassification.mjs';

const sample = (id, revision = 1) => ({
  sampleId: id,
  draftRevision: revision,
  explicitDone: true,
  resultFormat: 'image-classification-v1',
  result: { labelId: 'stable-label' },
});
const labels = [{ id: 'stable-label', name: '显示名称' }];

test('submission freezes every saved revision in stable sample order', () => {
  assert.deepEqual(
    submissionManifest([sample('b', 3), sample('a', 2)], labels),
    [
      { sampleId: 'a', revision: 2 },
      { sampleId: 'b', revision: 3 },
    ],
  );
});

test('incomplete, duplicate, stale format or unknown labels cannot become a submission', () => {
  for (const items of [
    [],
    [sample('a'), sample('a')],
    [sample('a', 0)],
    [{ ...sample('a'), explicitDone: false }],
    [{ ...sample('a'), resultFormat: 'label-studio-json' }],
    [{ ...sample('a'), result: { labelId: '显示名称' } }],
  ]) {
    assert.throws(() => submissionManifest(items, labels));
  }
});

test('a late or malformed draft response cannot authorize another sample', () => {
  const draft = {
    assignmentId: 'mine',
    sampleId: 'a',
    revision: 2,
    explicitDone: true,
    resultFormat: 'image-classification-v1',
    result: { labelId: 'stable-label' },
  };
  assert.equal(confirmedDraft(draft, 'mine', 'a'), draft);
  assert.throws(() => confirmedDraft(draft, 'mine', 'b'));
  assert.throws(() => confirmedDraft({ ...draft, revision: -1 }, 'mine', 'a'));
});

test('ambiguous submission survives refresh with original key and immutable revisions, isolated per actor', () => {
  const pending = {
    actorId: 'actor',
    assignmentId: 'work',
    key: 'same-key',
    items: [{ sampleId: 'a', revision: 3 }],
  };
  assert.deepEqual(
    parsePendingSubmission(JSON.stringify(pending), 'actor', 'work'),
    pending,
  );
  assert.throws(() =>
    parsePendingSubmission(JSON.stringify(pending), 'other', 'work'),
  );
  assert.throws(() =>
    parsePendingSubmission(
      JSON.stringify({
        ...pending,
        items: [...pending.items, ...pending.items],
      }),
      'actor',
      'work',
    ),
  );
});
