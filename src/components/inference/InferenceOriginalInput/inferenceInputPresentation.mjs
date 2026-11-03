const IMAGE_EXTENSIONS = new Set([
  'bmp',
  'gif',
  'jpeg',
  'jpg',
  'png',
  'webp',
]);

const TEXT_EXTENSIONS = new Set([
  'csv',
  'json',
  'jsonl',
  'md',
  'txt',
  'tsv',
  'xml',
  'yaml',
  'yml',
]);

export function fileNameFromObjectName(objectName) {
  if (!objectName) return '';
  const normalized = String(objectName).replace(/\\/g, '/').replace(/\/+$/, '');
  const parts = normalized.split('/');
  return parts[parts.length - 1] || normalized;
}

export function previewKindFromObjectName(objectName) {
  const fileName = fileNameFromObjectName(objectName);
  const dot = fileName.lastIndexOf('.');
  if (dot < 0 || dot === fileName.length - 1) return 'unsupported';
  const extension = fileName.slice(dot + 1).toLowerCase();
  if (IMAGE_EXTENSIONS.has(extension)) return 'image';
  if (TEXT_EXTENSIONS.has(extension)) return 'text';
  return 'unsupported';
}

export function buildInferenceInputPresentation(input) {
  const mode = input?.inputMode;
  if (mode === 'DATASET_VERSION') {
    return {
      kind: 'dataset',
      identifier: input?.datasetVersionId || '',
      displayName: input?.datasetDisplayName || input?.datasetVersionId || '数据集版本不可用',
    };
  }

  const objectName = input?.inputObjectName || '';
  return {
    kind: 'object',
    identifier: objectName,
    displayName: fileNameFromObjectName(objectName) || '原始输入不可用',
    previewKind: previewKindFromObjectName(objectName),
  };
}
