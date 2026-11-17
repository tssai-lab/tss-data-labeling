import { acquireEditorLease } from './editorLifecycle.mjs';

// LS Preview lifecycle: reset and reuse; annotation creation waits for SharedStore.
export function createEditorSession(LabelStudio, container, options = {}) {
  let instance, release, closed = false, queue = Promise.resolve(), pending;
  const initialize = store => {
    const current = pending;
    setTimeout(() => {
      if (closed || current !== pending) { current?.resolve(null); return; }
      try {
        const as = store.annotationStore;
        const a = as.annotations?.[0] || as.createAnnotation();
        as.selectAnnotation(a.id);
        if (current.value.result) a.deserializeResults(current.value.result);
        a.setReadonly(!!current.value.readOnly);
        current.resolve({ instance, annotation: a });
      } catch (error) { current.reject(error); }
    }, 0);
  };
  return {
    update(value) {
      const operation = queue.then(async () => {
        if (closed) return null;
        const initialized = new Promise((resolve, reject) => { pending = { value, resolve, reject }; });
        if (!instance) {
          release = await acquireEditorLease();
          if (closed) { release(); return null; }
          instance = new LabelStudio(container, { ...options, config: value.config, task: value.task, onStorageInitialized: initialize });
          await instance.ready;
        } else {
          const store = instance.store;
          store.resetState();
          store.assignTask(value.task);
          store.assignConfig(value.config);
          store.initializeStore(value.task);
        }
        return initialized;
      });
      queue = operation.catch(() => {});
      return operation;
    },
    destroy() {
      closed = true;
      pending?.resolve(null);
      queue = queue.finally(() => {
        try { instance?.destroy?.(); }
        finally { release?.(); instance = null; }
      });
      return queue;
    },
  };
}
