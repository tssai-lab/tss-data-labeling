export const METRICS_STATUS_META = {
  fresh: { label: '实时', color: 'success', alertType: 'success' },
  temporarily_unavailable: {
    label: '采集失败',
    color: 'warning',
    alertType: 'warning',
  },
  stale: { label: '数据过期', color: 'error', alertType: 'error' },
  unavailable: { label: '无指标', color: 'default', alertType: 'warning' },
};

export function getMetricsStatusMeta(status) {
  return METRICS_STATUS_META[status] || METRICS_STATUS_META.unavailable;
}

export function getNodeWarnings(server) {
  if (!server?.nodeHealthStatus || server.nodeHealthStatus === 'unavailable') {
    return ['节点状态不可用'];
  }
  const warnings = [];
  if (server.nodeReady !== true) warnings.push('节点未就绪');
  if (server.nodeUnschedulable) warnings.push('已禁止调度');
  if (server.nodeMemoryPressure) warnings.push('内存压力');
  if (server.nodeDiskPressure) warnings.push('磁盘压力');
  if (server.nodePidPressure) warnings.push('进程数压力');
  return warnings;
}
