/**
 * 训练代码审核相关配置读取。
 *
 * 对接后端 `trainingCodeReviewMode`：
 * - DIRECT_PASS：自动通过（不跑风险扫描/不等人工）
 * - STANDARD_REVIEW：自动风险审核 + 人工兜底
 * - MANUAL_ONLY：基础校验后全部进入人工待审
 *
 * 系统管理页开关「训练代码管理员审核」：
 * - 审核关闭 → DIRECT_PASS
 * - 审核开启、自动审核开启 → STANDARD_REVIEW
 * - 审核开启、自动审核关闭 → MANUAL_ONLY
 *
 * 注意：GET/POST /api/system/config/* 仅管理员可用；普通用户会 403。
 * 因此不能把「同步失败 / 未同步」当成「审核已关闭」。
 */
import type { TrainingCodeReviewMode } from '@/services/system/trainingCodeReviewPolicy';
import {
  automaticDecisionsEnabledFromMode,
  automaticReviewSelectedFromMode,
  normalizeTrainingCodeReviewMode,
  reviewEnabledFromMode,
  reviewModeFromSwitches,
} from '@/services/system/trainingCodeReviewPolicy';
import { STORAGE_KEYS, storage } from '@/utils/storage';

const DEFAULT_REVIEW_MODE: TrainingCodeReviewMode = 'STANDARD_REVIEW';

/** @deprecated 请改用 isTrainingCodeAutoApproveEnabled()；保留仅为兼容旧引用 */
export const TRAINING_CODE_AUTO_APPROVE = false;

export type TrainingCodeReviewLocalConfig = {
  /** true = STANDARD_REVIEW/MANUAL_ONLY；false = DIRECT_PASS */
  enableTrainingCodeAdminReview: boolean;
  enableTrainingCodeAutoReview?: boolean;
  trainingCodeReviewMode?: TrainingCodeReviewMode;
  /** 是否已从服务端成功同步（普通用户通常为 false） */
  syncedFromServer?: boolean;
};

export function getTrainingCodeReviewLocalConfig(): TrainingCodeReviewLocalConfig {
  const cached = storage.get<Partial<TrainingCodeReviewLocalConfig>>(
    STORAGE_KEYS.TRAINING_CODE_REVIEW_CONFIG,
  );
  const mode = cached?.trainingCodeReviewMode
    ? normalizeTrainingCodeReviewMode(cached.trainingCodeReviewMode)
    : reviewModeFromSwitches(
        cached?.enableTrainingCodeAdminReview !== false,
        cached?.enableTrainingCodeAutoReview !== false,
      );
  return {
    enableTrainingCodeAdminReview: reviewEnabledFromMode(mode),
    enableTrainingCodeAutoReview: automaticReviewSelectedFromMode(mode),
    trainingCodeReviewMode: mode || DEFAULT_REVIEW_MODE,
    syncedFromServer: cached?.syncedFromServer === true,
  };
}

export function setTrainingCodeReviewLocalConfig(
  config: Partial<TrainingCodeReviewLocalConfig>,
) {
  const prev = getTrainingCodeReviewLocalConfig();
  let enable = config.enableTrainingCodeAdminReview;
  let enableAuto = config.enableTrainingCodeAutoReview;
  let mode = config.trainingCodeReviewMode;
  if (mode) {
    mode = normalizeTrainingCodeReviewMode(mode);
    enable = reviewEnabledFromMode(mode);
    enableAuto = automaticReviewSelectedFromMode(mode);
  } else if (enable != null || enableAuto != null) {
    enable = enable ?? prev.enableTrainingCodeAdminReview;
    enableAuto = enableAuto ?? prev.enableTrainingCodeAutoReview ?? true;
    mode = reviewModeFromSwitches(enable, enableAuto);
  } else {
    enable = prev.enableTrainingCodeAdminReview;
    enableAuto = prev.enableTrainingCodeAutoReview;
    mode = prev.trainingCodeReviewMode;
  }
  const next: TrainingCodeReviewLocalConfig = {
    enableTrainingCodeAdminReview: !!enable,
    enableTrainingCodeAutoReview: enableAuto !== false,
    trainingCodeReviewMode: mode || DEFAULT_REVIEW_MODE,
    syncedFromServer:
      config.syncedFromServer != null
        ? !!config.syncedFromServer
        : prev.syncedFromServer === true,
  };
  storage.set(STORAGE_KEYS.TRAINING_CODE_REVIEW_CONFIG, next);
  return next;
}

/** 是否已从服务端成功读到审核开关（管理员保存/拉取后为 true） */
export function hasSyncedTrainingCodeReviewConfig(): boolean {
  return getTrainingCodeReviewLocalConfig().syncedFromServer === true;
}

/**
 * 是否启用审核总开关（STANDARD_REVIEW 或 MANUAL_ONLY）。
 * 未同步成功前不假定已关闭，避免普通用户 403 后被误导。
 */
export function isTrainingCodeAdminReviewEnabled(): boolean {
  const cfg = getTrainingCodeReviewLocalConfig();
  if (!cfg.syncedFromServer) return true;
  return cfg.enableTrainingCodeAdminReview;
}

/** 当前服务端是否允许风险策略自动批准或拒绝。 */
export function isTrainingCodeAutomaticReviewEnabled(): boolean {
  const cfg = getTrainingCodeReviewLocalConfig();
  if (!cfg.syncedFromServer) return false;
  return automaticDecisionsEnabledFromMode(cfg.trainingCodeReviewMode);
}

/**
 * 是否自动审核通过（DIRECT_PASS）。
 * 未同步成功前返回 false，避免前端误走「自动审批」旁路。
 */
export function isTrainingCodeAutoApproveEnabled(): boolean {
  const cfg = getTrainingCodeReviewLocalConfig();
  if (!cfg.syncedFromServer) return false;
  return cfg.trainingCodeReviewMode === 'DIRECT_PASS';
}

/**
 * 从系统配置接口同步训练代码审核模式到本机缓存。
 * 接口失败（含普通用户 403）时保留已有缓存，且不得写成「已关闭」。
 */
export async function syncTrainingCodeReviewConfigFromServer(options?: {
  [key: string]: any;
}): Promise<TrainingCodeReviewLocalConfig> {
  try {
    const { fetchSystemConfig } = await import('@/services/system/config');
    const res = await fetchSystemConfig({
      skipErrorHandler: true,
      ...(options || {}),
    });
    if (res?.code === 200 && res.data) {
      return setTrainingCodeReviewLocalConfig({
        enableTrainingCodeAdminReview:
          res.data.enableTrainingCodeAdminReview ?? true,
        enableTrainingCodeAutoReview:
          res.data.enableTrainingCodeAutoReview ?? true,
        trainingCodeReviewMode: res.data.trainingCodeReviewMode,
        syncedFromServer: true,
      });
    }
  } catch {
    // 保留本地缓存；普通用户 403 时保持 syncedFromServer 原值
  }
  return getTrainingCodeReviewLocalConfig();
}
