import type { EnergyInput } from './labelingEnergy.mjs';
export const timeseriesInterfaces: readonly string[];
export const draftButtons: Readonly<
  Record<
    string,
    { name: string; title: string; ariaLabel: string; draftOnly: boolean }[]
  >
>;
export function saveLocalLsDraft(
  storage: Storage,
  input: EnergyInput,
  config: string,
  result: unknown[],
): void;
export function readLocalLsDraft(
  storage: Storage,
  input: EnergyInput,
  config: string,
): unknown[];
