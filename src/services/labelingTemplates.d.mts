export type TaskBinding = {
  templateId?: string;
  templateVersion?: string;
  taskType?: string;
  editorKey?: string;
  inputFormat?: string;
  resultFormat?: string | null;
  inputSchemaId?: string;
  resultSchemaId?: string;
};
export type TaskTemplate = {
  id: string;
  version: string;
  name: string;
  taskType: string;
  inputFormat: string;
  resultFormat: string;
  editorKey: string;
  enabled: boolean;
  inputSchemaId?: string;
  resultSchemaId?: string;
};
export const taskTemplates: readonly TaskTemplate[];
export function resolveTaskTemplate(
  binding?: TaskBinding,
  options?: { preview?: boolean },
): TaskTemplate;
export function assertClassificationWork(
  work: TaskBinding & { specification?: TaskBinding; items?: TaskBinding[] },
): TaskTemplate;
