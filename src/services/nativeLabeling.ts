import {
  labelingJson,
  type Page,
  type Project,
  type SpecVersion,
} from './labeling';
export function setNativeField(
  data: Record<string, unknown>,
  field: string,
  value: unknown,
) {
  if (Object.hasOwn(data, field)) {
    Object.defineProperty(data, field, {
      value,
      enumerable: true,
      configurable: true,
      writable: true,
    });
    return;
  }
  const keys = field.replace(/\[(\d+)\]/g, '.$1').split('.');
  let node: Record<string, unknown> = data;
  for (const key of keys.slice(0, -1)) {
    if (
      !Object.hasOwn(node, key) ||
      !node[key] ||
      typeof node[key] !== 'object'
    )
      Object.defineProperty(node, key, {
        value: {},
        enumerable: true,
        configurable: true,
        writable: true,
      });
    node = node[key] as Record<string, unknown>;
  }
  Object.defineProperty(node, keys[keys.length - 1], {
    value,
    enumerable: true,
    configurable: true,
    writable: true,
  });
}
export type NativeSpec = SpecVersion & {
  taskType: string;
  schemaVersion: string;
  editorConfig: {
    labelConfig: string;
    templateId: string;
    templateVersion: string;
    completion?: { allowEmpty?: boolean };
    configSha256?: string;
    pluginVersion?: string;
    capabilities?: {
      version: string;
      controls: string[];
      aiAvailable: boolean;
    };
  };
};
export type NativeProject = Omit<Project, 'specVersions'> & {
  specVersions: NativeSpec[];
};
export type NativeSample = {
  sampleId: string;
  batchId: string;
  sourcePath: string;
  mediaType: string;
  contentSha256: string;
  metadata: {
    displayName?: string;
    columns?: string[];
    rowCount?: number;
    sizeBytes?: number;
    previewRows?: string[][];
    delimiter?: string;
  };
};
export type InputResource = Omit<NativeSample, 'sampleId'> & {
  resourceId: string;
};
export const categoryOf = (project: {
  taskType: string;
}): 'Time Series Analysis' | 'Computer Vision' =>
  project.taskType === 'TIME_SERIES'
    ? 'Time Series Analysis'
    : 'Computer Vision';
export async function collectPages<T>(
  path: string,
  signal?: AbortSignal,
): Promise<T[]> {
  const records: T[] = [];
  let cursor: string | null = null;
  do {
    const query = new URLSearchParams({
      limit: '100',
      ...(cursor ? { cursor } : {}),
    });
    const page: Page<T> = await labelingJson(
      `${path}${path.includes('?') ? '&' : '?'}${query}`,
      { signal },
    );
    records.push(...page.items);
    cursor = page.nextCursor;
  } while (cursor);
  return records;
}
export const nativeDefaults = {
  TIME_SERIES:
    '<View><TimeSeries name="series_view" value="$series" valueType="json" timeColumn="time"><Channel column="value"/></TimeSeries><Choices name="classification" toName="series_view" required="true"><Choice value="标签一"/><Choice value="标签二"/></Choices><TimeSeriesLabels name="events" toName="series_view"><Label value="事件"/></TimeSeriesLabels></View>',
  COMPUTER_VISION:
    '<View><Image name="image" value="$image"/><Choices name="classification" toName="image" required="true"><Choice value="标签一"/><Choice value="标签二"/></Choices></View>',
};
