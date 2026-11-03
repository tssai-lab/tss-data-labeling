import { history } from '@umijs/max';

const LOGIN_PATH = '/user/login';

/** 仅允许站内相对路径，避免开放重定向 */
export function sanitizeRedirect(raw?: string | null): string | undefined {
  if (!raw) return undefined;
  let value = raw.trim();
  try {
    value = decodeURIComponent(value);
  } catch {
    // 保持原值
  }
  if (!value.startsWith('/') || value.startsWith('//')) {
    return undefined;
  }
  if (value === LOGIN_PATH || value.startsWith(`${LOGIN_PATH}?`)) {
    return undefined;
  }
  return value;
}

/** 从当前 URL / history 读取登录回跳目标 */
export function getLoginRedirectTarget(fallback = '/dashboard'): string {
  const fromHistory = sanitizeRedirect(
    new URLSearchParams(history.location.search || '').get('redirect'),
  );
  if (fromHistory) return fromHistory;

  const fromWindow = sanitizeRedirect(
    new URL(window.location.href).searchParams.get('redirect'),
  );
  if (fromWindow) return fromWindow;

  return fallback;
}

/** 未登录 / 登录失效时跳转登录页，并带上原目标路径 */
export function redirectToLogin(options?: {
  replace?: boolean;
  /** 不传则使用当前 history 路径 */
  from?: string;
}) {
  const { pathname, search } = history.location;
  const from = options?.from ?? `${pathname}${search || ''}`;
  const safeFrom = sanitizeRedirect(from);
  const nextSearch = safeFrom
    ? `?redirect=${encodeURIComponent(safeFrom)}`
    : '';
  const nav = options?.replace ? history.replace : history.push;
  nav(`${LOGIN_PATH}${nextSearch}`);
}
