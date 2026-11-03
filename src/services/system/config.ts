/**
 * 系统配置模块 - Services 层
 * 对齐 GET/POST /api/system/config/*
 * data: trainingCodeReviewMode, logMaxSize, userLogStorageLimitMb, updatedAt
 */
import { request } from '@umijs/max';
import { SYSTEM_API_CONFIG } from '@/constants/system';
import {
  automaticReviewSelectedFromMode,
  normalizeTrainingCodeReviewMode,
  reviewEnabledFromMode,
  reviewModeFromSwitches,
} from './trainingCodeReviewPolicy';
import type { TrainingCodeReviewMode } from './trainingCodeReviewPolicy';

export type { TrainingCodeReviewMode } from './trainingCodeReviewPolicy';

export const DEFAULT_USER_LOG_LIMIT_MB = 50;
export const MIN_USER_LOG_LIMIT_MB = 1;
export const MAX_USER_LOG_LIMIT_MB = 10240;
export const MIN_POD_QUOTA = 1;
export const MAX_POD_QUOTA = 50;
export const MIN_JOB_QUOTA = 1;
export const MAX_JOB_QUOTA = 200;
export const MIN_JOB_TTL_SECONDS = 60;
export const MAX_JOB_TTL_SECONDS = 3600;

export interface SystemConfig {
  /**
   * 审核总开关：false=DIRECT_PASS，true=STANDARD_REVIEW 或 MANUAL_ONLY
   */
  enableTrainingCodeAdminReview?: boolean;
  /** 审核总开关开启时，是否允许风险策略自动批准或拒绝 */
  enableTrainingCodeAutoReview?: boolean;
  /** 后端字段 */
  trainingCodeReviewMode?: TrainingCodeReviewMode;
  /** 每用户日志上限 MB（写接口用 logMaxSize） */
  logMaxSize?: number;
  /** 与 logMaxSize 等价，读响应两者都会返回 */
  userLogStorageLimitMb?: number;
  updatedAt?: string;
}

export interface KubernetesResourcePolicy {
  podQuota: number;
  jobQuota: number;
  jobTtlSecondsAfterFinished: number;
  usedPods: number;
  usedJobs: number;
  clusterName: string;
  namespace: string;
  updatedAt?: string;
}

export function reviewModeFromAdminFlag(
  enableAdminReview?: boolean,
): TrainingCodeReviewMode {
  return reviewModeFromSwitches(enableAdminReview !== false, true);
}

export function adminFlagFromReviewMode(mode?: string | null): boolean {
  return reviewEnabledFromMode(mode);
}

function resolveUserLogLimitMb(raw?: Partial<SystemConfig> | null): number {
  const value = raw?.userLogStorageLimitMb ?? raw?.logMaxSize;
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.min(
      MAX_USER_LOG_LIMIT_MB,
      Math.max(MIN_USER_LOG_LIMIT_MB, Math.floor(value)),
    );
  }
  return DEFAULT_USER_LOG_LIMIT_MB;
}

export function normalizeSystemConfig(
  raw?: Partial<SystemConfig> | null,
): SystemConfig {
  const mode = raw?.trainingCodeReviewMode
    ? normalizeTrainingCodeReviewMode(raw.trainingCodeReviewMode)
    : reviewModeFromSwitches(
        raw?.enableTrainingCodeAdminReview !== false,
        raw?.enableTrainingCodeAutoReview !== false,
      );
  const limitMb = resolveUserLogLimitMb(raw);
  return {
    trainingCodeReviewMode: mode,
    enableTrainingCodeAdminReview: adminFlagFromReviewMode(mode),
    enableTrainingCodeAutoReview: automaticReviewSelectedFromMode(mode),
    logMaxSize: limitMb,
    userLogStorageLimitMb: limitMb,
    updatedAt: raw?.updatedAt,
  };
}

/** 获取系统配置 GET /api/system/config/get */
export async function fetchSystemConfig(options?: { [key: string]: any }) {
  const res = await request<{
    code: number;
    message: string;
    data: SystemConfig;
  }>(SYSTEM_API_CONFIG.ENDPOINTS.CONFIG_GET, {
    method: 'GET',
    ...(options || {}),
  });
  if (res?.data) {
    return { ...res, data: normalizeSystemConfig(res.data) };
  }
  return res;
}

/** 更新系统配置 POST /api/system/config/update */
export async function updateSystemConfig(
  params: SystemConfig,
  options?: { [key: string]: any },
) {
  const trainingCodeReviewMode =
    params.trainingCodeReviewMode ||
    reviewModeFromSwitches(
      params.enableTrainingCodeAdminReview !== false,
      params.enableTrainingCodeAutoReview !== false,
    );
  const logMaxSize = resolveUserLogLimitMb(params);
  const res = await request<{
    code: number;
    message: string;
    data?: SystemConfig;
  }>(SYSTEM_API_CONFIG.ENDPOINTS.CONFIG_UPDATE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: {
      trainingCodeReviewMode,
      logMaxSize,
    },
    ...(options || {}),
  });
  if (res?.data) {
    return { ...res, data: normalizeSystemConfig(res.data) };
  }
  return res;
}

/** 获取 Main 当前集群的真实资源策略；失败时不得回落到浏览器本地值。 */
export async function fetchKubernetesResourcePolicy(options?: {
  [key: string]: any;
}) {
  return request<{
    code: number;
    message: string;
    data?: KubernetesResourcePolicy;
  }>(SYSTEM_API_CONFIG.ENDPOINTS.RESOURCE_POLICY_GET, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 更新只影响 ResourceQuota 和之后新建 Job 的 TTL。 */
export async function updateKubernetesResourcePolicy(
  params: Pick<
    KubernetesResourcePolicy,
    'podQuota' | 'jobQuota' | 'jobTtlSecondsAfterFinished'
  >,
  options?: { [key: string]: any },
) {
  return request<{
    code: number;
    message: string;
    data?: KubernetesResourcePolicy;
  }>(SYSTEM_API_CONFIG.ENDPOINTS.RESOURCE_POLICY_UPDATE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: params,
    ...(options || {}),
  });
}
