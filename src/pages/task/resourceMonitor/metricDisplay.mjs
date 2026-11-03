export function isMetricAvailable(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

export function formatMetric(value, suffix = '') {
  return isMetricAvailable(value) ? `${value}${suffix}` : '无数据';
}
