export const MODEL_UPLOAD_CATEGORY_OPTIONS = Object.freeze([
  Object.freeze({ value: 'CV', label: 'CV（视觉）' }),
  Object.freeze({ value: 'NLP', label: 'NLP（文本）' }),
  Object.freeze({ value: 'POINT_CLOUD', label: '点云' }),
  Object.freeze({ value: 'ROBOT', label: '机器人' }),
  Object.freeze({ value: 'OTHER', label: '其他（暂未归类）' }),
]);

const MODEL_UPLOAD_CATEGORIES = new Set(
  MODEL_UPLOAD_CATEGORY_OPTIONS.map((option) => option.value),
);

export function isModelUploadCategory(value) {
  return MODEL_UPLOAD_CATEGORIES.has(String(value || '').toUpperCase());
}

export function inheritedModelIdentity(detail) {
  if (!detail?.id || !detail?.name || !detail?.type) return undefined;
  return {
    id: detail.id,
    name: detail.name,
    type: detail.type,
    remark: detail.remark || '',
    artifactSpecId: detail.latestVersion?.artifactSpecId,
  };
}
