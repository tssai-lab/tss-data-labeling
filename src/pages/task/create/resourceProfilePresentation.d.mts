import type { TrainingPlan } from '@/services/trainingPlans';

export type TrainingResourceProfile =
  TrainingPlan['runtimes'][number]['resourceProfiles'][number] & {
    runtimeId: string;
    deviceType: 'CPU' | 'NVIDIA_GPU';
  };

export function listTrainingResourceProfiles(
  plan?: TrainingPlan,
): TrainingResourceProfile[];

export function firstTrainingResourceProfileId(
  plan?: TrainingPlan,
): string | undefined;

export function isTrainingResourceProfileIdAllowed(
  profiles: TrainingResourceProfile[],
  profileId: unknown,
): boolean;

export function formatTrainingResourceProfileLabel(
  profile?: TrainingResourceProfile,
): string;
