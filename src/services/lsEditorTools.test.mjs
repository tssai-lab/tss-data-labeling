import assert from 'node:assert/strict';
import test from 'node:test';
import {
  draftButtons,
  readLocalLsDraft,
  saveLocalLsDraft,
  timeseriesInterfaces,
} from './lsEditorTools.mjs';

const setup = () => {
  const values = new Map();
  return {
    getItem: (k) => values.get(k) ?? null,
    setItem: (k, v) => values.set(k, v),
  };
};
test('native tools do not open additional votes, AI execution or formal submission', () => {
  assert.ok(timeseriesInterfaces.includes('side-column'));
  for (const name of [
    'submit',
    'skip',
    'auto-annotation',
    'annotations:add-new',
    'annotations:delete',
  ])
    assert.ok(!timeseriesInterfaces.includes(name));
  assert.equal(draftButtons._replace[0].draftOnly, true);
});
test('empty draft can be saved and recovered independently of sample completion', () => {
  const storage = setup(),
    input = { sampleId: 's', inputHash: 'hash' };
  saveLocalLsDraft(storage, input, 'config', []);
  assert.deepEqual(readLocalLsDraft(storage, input, 'config'), []);
});
test('rejects stale input, changed specification and different sample instead of mixing answers', () => {
  const storage = setup(),
    input = { sampleId: 's', inputHash: 'hash' };
  saveLocalLsDraft(storage, input, 'config', [{ type: 'choices' }]);
  assert.throws(
    () => readLocalLsDraft(storage, { ...input, inputHash: 'new' }, 'config'),
    /不匹配/,
  );
  assert.throws(() => readLocalLsDraft(storage, input, 'new-config'), /不匹配/);
  assert.throws(
    () =>
      readLocalLsDraft(storage, { ...input, sampleId: 'another' }, 'config'),
    /没有本地草稿/,
  );
});
test('storage failures propagate, never produce a success receipt', () => {
  assert.throws(
    () =>
      saveLocalLsDraft(
        {
          setItem() {
            throw new Error('quota');
          },
        },
        { sampleId: 's' },
        'config',
        [],
      ),
    /quota/,
  );
});
