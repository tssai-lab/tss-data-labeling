import type { Label, Project } from './labeling';
import type { NativeProject, NativeSpec } from './nativeLabeling';
export function normalizeWorkspaceProject(project: Project): NativeProject;
export function isLegacyProjectSpec(
  version: NativeSpec | undefined,
  taskType: string,
): boolean;
export function hasProjectAnswer(result: unknown): boolean;
export function projectPanel(pathname: string, query: URLSearchParams): string;
export function legacyProjectSpecRequest(
  version: NativeSpec | undefined,
  taskType: string,
  labels: Label[],
  instructions: string,
): Record<string, unknown>;
