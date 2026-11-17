import { labelingJson } from './labeling';
import type {
  EnergyDraft,
  EnergyInput,
  EnergyResult,
} from './labelingEnergy.mjs';
import {
  validateEnergyInput,
  validateEnergyResult,
} from './labelingEnergy.mjs';

export type StructuredDraft = EnergyDraft & {
  assignmentId: string;
  sampleId: string;
  inputHash: string;
};
export type StructuredSave = {
  baseRevision: number;
  explicitDone: boolean;
  resultFormat: 'annotation-record-v1';
  result: EnergyResult;
};
const id = encodeURIComponent;
export const structured = {
  draft: (assignment: string, sample: string, signal?: AbortSignal) =>
    labelingJson<StructuredDraft>(
      `/assignments/${id(assignment)}/drafts/${id(sample)}`,
      { signal },
    ),
  save: (
    assignment: string,
    sample: string,
    inputHash: string,
    body: StructuredSave,
  ) =>
    labelingJson<StructuredDraft>(
      `/assignments/${id(assignment)}/drafts/${id(sample)}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...body, inputHash }),
      },
    ),
};
export function verifyStructuredDraft(
  draft: StructuredDraft,
  assignment: string,
  input: EnergyInput,
  labels: readonly string[],
) {
  validateEnergyInput(input);
  if (
    draft.assignmentId !== assignment ||
    draft.sampleId !== input.sampleId ||
    draft.inputHash !== input.inputHash ||
    !Number.isInteger(draft.revision) ||
    draft.revision < 0 ||
    draft.resultFormat !== 'annotation-record-v1'
  )
    throw new Error('草稿归属、输入摘要或结果格式不一致，请重新读取');
  validateEnergyResult(draft.result, input, draft.explicitDone, labels);
  return draft;
}
export function resultLabel(
  result:
    | {
        labelId?: string;
        annotations?: { from_name: string; value: { labelIds?: string[] } }[];
      }
    | null
    | undefined,
): string | undefined {
  return (
    result?.labelId ||
    result?.annotations?.find((a) => a.from_name === 'primary_label')?.value
      .labelIds?.[0]
  );
}
