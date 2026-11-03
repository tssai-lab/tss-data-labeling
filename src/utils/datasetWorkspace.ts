export const WORKSPACE_EDITABLE_DATASET_TYPES = [
  'CV',
  'NLP',
  'POINT_CLOUD',
  'MULTIMODAL',
  'ROBOT',
] as const;

export type WorkspaceEditableDatasetType =
  (typeof WORKSPACE_EDITABLE_DATASET_TYPES)[number];

export function supportsDatasetWorkspaceEdit(
  type?: string,
): type is WorkspaceEditableDatasetType {
  return WORKSPACE_EDITABLE_DATASET_TYPES.includes(
    type as WorkspaceEditableDatasetType,
  );
}

/** ZIP 包数据集才可创建维护工作区（单文件旧版本需先重新上传为 zip） */
export function isZipBackedDatasetVersion(version?: {
  fileName?: string;
  storagePath?: string;
}): boolean {
  const fileName = version?.fileName?.toLowerCase() ?? '';
  const storagePath = version?.storagePath?.toLowerCase() ?? '';
  return fileName.endsWith('.zip') || storagePath.endsWith('.zip');
}
