import type {
  EnergyDraft,
  EnergyInput,
  EnergyResult,
} from './labelingEnergy.mjs';
export type EnergyCandidate = {
  sampleId: string;
  resultId: string;
  sourceKind: string;
  sourceActor: string;
  confidence: null;
  specVersionId: string;
  inputHash: string;
  result: EnergyResult;
  parentResultId?: string;
  reason?: string;
};
export type EnergyFinal = { finalSnapshotId: string; rows: EnergyCandidate[] };
export type EnergyDemoState = {
  version: string;
  drafts: Record<string, EnergyDraft>;
  submitted: boolean;
  results: EnergyCandidate[];
  current: Record<string, string>;
  reviewRevisions: Record<string, number>;
  accepted: Record<string, string>;
  finals: EnergyFinal[];
  sequence: number;
};
export type EnergySave = {
  baseRevision: number;
  explicitDone: boolean;
  resultFormat: 'annotation-record-v1';
  result: EnergyResult;
};
export type EnergyWorkspace = {
  inputs: EnergyInput[];
  draft(id: string): EnergyDraft;
  snapshot(): EnergyDemoState;
  serialize(): string;
  save(id: string, hash: string, body: EnergySave): EnergyDraft;
  submit(items: { sampleId: string; revision: number }[]): EnergyDemoState;
  revise(
    id: string,
    base: number,
    result: EnergyResult,
    reason: string,
  ): EnergyCandidate;
  review(
    items: { sampleId: string; resultId: string; reviewRevision: number }[],
  ): EnergyDemoState;
  finalize(): EnergyFinal;
  export(id: string): Promise<Record<string, string>>;
};
export function contentHash(value: unknown): Promise<string>;
export function createEnergyDemo(options?: {
  enabled?: boolean;
  production?: boolean;
  serialized?: string;
}): Promise<EnergyWorkspace>;
