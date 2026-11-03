import { storage, STORAGE_KEYS } from '@/utils/storage';

export async function serverRequest(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${storage.get<string>(STORAGE_KEYS.TOKEN) || ''}`);
  const response = await fetch(`/label-api${path}`, { ...init, headers, signal: init.signal || AbortSignal.timeout(120000) });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `请求失败（${response.status}）`);
  }
  return response;
}
export async function serverJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  return (await serverRequest(path, init)).json();
}
export type ServerProject = { id: string; name: string; filename: string; status: string; count: number; skipped: number; error: string; size: number; offset: number };
