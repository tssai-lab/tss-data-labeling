import { describeLabelingError, labelingBlob, labelingJson } from './labeling';
import { setNativeField } from './nativeLabeling';

type Scope = {
  projectId?: string;
  sampleId?: string;
  category?: 'Computer Vision' | 'Time Series Analysis';
};
type ConfigurationResponse = {
  sample_task: Record<string, unknown>;
  resources?: {
    content?: string;
    mediaType: string;
    url?: string;
    field: string;
  }[];
};

// A template change can trigger validation, sample generation and a new preview
// together. Serialize this read-only transport instead of exhausting the bounded
// source-worker slots while obsolete component requests are still completing.
let configurationQueue: Promise<unknown> = Promise.resolve();

/** Transport used by the vendored ConfigPage; preview generation stays on the backend. */
export function configurationApi(
  scope: Scope,
  ownUrl: (url: string) => void,
  signal: AbortSignal,
) {
  return async (
    name: string,
    options: { body?: { label_config?: string } } = {},
  ) => {
    const execute = async () => {
      try {
        if (signal.aborted)
          throw new DOMException(
            'Configuration request cancelled',
            'AbortError',
          );
        if (name === 'configTemplates')
          return await labelingJson(
            `/label-configs/templates${scope.category ? `?category=${encodeURIComponent(scope.category)}` : ''}`,
            { signal },
          );
        if (name === 'dataSummary') {
          const query = new URLSearchParams({
            ...(scope.projectId ? { projectId: scope.projectId } : {}),
            ...(scope.projectId && scope.sampleId
              ? { sampleId: scope.sampleId }
              : {}),
          });
          return await labelingJson(`/label-configs/data-summary?${query}`, {
            signal,
          });
        }
        if (!['validateConfig', 'createSampleTask'].includes(name))
          throw Error('不支持的配置操作');
        const value = await labelingJson<ConfigurationResponse>(
          `/label-configs/${name === 'validateConfig' ? 'validate' : 'sample-task'}`,
          {
            signal,
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              labelConfig: options.body?.label_config,
              projectId: scope.projectId,
              sampleId: scope.projectId ? scope.sampleId : undefined,
              taskType:
                scope.category === 'Time Series Analysis' ||
                (!scope.category &&
                  /<TimeSeries\b/.test(options.body?.label_config || ''))
                  ? 'TIME_SERIES'
                  : 'COMPUTER_VISION',
            }),
          },
        );
        if (name === 'createSampleTask') {
          const created: string[] = [];
          try {
            for (const resource of value.resources || []) {
              let blob: Blob;
              if (typeof resource.content === 'string')
                blob = new Blob([resource.content], {
                  type: resource.mediaType,
                });
              else {
                if (!resource.url?.startsWith('/api/labeling/v1/'))
                  throw Error('预览资源地址无效');
                blob = await labelingBlob(
                  resource.url.slice('/api/labeling/v1'.length),
                  signal,
                );
              }
              const url = URL.createObjectURL(blob);
              created.push(url);
              setNativeField(value.sample_task, resource.field, url);
            }
            created.forEach(ownUrl);
          } catch (error) {
            created.forEach(URL.revokeObjectURL);
            throw error;
          }
        }
        return value;
      } catch (error) {
        return {
          error: true,
          response: {
            detail: '配置或预览暂不可用',
            validation_errors: [describeLabelingError(error)],
          },
        };
      }
    };
    const pending = configurationQueue.then(execute, execute);
    configurationQueue = pending.then(
      () => undefined,
      () => undefined,
    );
    return pending;
  };
}
