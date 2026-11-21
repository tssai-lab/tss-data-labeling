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

export function createTaskPluginRegistry(definitions: readonly TaskTemplate[]): { templates: readonly TaskTemplate[]; resolve: typeof resolveTaskTemplate };
export type RegisteredTaskPlugin = TaskBinding & {
  templateId: string;
  templateVersion: string;
  taskType: string;
  name?: string;
  enabled: boolean;
  defaultVersion?: boolean;
  creationAvailable?: boolean;
  capabilities?: { importer: boolean; inputReader: boolean; resultValidator: boolean; completion: boolean; exporter: boolean };
};
export type AvailableTaskPlugin = RegisteredTaskPlugin & { key: string; name: string; installed: boolean; unavailableReason: string };
export function availableTaskPlugins(catalogue: RegisteredTaskPlugin[]): AvailableTaskPlugin[];
export type ProjectTaskType = AvailableTaskPlugin & { description: string };
export function availableProjectTypes(catalogue: RegisteredTaskPlugin[]): ProjectTaskType[];
