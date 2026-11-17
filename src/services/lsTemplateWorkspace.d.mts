export type TemplateDraft = {
  formatVersion: number;
  scope: string;
  templateId: string;
  templateVersion: string;
  inputSchema: string;
  config: string;
  instructions: string;
  savedAt: string;
};
export function saveTemplateDraft(
  storage: Storage,
  scope: string,
  draft: { templateId: string; templateVersion?: string; config: string; instructions: string },
): TemplateDraft;
export function readTemplateDraft(
  storage: Storage,
  scope: string,
): TemplateDraft | null;
