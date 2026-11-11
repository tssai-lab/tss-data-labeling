/** 登录页区分服务故障与账号错误，避免直接展示 Axios 的英文错误。 */
export function loginErrorMessage(error, fallback = '登录失败，请重试！') {
  const status = error?.response?.status;
  if ([502, 503, 504].includes(status)) {
    return '登录服务暂时不可用，请稍后重试；若持续失败，请联系管理员。';
  }
  if (['ECONNABORTED', 'ETIMEDOUT'].includes(error?.code)) {
    return '登录请求超时，请稍后重试。';
  }
  if (error?.code === 'ERR_NETWORK') {
    return '无法连接登录服务，请检查网络连接后重试。';
  }
  const candidates = [
    error?.info?.message,
    error?.info?.errorMessage,
    error?.response?.data?.msg,
    error?.response?.data?.message,
    error?.message,
  ];
  return candidates.find((value) => typeof value === 'string' && value.trim())?.trim() || fallback;
}
