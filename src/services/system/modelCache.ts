import { request } from '@umijs/max';

export interface ModelCacheEntry {
  sha256: string;
  storagePath: string;
  artifactSizeBytes: number;
  dataSizeBytes: number;
  diskSizeBytes: number;
  createdAtEpochSeconds: number;
  lastUsedAtEpochSeconds: number;
  inUse: boolean;
  valid: boolean;
}

export interface ModelCacheNode {
  serverIp: string;
  hostname: string;
  k8sNodeName: string;
  cacheReady: boolean;
  usedBytes: number;
  diskFreeBytes: number;
  diskTotalBytes: number;
  requiredAvailableBytes: number;
  policyHeadroomBytes: number;
  entries: ModelCacheEntry[];
  error?: string | null;
}

export interface ModelCacheOverview {
  enabled: boolean;
  maxBytes: number;
  minFreeBytes: number;
  runtimeReserveBytes: number;
  emptyCacheGateBytes: number;
  policyUpdatedAt?: string | null;
  nodes: ModelCacheNode[];
}

export interface ModelCachePolicyUpdate {
  maxBytes: number;
  minFreeBytes: number;
  runtimeReserveBytes: number;
}

export interface ModelCacheClearNodeResult {
  serverIp: string;
  k8sNodeName: string;
  cleared: string[];
  inUse: string[];
  notFound: string[];
  error?: string | null;
}

export interface ModelCacheClearResponse {
  nodes: ModelCacheClearNodeResult[];
}

export interface Result<T> {
  code: number;
  message: string;
  data?: T;
}

export async function fetchModelCacheOverview(options?: {
  skipErrorHandler?: boolean;
}) {
  return request<Result<ModelCacheOverview>>('/system/model-cache', {
    method: 'GET',
    ...(options || {}),
    timeout: 60_000,
  });
}

export async function clearModelCache(
  payload: {
    serverIps: string[];
    sha256s: string[];
    clearAll: boolean;
  },
  options?: { skipErrorHandler?: boolean },
) {
  return request<Result<ModelCacheClearResponse>>('/system/model-cache/clear', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: payload,
    ...(options || {}),
  });
}

export async function updateModelCachePolicy(
  payload: ModelCachePolicyUpdate,
  options?: { skipErrorHandler?: boolean },
) {
  return request<Result<ModelCacheOverview>>('/system/model-cache/policy', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: payload,
    ...(options || {}),
  });
}
