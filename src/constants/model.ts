/** 后端支持的模型目录类型（上传、编辑、列表筛选） */
export const MODEL_BACKEND_TYPE_OPTIONS = [
  { value: 'CV', label: 'CV' },
  { value: 'NLP', label: 'NLP' },
  { value: 'POINT_CLOUD', label: '点云' },
  { value: 'ROBOT', label: '机器人' },
  { value: 'OTHER', label: '其他（暂未归类）' },
] as const;

export type ModelBackendType =
  (typeof MODEL_BACKEND_TYPE_OPTIONS)[number]['value'];

/** 列表/详情展示。 */
export const MODEL_TYPE_OPTIONS = [...MODEL_BACKEND_TYPE_OPTIONS] as const;

export type ModelTaskType = (typeof MODEL_TYPE_OPTIONS)[number]['value'];

/** 列表筛选：仅后端支持的 type 参数 */
export const MODEL_TYPE_FILTER_VALUE_ENUM: Record<string, { text: string }> = {
  CV: { text: 'CV' },
  NLP: { text: 'NLP' },
  POINT_CLOUD: { text: '点云' },
  ROBOT: { text: 'ROBOT' },
  OTHER: { text: '其他' },
};

export const MODEL_TYPE_VALUE_ENUM: Record<string, { text: string }> = {
  ...MODEL_TYPE_FILTER_VALUE_ENUM,
};

export const MODEL_TYPE_COLORS: Record<string, string> = {
  CV: 'blue',
  NLP: 'green',
  POINT_CLOUD: 'purple',
  ROBOT: 'default',
  OTHER: 'default',
};
