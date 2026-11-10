export type QualityPlan = {
  version: string;
  seed: string;
  population: string[];
  gold: string[];
  inspection: string[];
  double: string[];
  goldConfirmed: boolean;
};
export type QualityAnswer = {
  actorId: string;
  labelId: string;
  sourceKind: string;
  referenceResultId?: string;
};
export function fixedQualityPlan(
  ids: string[],
  options: {
    seed: string;
    goldCount: number;
    inspectionRate: number;
    doubleRate: number;
  },
): QualityPlan;
export function cohenKappa(
  pairs: {
    sampleId: string;
    specVersionId: string;
    left: QualityAnswer;
    right: QualityAnswer;
  }[],
): {
  pairCount: number;
  excludedCount: number;
  observedAgreement: number | null;
  expectedAgreement: number | null;
  kappa: number | null;
  reason: string | null;
};
