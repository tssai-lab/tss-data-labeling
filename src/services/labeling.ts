import { redirectToLogin } from '@/utils/loginRedirect';
import { STORAGE_KEYS, storage } from '@/utils/storage';

export type Page<T> = { items: T[]; nextCursor: string | null };
export type Label = { id: string; name: string };
export type SpecVersion = {
  specVersionId: string;
  versionNo: number;
  labels: Label[];
  instructions: string;
};
export type Project = {
  projectId: string;
  name: string;
  description: string;
  taskType: string;
  status: string;
  revision: number;
  createdAt: string;
  specVersions?: SpecVersion[];
};
export type Batch = {
  batchId: string;
  projectId: string;
  status: 'UPLOADING' | 'PROCESSING' | 'READY' | 'FAILED';
  sizeBytes: number;
  receivedBytes: number;
  chunkSizeBytes: number;
  originalComplete: boolean;
  validSampleCount: number;
  errorCount: number;
  errors: { sourcePath: string; code: string; message: string }[];
  errorCode?: string | null;
};
export type Sample = {
  sampleId: string;
  projectId: string;
  batchId: string;
  sourcePath: string;
  mediaType: string;
  metadata: { width: number; height: number; sizeBytes: number };
};

export class LabelingError extends Error {
  constructor(
    message: string,
    public status: number,
    public code?: string,
    public requestId?: string,
  ) {
    super(message);
    this.name = 'LabelingError';
  }
}

const prefix = '/api/labeling/v1';
export function operationKey(): string {
  // getRandomValues also works on the existing HTTP deployment.
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) =>
    b.toString(16).padStart(2, '0'),
  ).join('');
}

async function response(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = storage.get<string>(STORAGE_KEYS.TOKEN);
  if (!token) {
    redirectToLogin();
    throw new LabelingError('请先登录', 401);
  }
  headers.set('Authorization', `Bearer ${token}`);
  const res = await fetch(`${prefix}${path}`, {
    ...init,
    headers,
    signal: init.signal ?? AbortSignal.timeout(120000),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    const error = body?.error;
    if (res.status === 401) {
      storage.remove(STORAGE_KEYS.TOKEN);
      redirectToLogin();
    }
    throw new LabelingError(
      typeof error?.message === 'string'
        ? error.message
        : `请求失败（${res.status}）`,
      res.status,
      typeof error?.code === 'string' ? error.code : undefined,
      body?.requestId,
    );
  }
  return res;
}

export async function labelingJson<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const res = await response(path, init);
  const body = await res.json();
  if (!body || !Object.hasOwn(body, 'data') || body.data === null) {
    throw new LabelingError(
      '接口响应格式异常，请检查标注服务的转发配置',
      res.status,
    );
  }
  return body.data as T;
}

export function post<T>(path: string, body: unknown, key: string): Promise<T> {
  return labelingJson<T>(path, {
    method: 'POST',
    headers: {
      'Idempotency-Key': key,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

export function pageQuery(cursor?: string | null): string {
  const query = new URLSearchParams({ limit: '20' });
  if (cursor) query.set('cursor', cursor);
  return query.toString();
}

export async function sampleBlob(
  projectId: string,
  sampleId: string,
  signal: AbortSignal,
): Promise<Blob> {
  const res = await response(
    `/projects/${encodeURIComponent(projectId)}/samples/${encodeURIComponent(sampleId)}/file`,
    { signal },
  );
  if (
    !['image/jpeg', 'image/png'].includes(
      (res.headers.get('Content-Type') ?? '').split(';')[0],
    )
  ) {
    throw new Error('图片接口未返回 JPEG/PNG，请检查服务配置');
  }
  return res.blob();
}
