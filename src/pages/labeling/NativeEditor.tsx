import { Alert, Button, Spin } from 'antd';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  labelingBlob,
  labelingJson,
} from '@/services/labeling';
import { setNativeField } from '@/services/nativeLabeling';
import LocalEditor from './LocalEditor';

export type NativeContext = {
  task: { id: string; data: Record<string, unknown> };
  resources: { field: string; url: string; mediaType: string }[];
  editorConfig?: { labelConfig: string; completion?: { allowEmpty?: boolean } };
  sourcePath: string;
  displayName?: string;
};
export default function NativeEditor({
  path,
  category,
  result,
  onSave,
  config,
  onConfig,
  onTemplateBrowserMode,
  templateId,
  emptyReason,
  onGuard,
  reviewMode,
  configProjectId,
  instructions,
  compact,
  footerActions,
  navigation,
  refreshKey,
}: {
  refreshKey?: string;
  compact?: boolean;
  footerActions?: ReactNode;
  navigation?: ReactNode;
  instructions?: string;
  configProjectId?: string;
  path: string;
  sampleId: string;
  category: 'Computer Vision' | 'Time Series Analysis';
  result?: unknown[];
  onSave?: (
    value: unknown[],
    done: boolean,
    emptyReason?: string,
  ) => Promise<void>;
  config?: string;
  onConfig?: (xml: string, template: string) => Promise<void>;
  onTemplateBrowserMode?: (active: boolean) => void;
  templateId?: string;
  emptyReason?: string;
  onGuard?: (guard: () => boolean) => void;
  reviewMode?: boolean;
}) {
  const [loading, setLoading] = useState(true);
  const [context, setContext] = useState<NativeContext>();
  const [error, setError] = useState(''),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    const urls: string[] = [];
    setLoading(true);
    setError('');
    void (async () => {
      const response = await labelingJson<NativeContext>(path, {
        signal: abort.signal,
      });
      const task = structuredClone(response.task);
      for (const resource of response.resources) {
        if (onConfig) break; // ConfigPage obtains authorized preview resources from its HTTP endpoint.
        if (!resource.url.startsWith('/api/labeling/v1/'))
          throw Error('输入资源地址无效');
        const blob = await labelingBlob(
          resource.url.slice('/api/labeling/v1'.length),
          abort.signal,
        );
        if (abort.signal.aborted) return;
        const url = URL.createObjectURL(blob);
        urls.push(url);
        setNativeField(task.data, resource.field, url);
      }
      if (!abort.signal.aborted) setContext({ ...response, task });
    })()
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => {
      abort.abort();
      urls.forEach((url) => {
        URL.revokeObjectURL(url);
      });
    };
  }, [path, retry, refreshKey]);
  if (error)
    return (
      <Alert
        type="error"
        message={error}
        action={<Button onClick={() => setRetry((v) => v + 1)}>重试</Button>}
      />
    );
  if (!context)
    return (
      <Spin tip="正在读取样本">
        <div style={{ height: 100 }} />
      </Spin>
    );
  const xml = config || context.editorConfig?.labelConfig || '<View/>';
  const actualCategory = /<TimeSeries\b/.test(xml)
    ? 'Time Series Analysis'
    : /<Image\b/.test(xml)
      ? 'Computer Vision'
      : category;
  return (
    <Spin spinning={loading}>
      <div inert={loading}>
        <LocalEditor
          nativeTask={context.task}
          config={xml}
          configProjectId={configProjectId}
          instructions={instructions}
          compact={compact}
          footerActions={footerActions}
          navigation={navigation}
          sample={{
            id: context.task.id,
            name: context.displayName || context.sourcePath,
            input: undefined,
          }}
          category={actualCategory}
          result={result}
          completion={{
            allowEmpty: !!context.editorConfig?.completion?.allowEmpty,
            required: [],
          }}
          emptyReason={emptyReason}
          onSave={onSave}
          onConfig={onConfig}
          onTemplateBrowserMode={onTemplateBrowserMode}
          templateId={templateId}
          onGuard={onGuard}
          reviewMode={reviewMode}
        />
      </div>
    </Spin>
  );
}
