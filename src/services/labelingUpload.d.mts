import type { Batch } from './labeling';
export function hashFile(
  file: Blob,
  signal: AbortSignal,
  onProgress?: (fraction: number) => void,
): Promise<string>;
export function uploadChunks(
  file: Blob,
  batch: Pick<
    Batch,
    'batchId' | 'receivedBytes' | 'sizeBytes' | 'chunkSizeBytes'
  >,
  request: (
    path: string,
    init: RequestInit,
  ) => Promise<{ receivedBytes: number }>,
  signal: AbortSignal,
  onProgress?: (fraction: number) => void,
): Promise<void>;
