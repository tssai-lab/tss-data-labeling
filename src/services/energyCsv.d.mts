import type { EnergyInput } from './labelingEnergy.mjs';
export function parseCsv(text: string): string[][];
export function importEnergyCsv(
  text: string,
): Promise<{
  sourceHash: string;
  samples: {
    input: EnergyInput;
    source: {
      taskId: string;
      enterpriseKey?: string;
      sourceHash: string;
      row: number;
    };
  }[];
}>;
