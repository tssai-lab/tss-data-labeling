import type { EnergyInput } from './labelingEnergy.mjs';

export type LsProjectLabel = { id: string; name: string };
export function createPlatformTimeseriesTask(input: unknown): {
  id: string;
  data: Record<string, unknown>;
  platformTimeseries?: unknown;
  annotations: unknown[];
  predictions: unknown[];
};
export const lsTimeseriesTemplate: Readonly<{
  id: string;
  version: string;
  upstreamVersion: string;
  status: 'experimental';
  objectName: string;
}>;
export function createLsTimeseriesConfig(labels: LsProjectLabel[]): string;
export function createLsTimeseriesTask(input: EnergyInput): {
  id: string;
  data: {
    series: { hour: number[]; value_kwh: (number | null)[] };
    station_key: string;
    date_local: string;
    timezone: string;
    daily_kwh: number | null;
    input_hash: string;
  };
  annotations: unknown[];
  predictions: unknown[];
};
