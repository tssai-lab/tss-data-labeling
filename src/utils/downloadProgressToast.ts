import { message } from 'antd';

/** 每次下载独立 key，避免并发互相覆盖 */
let downloadToastSeq = 0;

export type DownloadProgressHandle = {
  key: string;
  update: (ratio: number | null) => void;
  close: () => void;
};

/** 开始一条下载进度提示；返回 update/close 供该次下载独占使用 */
export function beginDownloadProgress(): DownloadProgressHandle {
  downloadToastSeq += 1;
  const key = `auth-file-download-${downloadToastSeq}`;
  const update = (ratio: number | null) => {
    const content =
      typeof ratio === 'number' && Number.isFinite(ratio)
        ? `正在下载 ${Math.min(100, Math.max(0, Math.round(ratio * 100)))}%…`
        : '正在下载…';
    // 与项目其它处一致，用 loading API，保证立刻可见
    message.loading({ content, key, duration: 0 });
  };
  update(null);
  return {
    key,
    update,
    close: () => {
      message.destroy(key);
    },
  };
}

/** @deprecated 兼容旧调用名 */
export function showDownloadProgress(ratio: number | null): void {
  message.loading({
    content:
      typeof ratio === 'number' && Number.isFinite(ratio)
        ? `正在下载 ${Math.min(100, Math.max(0, Math.round(ratio * 100)))}%…`
        : '正在下载…',
    key: 'auth-file-download-legacy',
    duration: 0,
  });
}

/** @deprecated 兼容旧调用名 */
export function clearDownloadProgress(): void {
  message.destroy('auth-file-download-legacy');
}
