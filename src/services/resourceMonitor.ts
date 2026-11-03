/**
 * 算力资源监控 - Services 层
 * 只读取真实接口；失败由页面展示错误，过期采样必须标记，不以假数据冒充成功。
 */
import { request } from '@umijs/max';
import { API_CONFIG } from '@/constants/platform';

export type MetricInterval = '1min' | '10min' | '1hour' | '1day';

export type ResourceMonitorResponse<T> = {
  success: boolean;
  data?: T;
  errorMessage?: string | null;
};

export type AddServerPayload = {
  serverIp: string;
  hostname: string;
  specs?: {
    cpu?: string;
    memory?: string;
    gpu?: string;
    os?: string;
  };
};

const { ENDPOINTS } = API_CONFIG;

function requestFailure<T>(message: string): ResourceMonitorResponse<T> {
  return { success: false, errorMessage: message };
}

/** GET /resource-monitor/summary */
export async function fetchResourceMonitorSummary(options?: { skipErrorHandler?: boolean }) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorSummary>>(
      ENDPOINTS.RESOURCE_MONITOR_SUMMARY,
      { method: 'GET', ...(options || {}) },
    );
  } catch {
    return requestFailure<API.ResourceMonitorSummary>('无法读取真实资源汇总');
  }
}

/** GET /resource-monitor/servers */
export async function fetchResourceMonitorServers(
  params?: { keyword?: string; status?: string },
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorServerItem[]>>(
      ENDPOINTS.RESOURCE_MONITOR_SERVERS,
      { method: 'GET', params, ...(options || {}) },
    );
  } catch {
    return requestFailure<API.ResourceMonitorServerItem[]>('无法读取真实服务器状态');
  }
}

/** GET /resource-monitor/servers/{serverIp} */
export async function fetchResourceMonitorServerDetail(
  serverIp: string,
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorServerItem>>(
      ENDPOINTS.RESOURCE_MONITOR_SERVER(serverIp),
      { method: 'GET', ...(options || {}) },
    );
  } catch {
    return requestFailure<API.ResourceMonitorServerItem>('无法读取真实服务器详情');
  }
}

/** GET /resource-monitor/servers/{serverIp}/metrics */
export async function fetchResourceMonitorMetrics(
  serverIp: string,
  interval: MetricInterval = '1hour',
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorMetrics>>(
      ENDPOINTS.RESOURCE_MONITOR_METRICS(serverIp),
      { method: 'GET', params: { interval }, ...(options || {}) },
    );
  } catch {
    return requestFailure<API.ResourceMonitorMetrics>('无法读取真实资源趋势');
  }
}

/** GET /resource-monitor/kubernetes/diagnostics（仅超级管理员） */
export async function fetchKubernetesDiagnostics(
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.KubernetesDiagnostics>>(
      ENDPOINTS.RESOURCE_MONITOR_KUBERNETES_DIAGNOSTICS,
      { method: 'GET', ...(options || {}) },
    );
  } catch {
    return requestFailure<API.KubernetesDiagnostics>('无法读取 Kubernetes 诊断详情');
  }
}

/** POST /resource-monitor/servers */
export async function createResourceMonitorServer(
  payload: AddServerPayload,
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorServerItem>>(
      ENDPOINTS.RESOURCE_MONITOR_SERVERS,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        data: payload,
        ...(options || {}),
      },
    );
  } catch {
    return requestFailure<API.ResourceMonitorServerItem>('添加服务器失败，未写入真实环境');
  }
}

/** DELETE /resource-monitor/servers/{serverIp} */
export async function deleteResourceMonitorServer(
  serverIp: string,
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<null>>(
      ENDPOINTS.RESOURCE_MONITOR_SERVER(serverIp),
      { method: 'DELETE', ...(options || {}) },
    );
  } catch {
    return requestFailure<null>('删除服务器失败，真实环境未变更');
  }
}

/** PUT /resource-monitor/servers/{serverIp}/enabled（启用/禁用，禁用后不再分配新任务） */
export async function updateResourceMonitorServerEnabled(
  serverIp: string,
  enabled: boolean,
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorServerItem>>(
      ENDPOINTS.RESOURCE_MONITOR_SERVER_ENABLED(serverIp),
      { method: 'PUT', data: { enabled }, ...(options || {}) },
    );
  } catch {
    return requestFailure<API.ResourceMonitorServerItem>('更新服务器状态失败，真实环境未变更');
  }
}

/** PUT /resource-monitor/servers/{serverIp}/queue/reorder */
export async function reorderResourceQueueTask(
  serverIp: string,
  body: { taskId: string; direction: 'up' | 'down' },
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<{ queuedTasks: API.ResourceMonitorQueuedTask[] }>>(
      ENDPOINTS.RESOURCE_MONITOR_QUEUE_REORDER(serverIp),
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        data: body,
        ...(options || {}),
      },
    );
  } catch {
    return requestFailure<{ queuedTasks: API.ResourceMonitorQueuedTask[] }>(
      '调整排队顺序失败，真实环境未变更',
    );
  }
}

/** PUT /resource-monitor/servers/{serverIp}/queue/priority */
export async function updateResourceQueuePriority(
  serverIp: string,
  body: { taskId: string; priority: '高' | '中' | '低' },
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<{ queuedTasks: API.ResourceMonitorQueuedTask[] }>>(
      ENDPOINTS.RESOURCE_MONITOR_QUEUE_PRIORITY(serverIp),
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        data: body,
        ...(options || {}),
      },
    );
  } catch {
    return requestFailure<{ queuedTasks: API.ResourceMonitorQueuedTask[] }>(
      '更新优先级失败，真实环境未变更',
    );
  }
}

/** DELETE /resource-monitor/servers/{serverIp}/queue/{taskId} */
export async function cancelResourceQueueTask(
  serverIp: string,
  taskId: string,
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<{ queuedTasks: API.ResourceMonitorQueuedTask[] }>>(
      ENDPOINTS.RESOURCE_MONITOR_QUEUE_TASK(serverIp, taskId),
      { method: 'DELETE', ...(options || {}) },
    );
  } catch {
    return requestFailure<{ queuedTasks: API.ResourceMonitorQueuedTask[] }>(
      '取消排队失败，真实环境未变更',
    );
  }
}

/** GET /resource-monitor/queue（全局排队，按资源池分组） */
export async function fetchResourceMonitorGlobalQueue(
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorGlobalQueuedTask[]>>(
      ENDPOINTS.RESOURCE_MONITOR_GLOBAL_QUEUE,
      { method: 'GET', ...(options || {}) },
    );
  } catch {
    return requestFailure<API.ResourceMonitorGlobalQueuedTask[]>('无法读取真实全局队列');
  }
}

/** PUT /resource-monitor/queue/reorder（全局排队同池内上移/下移） */
export async function reorderResourceMonitorGlobalQueue(
  body: { taskId: string; direction: 'up' | 'down' },
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorGlobalQueuedTask[]>>(
      ENDPOINTS.RESOURCE_MONITOR_GLOBAL_QUEUE_REORDER,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        data: body,
        ...(options || {}),
      },
    );
  } catch {
    return requestFailure<API.ResourceMonitorGlobalQueuedTask[]>(
      '调整全局队列失败，真实环境未变更',
    );
  }
}

/** DELETE /resource-monitor/queue/{taskId}（取消全局排队） */
export async function cancelResourceMonitorGlobalQueueTask(
  taskId: string,
  options?: { skipErrorHandler?: boolean },
) {
  try {
    return await request<ResourceMonitorResponse<API.ResourceMonitorGlobalQueuedTask[]>>(
      ENDPOINTS.RESOURCE_MONITOR_GLOBAL_QUEUE_TASK(taskId),
      { method: 'DELETE', ...(options || {}) },
    );
  } catch {
    return requestFailure<API.ResourceMonitorGlobalQueuedTask[]>(
      '取消全局排队失败，真实环境未变更',
    );
  }
}
