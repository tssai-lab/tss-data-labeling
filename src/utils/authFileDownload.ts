import { STORAGE_KEYS, storage } from '@/utils/storage';

type AuthFileDownloadParams = {
  /** 相对路径如 /v2/model-versions/{id}/download，或已含 /api 前缀 */
  url: string;
  fileName: string;
  signal?: AbortSignal;
  /** 浏览器原生下载不暴露网络进度；开始申请时为 null，交给浏览器后为 1 */
  onProgress?: (ratio: number | null) => void;
};

type DownloadTicketResponse = {
  success?: boolean;
  data?: {
    downloadUrl?: string;
    expiresAt?: string;
  };
  errorMessage?: string;
  message?: string;
};

function resolveDownloadUrl(url: string): string {
  if (/^https?:\/\//i.test(url)) {
    throw new Error('下载地址必须是本平台的相对路径');
  }
  if (url.startsWith('/api/')) return url;
  if (url.startsWith('/')) return `/api${url}`;
  return `/api/${url}`;
}

async function readTicketError(response: Response): Promise<string> {
  try {
    const payload = (await response.json()) as DownloadTicketResponse;
    return (
      payload.errorMessage ||
      payload.message ||
      `下载请求失败（HTTP ${response.status}）`
    );
  } catch {
    return `下载请求失败（HTTP ${response.status}）`;
  }
}

async function issueDownloadTicket(
  target: string,
  signal?: AbortSignal,
): Promise<string> {
  const token = storage.get<string>(STORAGE_KEYS.TOKEN);
  const response = await fetch('/api/download-tickets', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${String(token)}` } : {}),
    },
    body: JSON.stringify({ target }),
    signal,
  });
  if (!response.ok) {
    throw new Error(await readTicketError(response));
  }
  const payload = (await response.json()) as DownloadTicketResponse;
  const downloadUrl = payload.data?.downloadUrl;
  if (payload.success === false || !downloadUrl?.startsWith('/api/')) {
    throw new Error(payload.errorMessage || '服务器未返回有效下载地址');
  }
  return downloadUrl;
}

/** 由浏览器下载管理器接管；文件内容不进入 JavaScript 内存。 */
function triggerNativeDownload(downloadUrl: string, fileName: string) {
  const link = document.createElement('a');
  link.href = downloadUrl;
  link.download = fileName;
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
}

/**
 * 带鉴权的浏览器原生下载。
 *
 * 先用登录态换取 60 秒、单次、绑定精确目标的一次性票据，再让普通链接
 * 触发下载。原下载 Controller 仍执行同一套资产权限校验。
 */
export async function downloadAuthFile(
  params: AuthFileDownloadParams,
): Promise<void> {
  const target = resolveDownloadUrl(params.url);
  const fileName = params.fileName.trim() || 'download.bin';
  params.onProgress?.(null);
  const downloadUrl = await issueDownloadTicket(target, params.signal);
  triggerNativeDownload(downloadUrl, fileName);
  params.onProgress?.(1);
}
