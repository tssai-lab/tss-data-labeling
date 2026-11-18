// LS keeps shared model caches and hotkeys at module scope. Preview and execution
// must finish destroying the previous instance before constructing the next one.
let available = Promise.resolve();
export async function acquireEditorLease() {
  const previous = available;
  let unlock;
  available = new Promise(resolve => { unlock = resolve; });
  await previous;
  let released = false;
  return () => {
    if (released) return;
    released = true;
    unlock();
  };
}
