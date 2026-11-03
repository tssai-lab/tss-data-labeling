const PUBLIC_AUTH_PATHS = Object.freeze([
  '/user/login',
  '/user/register/username',
  '/user/register/mobile',
  '/user/sms/code',
  '/user/forget/password',
]);

function responseCode(error) {
  return error?.response?.data?.code ?? error?.info?.code;
}

function requestPath(error) {
  const raw = error?.config?.url;
  if (typeof raw !== 'string' || raw.length === 0) return '';
  try {
    const url = new URL(raw, 'http://local.invalid');
    return url.pathname.replace(/^\/api(?=\/)/, '');
  } catch {
    return raw.replace(/^\/api(?=\/)/, '').split('?')[0];
  }
}

/** 登录、注册和验证码接口的 401 是本次请求失败，不代表已有会话过期。 */
export function isPublicAuthenticationRequest(error) {
  const path = requestPath(error);
  return PUBLIC_AUTH_PATHS.some(
    (publicPath) => path === publicPath || path.startsWith(`${publicPath}/`),
  );
}

/** 同时识别 HTTP 401 与后端 Result.code=401。 */
export function isUnauthorizedResponse(error) {
  if (isPublicAuthenticationRequest(error)) return false;
  return error?.response?.status === 401 || responseCode(error) === 401;
}

/** 防止轮询或并发请求同时触发多次提示和跳转。 */
export function createUnauthorizedOnceGate() {
  let handled = false;
  let handledToken = null;
  return {
    run(currentToken, effect) {
      if (handled && (!currentToken || currentToken === handledToken)) {
        return false;
      }
      handled = true;
      handledToken = currentToken || null;
      effect();
      return true;
    },
    isHandled() {
      return handled;
    },
  };
}

/** 首次加载时静默校验；只有已有令牌的业务请求失效才提示。 */
export function shouldNotifySessionExpired(token, silent = false) {
  return typeof token === 'string' && token.length > 0 && !silent;
}
