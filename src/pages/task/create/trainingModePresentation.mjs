const TRAINING_MODE_LABELS = {
  FULL_FINETUNE: '完整微调',
  FROM_SCRATCH: '从零训练',
  FEATURE_EXTRACTION: '特征提取',
};

export function formatTrainingMode(mode) {
  const value = typeof mode === 'string' ? mode.trim() : '';
  return TRAINING_MODE_LABELS[value] || value || '未指定';
}

export function isSingleTrainingMode(modes) {
  return Array.isArray(modes) && modes.length === 1;
}
