export function isApiSuccess(response) {
  return response?.code === 200 || response?.status === 'ok';
}

export function apiMessage(response, fallback) {
  const value = response?.message || response?.msg;
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

export function localSmsCode(response) {
  const value = response?.data?.code;
  return typeof value === 'string' && /^\d{6}$/.test(value) ? value : '';
}
