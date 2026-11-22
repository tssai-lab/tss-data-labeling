import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  labelingBlob,
  labelingJson,
  sampleBlob,
} from '@/services/labeling';
import type { NativeSample } from '@/services/nativeLabeling';
import type { NativeContext } from './NativeEditor';

export const aiSampleName = (sample: NativeSample) =>
  sample.metadata.displayName || sample.sourcePath;

function resourceFileName(
  data: Record<string, unknown>,
  field: string,
): string | undefined {
  const path = field.split('.').reduce<unknown>((value, key) => {
    if (Array.isArray(value)) return value[Number(key)];
    if (value && typeof value === 'object' && Object.hasOwn(value, key))
      return (value as Record<string, unknown>)[key];
    return undefined;
  }, data);
  if (typeof path !== 'string') return undefined;
  const name = path.split(/[?#]/)[0].split(/[\\/]/).pop();
  if (!name) return undefined;
  try {
    return decodeURIComponent(name);
  } catch {
    return name;
  }
}

type ImagePreview = {
  url: string;
  fileName?: string;
  error?: string;
  loading: boolean;
};

export function useAiSampleImage(
  projectId: string,
  sampleId: string | undefined,
  mediaType: string | undefined,
  enabled: boolean,
): ImagePreview {
  const key = enabled && sampleId ? `${projectId}/${sampleId}` : undefined;
  const [state, setState] = useState<ImagePreview & { key: string }>();
  useEffect(() => {
    if (!key || !sampleId) return;
    const controller = new AbortController();
    const { signal } = controller;
    let objectUrl = '';
    let fileName: string | undefined;
    void (async () => {
      try {
        const context = await labelingJson<NativeContext>(
          `/projects/${encodeURIComponent(projectId)}/samples/${encodeURIComponent(sampleId)}/native-input`,
          { signal },
        );
        const resource = context.resources?.find((item) =>
          item.mediaType.startsWith('image/'),
        );
        fileName = resource
          ? resourceFileName(context.task.data, resource.field)
          : undefined;
        if (resource) {
          const blob = await labelingBlob(
            resource.url.replace(/^\/api\/labeling\/v1/, ''),
            signal,
          );
          if (!signal.aborted) objectUrl = URL.createObjectURL(blob);
        } else if (mediaType?.startsWith('image/')) {
          const blob = await sampleBlob(projectId, sampleId, signal);
          if (!signal.aborted) objectUrl = URL.createObjectURL(blob);
        }
        if (!signal.aborted) {
          setState({
            key,
            url: objectUrl,
            fileName,
            loading: false,
            error: objectUrl ? undefined : '此样本没有可预览的图片',
          });
        }
      } catch (error) {
        if (!signal.aborted)
          setState({
            key,
            url: '',
            fileName,
            loading: false,
            error: describeLabelingError(error),
          });
      }
    })();
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [key, mediaType, projectId, sampleId]);
  return state && state.key === key
    ? state
    : { url: '', loading: Boolean(key) };
}
