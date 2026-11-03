/** 上传回执校验：空对象不是成功，显示状态不能代替上传状态。 */
import type {
  DatasetType,
  DatasetUploadCompleteResult,
  DatasetUploadProgress,
} from './dataset/types';

export class DatasetUploadReceiptError extends Error {}
const statuses = new Set([
  'UPLOADING',
  'COMPLETING',
  'COMPLETED',
  'FAILED',
  'DISCARDED',
]);
const text = (value: unknown): string | undefined =>
  typeof value === 'string' && value.trim() ? value : undefined;
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new DatasetUploadReceiptError(
      '上传回执格式无效，请查询上传状态后再操作',
    );
  const row = value as Record<string, unknown>;
  if (
    row.success === false ||
    row.errorCode ||
    (row.code != null &&
      ![0, 200, '0', '200'].includes(row.code as number | string))
  ) {
    throw new DatasetUploadReceiptError(
      text(row.errorMessage) || text(row.message) || '上传请求未成功',
    );
  }
  return row;
}
export function uploadRecord(
  raw: unknown,
  expectedId?: string,
): Record<string, unknown> {
  const root = object(raw);
  const row = 'data' in root ? object(root.data) : root;
  if (!text(row.uploadId) || (expectedId && row.uploadId !== expectedId))
    throw new DatasetUploadReceiptError(
      '上传回执缺少或错配 uploadId，请查询上传状态后再操作',
    );
  if (!statuses.has(String(row.uploadStatus ?? row.status)))
    throw new DatasetUploadReceiptError(
      '上传回执缺少有效状态，请查询上传状态后再操作',
    );
  return row;
}
function integer(
  row: Record<string, unknown>,
  field: string,
  positive = false,
): number {
  const value = row[field];
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value < (positive ? 1 : 0)
  )
    throw new DatasetUploadReceiptError(`上传回执 ${field} 无效`);
  return value;
}
export function normalizeDatasetUploadProgress(
  raw: unknown,
  expectedId?: string,
): DatasetUploadProgress {
  const row = uploadRecord(raw, expectedId);
  const totalChunks = integer(row, 'totalChunks', true);
  const indexes = row.uploadedPartIndexes;
  if (
    !Array.isArray(indexes) ||
    indexes.some((i) => !Number.isInteger(i) || i < 0 || i >= totalChunks) ||
    new Set(indexes).size !== indexes.length
  )
    throw new DatasetUploadReceiptError('上传回执分片索引无效');
  const uploadedChunks = integer(row, 'uploadedChunks');
  const fileSize = integer(row, 'fileSize', true);
  const uploadedBytes = integer(row, 'uploadedBytes');
  if (
    uploadedChunks > totalChunks ||
    uploadedChunks !== indexes.length ||
    uploadedBytes > fileSize
  )
    throw new DatasetUploadReceiptError('上传回执分片计数不一致');
  return {
    ...row,
    uploadId: String(row.uploadId),
    status: String(row.uploadStatus ?? row.status),
    fileName: text(row.fileName) ?? '',
    fileSize,
    chunkSize: integer(row, 'chunkSize', true),
    totalChunks,
    uploadedChunks,
    uploadedBytes,
    uploadedPartIndexes: indexes as number[],
    assetId: text(row.datasetId) ?? text(row.assetId),
    versionId: text(row.versionId) ?? text(row.workspaceId),
    artifactSpecId: text(row.artifactSpecId),
  };
}
export function normalizeDatasetUploadComplete(
  raw: unknown,
  expectedId: string,
): DatasetUploadCompleteResult {
  const row = uploadRecord(raw, expectedId);
  const status = String(row.uploadStatus ?? row.status);
  const assetId = text(row.datasetId) ?? text(row.assetId);
  if (status === 'COMPLETED' && !assetId)
    throw new DatasetUploadReceiptError(
      '上传完成回执缺少资产 ID，请刷新查询后再操作',
    );
  return {
    ...row,
    uploadId: expectedId,
    id: text(row.id) ?? assetId ?? '',
    assetId: assetId ?? '',
    datasetVersionId:
      text(row.datasetVersionId) ??
      text(row.versionId) ??
      text(row.workspaceId),
    name: text(row.name) ?? '',
    version: text(row.versionLabel) ?? text(row.version) ?? '',
    type: text(row.type) as DatasetType | undefined,
    fileName: text(row.fileName) ?? '',
    status,
    uploadStatus: status,
    importJobId: text(row.importJobId) ?? null,
    importStatus: text(row.importStatus) ?? null,
    artifactSpecId: text(row.artifactSpecId),
  };
}
/** 只有传输结果不确定时可只读核实；明确业务拒绝和本地回执错误不掩盖。 */
export function mayReconcileDatasetUpload(error: unknown): boolean {
  if (error instanceof DatasetUploadReceiptError) return false;
  const value = error as {
    name?: string;
    code?: string;
    errorCode?: string;
    status?: number;
    info?: { status?: number; errorCode?: string };
    response?: { status?: number; data?: { errorCode?: string } };
  };
  if (
    value?.name === 'AbortError' ||
    value?.code === 'ERR_CANCELED' ||
    value?.response?.data?.errorCode ||
    value?.info?.errorCode ||
    value?.errorCode
  )
    return false;
  const status =
    value?.response?.status ?? value?.info?.status ?? value?.status;
  return status == null || status === 408 || status >= 500;
}
