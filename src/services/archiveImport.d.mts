import type { Batch } from './labeling';
export const archivePolicy: { readonly maxArchiveBytes: number };
export type ImportAdapter = {
  create: (
    file: { filename: string; sizeBytes: number; sha256: string },
    key: string,
    signal: AbortSignal,
  ) => Promise<Batch>;
  get: (id: string, signal: AbortSignal) => Promise<Batch>;
  upload: (
    file: File,
    batch: Batch,
    signal: AbortSignal,
    progress: (fraction: number) => void,
  ) => Promise<void>;
  complete: (id: string, signal: AbortSignal) => Promise<Batch>;
  retry: (id: string, signal: AbortSignal) => Promise<Batch>;
};
export type ImportState = {
  busy?: boolean;
  stage: string;
  hashProgress: number;
  uploadProgress?: number;
  batch: Batch | null;
  error: string;
};
export function validateArchive(
  file: File,
  policy?: { maxArchiveBytes: number },
): Promise<void>;
export function imageImportAdapter(
  projectId: string,
  request: <T>(path: string, init?: RequestInit) => Promise<T>,
): ImportAdapter;
export class ArchiveImportSession {
  constructor(options: {
    scope: string;
    adapter?: ImportAdapter;
    storage?: Storage;
    policy?: { maxArchiveBytes: number };
    onChange?: (state: ImportState) => void;
  });
  state: ImportState;
  running: boolean;
  receipt: {
    batchId?: string;
    file: { filename: string; sizeBytes: number; sha256: string };
  } | null;
  run(file: File): Promise<void>;
  pause(): void;
  refresh(): Promise<void>;
  retry(): Promise<void>;
  reset(): void;
  dispose(): void;
}
