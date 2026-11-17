import { loadTypeScriptModule } from './load-typescript-module.mjs';

/** Offline component tests replace UI primitives, hooks and explicit I/O boundaries only. */
export function renderHooks(entry, props, dependencies = {}, globals = {}) {
  const hooks = [],
    cleanups = [];
  let index = 0,
    effects = [],
    tree;
  const react = {
    createElement: (type, attrs, ...children) => ({
      type,
      props: { ...attrs, children: children.flat(Infinity) },
    }),
    useState(initial) {
      const i = index++;
      if (!(i in hooks))
        hooks[i] = typeof initial === 'function' ? initial() : initial;
      return [
        hooks[i],
        (value) => {
          hooks[i] = typeof value === 'function' ? value(hooks[i]) : value;
        },
      ];
    },
    useRef(initial) {
      const i = index++;
      if (!(i in hooks)) hooks[i] = { current: initial };
      return hooks[i];
    },
    useEffect(effect, deps) {
      const i = index++;
      if (!hooks[i] || deps.some((d, j) => !Object.is(d, hooks[i][j]))) {
        hooks[i] = deps;
        effects.push(() => {
          cleanups[i]?.();
          cleanups[i] = effect();
        });
      }
    },
    useId() {
      const i = index++;
      return `control-${i}`;
    },
  };
  const { default: Component } = loadTypeScriptModule(
    entry,
    { react, ...dependencies },
    { React: react, AbortController, ...globals },
  );
  return {
    props,
    render() {
      index = 0;
      effects = [];
      tree = Component(props);
      for (const effect of effects) effect();
      return tree;
    },
    nodes(type) {
      const result = [];
      function visit(node) {
        if (!node || typeof node !== 'object') return;
        if (node.type === type) result.push(node);
        for (const child of node.props?.children || []) visit(child);
      }
      visit(tree);
      return result;
    },
    dispose() {
      cleanups.forEach((cleanup) => {
        cleanup?.();
      });
    },
  };
}
export const flushHooks = async () => {
  for (let i = 0; i < 8; i++)
    await new Promise((resolve) => setImmediate(resolve));
};
