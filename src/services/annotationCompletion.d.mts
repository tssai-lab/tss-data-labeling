export type CompletionRules = {
  allowEmpty: boolean;
  required: { name: string; type: string }[];
};
export const defaultCompletion: CompletionRules;
export function compileCompletion(
  config: string,
  allowEmpty?: boolean,
): CompletionRules;
export function validateCompletion(
  value: unknown[],
  rules?: CompletionRules,
  context?: { emptyReason?: string; input?: unknown },
): boolean;
