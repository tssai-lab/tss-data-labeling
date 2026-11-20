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
export function classificationLabelConfig(labels: ReadonlyArray<Label>): string;
export function classificationToChoices(labelId: string, labels: ReadonlyArray<Label>): unknown[];
export function classificationSaveInput(raw: unknown, labels: ReadonlyArray<Label>, baseRevision: number, done: boolean): {
  baseRevision: number;
  explicitDone: boolean;
  resultFormat: 'image-classification-v1';
  result: { labelId?: string };
};
