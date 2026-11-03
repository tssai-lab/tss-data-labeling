export type TrainingOutputArtifact = {
  role?: string;
  objectName?: string;
  path?: string;
};

export type TrainingOutputArtifactItem = {
  name: string;
  desc: string;
  objectName: string;
};

export function readHyperParamSummary(hyperParams?: Record<string, unknown>): {
  epochs: unknown;
  batch: unknown;
  lr: unknown;
};

export function resolveTrainingPlanDisplayName(
  trainingProfile?: string | null,
  plans?: Array<{ id?: string; displayName?: string }>,
): string;

export function buildTrainingOutputArtifactItems(trainingOutput?: {
  artifacts?: TrainingOutputArtifact[];
}): TrainingOutputArtifactItem[];
