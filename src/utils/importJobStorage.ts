/**
 * V2 约定：导入失败/部分成功后，列表与工作区详情不能再发现 importJobId，
 * 必须保留上传 complete / 轮询响应中的句柄，才能调用重试。
 */
const PREFIX = 'tss_dataset_import_job:';

export function importJobStorageKey(datasetId: string): string {
  return `${PREFIX}${datasetId}`;
}

export function saveImportJobId(datasetId: string, importJobId: string): void {
  if (!datasetId || !importJobId) return;
  try {
    localStorage.setItem(importJobStorageKey(datasetId), importJobId);
  } catch {
    // ignore quota / private mode
  }
}

export function loadImportJobId(datasetId: string): string | null {
  if (!datasetId) return null;
  try {
    return localStorage.getItem(importJobStorageKey(datasetId));
  } catch {
    return null;
  }
}

export function clearImportJobId(datasetId: string): void {
  if (!datasetId) return;
  try {
    localStorage.removeItem(importJobStorageKey(datasetId));
  } catch {
    // ignore
  }
}
