import { labelingJson } from './labeling';
import type { TaskTemplate } from './labelingTemplates.mjs';
// Documented endpoints only; current backend has not implemented them.
export const taskInputBackendReady = false;
export const taskInputs = {
  templates: (signal?: AbortSignal) =>
    labelingJson<TaskTemplate[]>('/task-templates', { signal }),
  assignment: <T>(
    assignmentId: string,
    sampleId: string,
    signal?: AbortSignal,
  ) =>
    labelingJson<T>(
      `/assignments/${encodeURIComponent(assignmentId)}/samples/${encodeURIComponent(sampleId)}/data`,
      { signal },
    ),
  owner: <T>(projectId: string, sampleId: string, signal?: AbortSignal) =>
    labelingJson<T>(
      `/projects/${encodeURIComponent(projectId)}/samples/${encodeURIComponent(sampleId)}/data`,
      { signal },
    ),
};
