type Annotation = {
  validate: () => boolean;
  setReadonly: (value: boolean) => void;
  id: string;
  deserializeResults: (result: unknown[]) => void;
  serializeAnnotation: () => unknown[];
};
type Store = {
  queuePosition: number;
  annotationStore: {
    addAnnotation: (options: {
      userGenerate: boolean;
      createdBy?: string;
    }) => Annotation;
    selectAnnotation: (id: string) => void;
    selected: Annotation;
  };
};
export type LsEditorInstance = {
  on: (
    event: string,
    callback: (
      store: Store,
      name: string,
      data: { entity: Annotation },
    ) => unknown,
  ) => void;
  ready: Promise<void>;
  destroy: (() => void) | null;
  store: Store;
};
export type EditorSession = {
  update: (value: {
    config: string;
    task: unknown;
    result?: unknown[];
    readOnly?: boolean;
  }) => Promise<{ instance: LsEditorInstance; annotation: Annotation } | null>;
  destroy: () => Promise<unknown>;
};
type Runtime = {
  createEditorSession: (
    constructor: Runtime['LabelStudio'],
    container: Element,
    options: Omit<
      ConstructorParameters<Runtime['LabelStudio']>[1],
      'config' | 'task' | 'onStorageInitialized'
    >,
  ) => EditorSession;
  waitForPreviewDisposal: () => Promise<unknown>;
  createLocalEditorTask: (
    config: string,
    task: unknown,
    urls: string[],
  ) => unknown;
  validateLabelConfig: (config: string) => string;
  mountNativeTemplateConfigurator: (
    container: Element,
    options: {
      config: string;
      task: unknown;
      category?: 'Computer Vision' | 'Time Series Analysis';
      request: (
        name: string,
        options?: { body?: { label_config?: string } },
      ) => Promise<unknown>;
      onChange: (config: string, error: string | null) => void;
      onRecipe: (recipe: {
        id: string;
        version: string;
        title: string;
      }) => void;
      onSave: () => Promise<void>;
    },
  ) => { destroy: () => void };
  LabelStudio: new (
    container: Element,
    options: {
      config: string;
      task: unknown;
      instanceOptions: { reactVersion: 'v18' };
      interfaces: string[];
      description?: string;
      customButtons?: Record<
        string,
        {
          name: string;
          title: string;
          draftOnly?: boolean;
          ariaLabel?: string;
        }[]
      >;
      onStorageInitialized: (store: Store) => void;
    },
  ) => LsEditorInstance;
  attachStyles: (root: ShadowRoot) => void;
  acquireEditorLease: () => Promise<() => void>;
};
declare global {
  interface Window {
    PlatformLs?: Runtime;
  }
}
let loading: Promise<Runtime> | undefined;
export function loadLsEditor(): Promise<Runtime> {
  if (window.PlatformLs) return Promise.resolve(window.PlatformLs);
  if (!loading)
    loading = (async () => {
      const response = await fetch('/ls-editor/manifest.json', {
        cache: 'no-store',
      });
      if (!response.ok) throw new Error('标注界面资源清单加载失败');
      const manifest: { protocolVersion: number; entry: string } =
        await response.json();
      if (
        manifest.protocolVersion !== 1 ||
        !/^editor\.[a-f0-9]{12}\.js$/.test(manifest.entry)
      )
        throw new Error('标注界面资源清单无效');
      return new Promise<Runtime>((resolve, reject) => {
        const script = document.createElement('script');
        script.src = `/ls-editor/${manifest.entry}`;
        script.onload = () =>
          window.PlatformLs
            ? resolve(window.PlatformLs)
            : reject(new Error('标注界面初始化失败，请重新加载'));
        script.onerror = () => {
          script.remove();
          reject(new Error('标注界面加载失败，请检查本地服务后重试'));
        };
        document.head.append(script);
      });
    })().catch((error) => {
      loading = undefined;
      throw error;
    });
  return loading;
}
