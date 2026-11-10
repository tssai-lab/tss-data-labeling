export type EnergyInput = {
  sampleId: string;
  inputFormat: string;
  schemaId: string;
  inputHash: string;
  payload: {
    stationKey: string;
    stationLevel?: string;
    totalBasis?: string;
    dateLocal: string;
    timezone: string;
    dailyKwh: number | null;
    qualityReasons?: string[];
    series: {
      name: string;
      channel: string;
      unit: string;
      start: string;
      periodSeconds: number;
      values: (number | null)[];
    };
  };
  context: {
    status: string;
    availableHistoryDays: number;
    contextVersion?: string;
  };
};
export type EnergyValue = {
  primary: string;
  secondary: string[];
  points: { id: string; hour: number; labelId: string }[];
  intervals: {
    id: string;
    startHour: number;
    endHour: number;
    labelId: string;
  }[];
  notes: string;
  wholeDayReason: string;
  certainty: string;
};
export type EnergyAnnotation = {
  id: string;
  from_name: string;
  to_name: string;
  type: string;
  value: {
    labelIds?: string[];
    text?: string;
    timestamp?: string;
    start?: string;
    end?: string;
    channel?: string;
  };
};
export type EnergyResult = {
  schemaVersion: string;
  annotations: EnergyAnnotation[];
};
export type EnergyDraft = {
  revision: number;
  explicitDone: boolean;
  resultFormat: 'annotation-record-v1';
  result: EnergyResult;
};
export const energyLabels: readonly string[];
export function emptyEnergyValue(): EnergyValue;
export function validateEnergyInput(input: EnergyInput): EnergyInput;
export function hourTimestamp(input: EnergyInput, hour: number): string;
export function encodeEnergyResult(
  value: EnergyValue,
  input: EnergyInput,
): EnergyResult;
export function decodeEnergyResult(
  result: EnergyResult,
  input: EnergyInput,
): EnergyValue;
export function validateEnergyResult(
  result: EnergyResult,
  input: EnergyInput,
  complete?: boolean,
): boolean;
export function energySummary(input: EnergyInput): {
  missing: number;
  knownSum: number;
  completeSum: number | null;
  difference: number | null;
};
