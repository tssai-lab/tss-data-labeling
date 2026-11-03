import type { InferenceResourceProfile } from '@/services/inference';

export function listUsableCpuInferenceProfiles(
  profiles?: InferenceResourceProfile[],
): InferenceResourceProfile[];

export function listUsableInferenceProfiles(
  profiles?: InferenceResourceProfile[],
): InferenceResourceProfile[];

export function defaultInferenceResourceProfileId(
  profiles?: InferenceResourceProfile[],
): string | undefined;
