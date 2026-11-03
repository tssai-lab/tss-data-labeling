import { message } from 'antd';

/** 页面 catch 中使用：BizError 已由 app.tsx 全局 errorHandler 提示，避免重复弹窗 */
export function notifyRequestError(error: unknown, fallback: string) {
  const err = error as { name?: string; message?: string };
  if (err.name !== 'BizError') {
    message.error(err.message || fallback);
  }
}
