import { Alert, Button, Input, Space, Spin } from 'antd';
import { type ReactNode, useEffect, useId, useRef, useState } from 'react';
import {
  type CompletionRules,
  compileCompletion,
  validateCompletion,
} from '@/services/annotationCompletion.mjs';
import { configurationApi } from '@/services/labelingConfig';
import type { EnergyInput } from '@/services/labelingEnergy.mjs';
import { createPlatformTimeseriesTask } from '@/services/labelStudioTimeseries.mjs';
import {
  type EditorSession,
  type LsEditorInstance,
  loadLsEditor,
} from '@/services/loadLsEditor';
import styles from './annotationWorkspace.less';

type EditorSample = {
  id: string;
  name: string;
  input: unknown;
  image?: Blob;
  source?: unknown;
};

type Props = {
  compact?: boolean;
  footerActions?: ReactNode;
  instructions?: string;
  configProjectId?: string;
  reviewMode?: boolean;
  nativeTask?: { id: string; data: Record<string, unknown> };
  config: string;
  sample?: EditorSample;
  category: 'Computer Vision' | 'Time Series Analysis';
  result?: unknown[];
  onSave?: (
    value: unknown[],
    done: boolean,
    emptyReason?: string,
  ) => Promise<void>;
  completion?: CompletionRules;
  emptyReason?: string;
  onConfig?: (xml: string, templateId: string) => Promise<void>;
  onTemplateBrowserMode?: (active: boolean) => void;
  templateId?: string;
  onGuard?: (guard: () => boolean) => void;
};
let disposing: Promise<unknown> = Promise.resolve();
export default function LocalEditor(props: Props) {
  const reasonId = useId();
  const session = useRef<EditorSession | null>(null);
  useEffect(
    () => () => {
      if (session.current) disposing = session.current.destroy();
      session.current = null;
    },
    [],
  );
  const host = useRef<HTMLDivElement>(null),
    editor = useRef<LsEditorInstance | null>(null),
    latest = useRef(props);
  latest.current = props;
  useEffect(() => {
    if (!props.onConfig || !props.onTemplateBrowserMode) return;
    const handleMode = (event: Event) => {
      const active = (event as CustomEvent<{ active?: boolean }>).detail?.active;
      props.onTemplateBrowserMode?.(!!active);
    };
    window.addEventListener('labeling-template-browser-mode', handleMode);
    return () => {
      window.removeEventListener('labeling-template-browser-mode', handleMode);
    };
  }, [props.onConfig, props.onTemplateBrowserMode]);
  const snapshot = useRef('[]');
  const savingAction = useRef(false);
  const [error, setError] = useState(''),
    [ready, setReady] = useState(false),
    [saving, setSaving] = useState(false);
  const [retry, setRetry] = useState(0),
    [emptyReason, setEmptyReason] = useState(props.emptyReason || '');
  const reason = useRef(emptyReason),
    savedReason = useRef(props.emptyReason || '');
  reason.current = emptyReason;
  useEffect(() => {
    setEmptyReason(props.emptyReason || '');
    savedReason.current = props.emptyReason || '';
  }, [props.sample?.id, props.emptyReason]);
  useEffect(() => {
    let active = true,
      view: { destroy: () => void } | undefined;
    const urls: string[] = [];
    const configRequests = new AbortController();
    let currentXml = props.config,
      savedXml = props.config;
    const changed = () =>
      (!!props.onSave || !!props.onConfig) &&
      (reason.current !== savedReason.current ||
        (props.onConfig
          ? currentXml !== savedXml
          : !!editor.current?.store?.annotationStore?.selected &&
            JSON.stringify(
              editor.current.store.annotationStore.selected.serializeAnnotation(),
            ) !== snapshot.current));
    latest.current.onGuard?.(
      () =>
        !savingAction.current &&
        (!changed() || window.confirm('当前修改尚未保存，是否放弃并离开？')),
    );
    const beforeUnload = (e: BeforeUnloadEvent) => {
      if (savingAction.current || changed()) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', beforeUnload);
    setReady(false);
    setError('');
    void (async () => {
      const runtime = await loadLsEditor();
      await disposing;
      await runtime.waitForPreviewDisposal();
      if (!active || !host.current) return;
      const shadow =
        host.current.shadowRoot || host.current.attachShadow({ mode: 'open' });
      let node: HTMLDivElement;
      if (!props.onConfig && session.current)
        node = shadow.lastElementChild as HTMLDivElement;
      else {
        shadow.replaceChildren();
        runtime.attachStyles(shadow);
        node = document.createElement('div');
        node.className = 'root';
        shadow.append(node);
      }
      let task: unknown;
      if (props.nativeTask) {
        task = { ...props.nativeTask, platformNative: true };
      } else if (props.sample?.image) {
        const url = URL.createObjectURL(props.sample.image);
        urls.push(url);
        task = { id: props.sample.id, data: { image: url } };
      } else if (props.sample)
        task = createPlatformTimeseriesTask(props.sample.input);
      if (props.onConfig) {
        let xml = props.config,
          id = props.templateId || 'custom';
        view = runtime.mountNativeTemplateConfigurator(node, {
          config: xml,
          task,
          category: props.category,
          request: configurationApi(
            {
              projectId: props.configProjectId,
              sampleId: props.sample?.id,
              category: props.category,
            },
            (url) => (active ? urls.push(url) : URL.revokeObjectURL(url)),
            configRequests.signal,
          ),
          onChange: (value) => {
            xml = value;
            currentXml = value;
          },
          onRecipe: (recipe) => {
            id = recipe.id;
          },
          onSave: async () => {
            if (savingAction.current) return;
            savingAction.current = true;
            try {
              await latest.current.onConfig?.(xml, id);
              savedXml = xml;
            } finally {
              savingAction.current = false;
            }
          },
        });
      } else {
        if (!props.sample) throw Error('请先选择样本');
        runtime.validateLabelConfig(props.config);
        const data =
          props.nativeTask?.data ||
          runtime.createLocalEditorTask(props.config, task, urls);
        if (!session.current)
          session.current = runtime.createEditorSession(
            runtime.LabelStudio,
            node,
            {
              description: props.instructions || '',
              instanceOptions: { reactVersion: 'v18' },
              interfaces: props.onSave
                ? [
                    'topbar',
                    'side-column',
                    'controls',
                    'edit-history',
                    'instruction',
                    'annotations:current',
                  ]
                : ['side-column'],
              customButtons: { _replace: [] },
            },
          );
        const updated = await session.current.update({
          config: props.config,
          task: { id: props.sample.id, data, annotations: [], predictions: [] },
          result: props.result || [],
          readOnly: !props.onSave,
        });
        if (!active || !updated) return;
        editor.current = updated.instance;
        snapshot.current = JSON.stringify(
          updated.annotation.serializeAnnotation(),
        );
      }
      if (!active) view?.destroy();
      else setReady(true);
    })().catch((e) => {
      if (active) setError(e.message);
    });
    return () => {
      active = false;
      configRequests.abort();
      window.removeEventListener('beforeunload', beforeUnload);
      latest.current.onGuard?.(() => true);
      editor.current = null;
      view?.destroy();
      urls.forEach(URL.revokeObjectURL);
    };
  }, [
    props.config,
    props.sample?.id,
    props.nativeTask,
    props.instructions,
    !!props.onConfig,
    !!props.onSave,
    retry,
  ]);
  async function save(done: boolean) {
    if (savingAction.current || !editor.current || saving) return;
    savingAction.current = true;
    setSaving(true);
    try {
      const annotation = editor.current.store.annotationStore.selected;
      if (done && !annotation.validate())
        throw Error('当前标注未通过模板必填校验');
      const submitted = annotation.serializeAnnotation();
      if (done && !props.nativeTask)
        validateCompletion(
          submitted,
          props.completion || compileCompletion(props.config),
          { emptyReason, input: props.sample?.input },
        );
      await props.onSave?.(submitted, done, emptyReason);
      savedReason.current = emptyReason;
      snapshot.current = JSON.stringify(submitted);
      setError('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      savingAction.current = false;
      setSaving(false);
    }
  }
  return (
    <section
      className={props.compact ? styles.editorPane : undefined}
      style={{ minWidth: 0 }}
    >
      {error && <Alert type="error" message={error} />}
      {!props.onSave && props.emptyReason && (
        <Alert type="info" message={`无目标说明：${props.emptyReason}`} />
      )}
      {!ready && !error && (
        <Spin tip="正在加载标注界面">
          <div style={{ minHeight: 80 }} />
        </Spin>
      )}
      {!ready && error && (
        <Button onClick={() => setRetry((v) => v + 1)}>重新加载编辑器</Button>
      )}
      {ready && props.sample && !props.onConfig && !props.compact && (
        <p>
          当前样本：{props.sample.name} · {props.onSave ? '可编辑' : '只读'}
        </p>
      )}
      {ready &&
        !props.onConfig &&
        props.category === 'Time Series Analysis' &&
        (() => {
          const input = props.sample?.input as EnergyInput | undefined;
          if (input?.inputFormat !== 'energy-station-day-input-v1') return null;
          const missing =
            input?.payload?.series?.values.filter((v) => v === null).length ||
            0;
          return missing ? (
            <Alert
              type="warning"
              message={`当前样本有 ${missing} 个小时缺测${missing === 24 ? '，整日没有有效曲线值' : ''}。请依据项目规范处理，缺测不代表零能耗。`}
            />
          ) : null;
        })()}
      <div ref={host} style={{ maxWidth: '100%', overflowX: 'auto' }} />
      {props.completion?.allowEmpty && props.onSave && (
        <label htmlFor={reasonId}>
          无目标说明
          <Input.TextArea
            id={reasonId}
            aria-label="无目标说明"
            value={emptyReason}
            disabled={saving}
            onChange={(e) => setEmptyReason(e.target.value)}
            placeholder="确认没有可标注目标时，填写判断原因"
            maxLength={1000}
          />
        </label>
      )}
      {props.onSave && (
        <div className={props.compact ? styles.editorFooter : undefined}>
          <Space wrap style={props.compact ? undefined : { marginTop: 16 }}>
            {!props.reviewMode && (
              <Button
                disabled={!ready || saving}
                onClick={() => void save(false)}
              >
                保存草稿
              </Button>
            )}
            <Button
              type="primary"
              disabled={!ready || saving}
              onClick={() => void save(true)}
            >
              {props.reviewMode ? '保存修订并确认' : '标记完成并保存'}
            </Button>
            {props.footerActions && (
              <fieldset disabled={saving} className={styles.footerExtra}>
                {props.footerActions}
              </fieldset>
            )}
          </Space>
        </div>
      )}
    </section>
  );
}
