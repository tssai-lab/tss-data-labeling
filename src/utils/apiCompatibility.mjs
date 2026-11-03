/**
 * 旧接口是版本兼容路径，不是失败重试路径。
 * 只有“不支持该接口”才允许回退；权限、限流、业务错误以及结果不明的超时/5xx
 * 必须原样交给调用者。特别注意：业务 404（例如资产不可见）不代表接口不存在。
 */
export function isLegacyEndpointUnavailable(error) {
  const status = Number(error?.response?.status ?? error?.info?.status ?? error?.status);
  const errorCode = error?.response?.data?.errorCode ?? error?.info?.errorCode ?? error?.errorCode;
  if (errorCode) return false;
  return status === 404 || status === 405 || status === 501;
}
