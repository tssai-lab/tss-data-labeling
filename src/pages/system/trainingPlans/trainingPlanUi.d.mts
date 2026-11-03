import type { TrainingPlanPreview } from '@/services/system/trainingPlans';

export const MAX_TRAINING_PLAN_YAML_BYTES: number;

export function validateTrainingPlanYamlFile(
  file?: Pick<File, 'name' | 'size'>,
): string | undefined;

export function canPublishTrainingPlan(
  file: File | undefined,
  preview: TrainingPlanPreview | undefined,
): boolean;

export function getTrainingPlanRequestError(
  error: unknown,
  fallback?: string,
): string;
