export type TrainingCodeReviewMode =
  | 'DIRECT_PASS'
  | 'STANDARD_REVIEW'
  | 'MANUAL_ONLY';

export function normalizeTrainingCodeReviewMode(
  mode?: string | null,
): TrainingCodeReviewMode {
  const normalized = String(mode || '').trim().toUpperCase();
  if (
    normalized === 'DIRECT_PASS' ||
    normalized === 'STANDARD_REVIEW' ||
    normalized === 'MANUAL_ONLY'
  ) {
    return normalized;
  }
  // 未知值不能默认允许系统自动批准，按“仅人工审核”保守处理。
  return 'MANUAL_ONLY';
}

export function reviewModeFromSwitches(
  enableReview: boolean,
  enableAutomaticReview: boolean,
): TrainingCodeReviewMode {
  if (!enableReview) return 'DIRECT_PASS';
  return enableAutomaticReview ? 'STANDARD_REVIEW' : 'MANUAL_ONLY';
}

export function reviewEnabledFromMode(mode?: string | null): boolean {
  return normalizeTrainingCodeReviewMode(mode) !== 'DIRECT_PASS';
}

/**
 * DIRECT_PASS keeps the recommended automatic-review selection for the next
 * time the master review switch is enabled. It is not effective while review
 * itself is disabled.
 */
export function automaticReviewSelectedFromMode(
  mode?: string | null,
): boolean {
  return normalizeTrainingCodeReviewMode(mode) !== 'MANUAL_ONLY';
}

export function automaticDecisionsEnabledFromMode(
  mode?: string | null,
): boolean {
  return normalizeTrainingCodeReviewMode(mode) === 'STANDARD_REVIEW';
}
