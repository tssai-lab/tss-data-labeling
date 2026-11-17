import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createEditorSession } from './editorSession.mjs';

test('reuse follows upstream reset order and restores only the current answer', async () => {
  let builds = 0, destroys = 0;
  const calls = [];
  class Editor {
    constructor(node, options) {
      builds++;
      const as = { annotations: [], createAnnotation() {
        const a = { id: 'answer', result: [], deserializeResults(v) { this.result = v; }, setReadonly(v) { this.readOnly = v; } };
        this.annotations.push(a); return a;
      }, selectAnnotation() {} };
      this.store = { annotationStore: as,
        resetState() { calls.push('reset'); as.annotations = []; },
        assignTask(task) { calls.push('task'); this.task = task; },
        assignConfig(config) { calls.push('config'); this.config = config; },
        initializeStore() { calls.push('initialize'); options.onStorageInitialized(this); } };
      this.ready = Promise.resolve().then(() => options.onStorageInitialized(this.store));
    }
    destroy() { destroys++; }
  }
  const session = createEditorSession(Editor, {}, {});
  const first = await session.update({ config: 'one', task: {id:1}, result: [{label:'first'}] });
  const second = await session.update({ config: 'two', task: {id:2}, result: [{label:'second'}], readOnly:true });
  assert.equal(builds,1); assert.equal(first.instance,second.instance);
  assert.deepEqual(calls,['reset','task','config','initialize']);
  assert.deepEqual(second.annotation.result,[{label:'second'}]); assert.equal(second.annotation.readOnly,true);
  await session.destroy(); await session.destroy(); assert.equal(destroys,1);
  assert.equal(await session.update({}),null);
});
