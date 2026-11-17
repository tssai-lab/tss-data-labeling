import type { Label } from './labeling';
export function initialClassification(
  draft: { revision: number; result: { labelId?: string } },
  reference: { result: { labelId?: string } } | null,
  labels: ReadonlyArray<Label>,
): { labelId: string; referenceShown: boolean };
export function classificationFromChoices(
  raw: unknown,
  labels: ReadonlyArray<Label>,
): { labelId: string };
