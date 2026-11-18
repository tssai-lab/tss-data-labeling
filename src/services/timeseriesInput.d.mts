export type TimeseriesSample = { id: string; name: string; input: unknown; source?: unknown };
export type TimeseriesInput = {
  inputFormat: 'timeseries-input-v1';
  schemaId: 'timeseries-input@1.0.0';
  sampleId: string;
  inputHash: string;
  payload: {
    time: { kind: 'number' | 'timestamp'; unit: string; values: number[] };
    channels: {
      column: string;
      label: string;
      unit: string;
      values: (number | null)[];
    }[];
  };
};
export const TIMESERIES_INPUT_FORMAT: 'timeseries-input-v1';
export function validateTimeseriesInput(input: unknown): TimeseriesInput;
export function createGeneralTimeseriesTask(input: unknown): {
  id: string;
  data: Record<string, unknown>;
  platformTimeseries: {
    kind: string;
    unit: string;
    timeColumn: string;
    channels: { column: string; label: string; unit: string }[];
  };
  annotations: unknown[];
  predictions: unknown[];
};
export function createGeneralTimeseriesConfig(input: unknown): string;
export function timeseriesHash(value: string): Promise<string>;
export function importTimeseriesCsv(
  text: string,
  options: {
    timeColumn: string;
    channelColumns: string[];
    groupColumn?: string;
    kind?: 'number' | 'timestamp';
    timeUnit?: string;
    units?: Record<string, string>;
    filename?: string;
  },
): Promise<{ samples: TimeseriesSample[]; mapping: Record<string, string> }>;
