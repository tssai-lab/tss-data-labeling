export type TrainingAssetInput = {
  acceptedSpecIds?: string[];
  taskTypes?: string[];
};

export function isSpecDrivenInput(input?: TrainingAssetInput): boolean;
export function filterModelCandidates<
  T extends {
    status?: string;
    type?: string;
    artifactSpecId?: string;
  },
>(items: T[], input?: TrainingAssetInput): T[];
export function filterDatasetCandidates<
  T extends {
    versionId?: string;
    versionStatus?: string;
    type?: string;
    artifactSpecId?: string;
  },
>(items: T[], input?: TrainingAssetInput): T[];
