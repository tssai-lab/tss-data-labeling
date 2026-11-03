/** 推理结果可视化 view 枚举（与协议对齐；未实现的 view 走占位） */
export const INFERENCE_VIEWS = [
  'image_detection',
  'image_classification',
  'image_segmentation',
  'image_keypoints',
  'image_ocr',
  'image_pair',
  'table_classification',
  'table_regression',
  'text_classification',
  'text_spans',
  'text_generation',
  'text_retrieval',
  'pointcloud',
  'multimodal',
  'metrics',
  'unknown',
] as const;

export type InferenceView = (typeof INFERENCE_VIEWS)[number];

export const INFERENCE_VIEW_META: Record<
  InferenceView,
  { label: string; color: string; implemented: boolean }
> = {
  image_detection: { label: 'CV 检测', color: 'purple', implemented: true },
  image_classification: {
    label: 'CV 分类',
    color: 'blue',
    implemented: true,
  },
  image_segmentation: {
    label: 'CV 分割',
    color: 'geekblue',
    implemented: false,
  },
  image_keypoints: { label: 'CV 关键点', color: 'cyan', implemented: false },
  image_ocr: { label: 'OCR', color: 'magenta', implemented: false },
  image_pair: { label: '图像对比', color: 'gold', implemented: false },
  table_classification: {
    label: '表格 / 融合分类',
    color: 'cyan',
    implemented: true,
  },
  table_regression: { label: '表格回归', color: 'lime', implemented: false },
  text_classification: {
    label: '文本分类',
    color: 'processing',
    implemented: true,
  },
  text_spans: { label: 'NER / 抽取', color: 'processing', implemented: false },
  text_generation: {
    label: '文本生成',
    color: 'processing',
    implemented: false,
  },
  text_retrieval: {
    label: '文本检索',
    color: 'processing',
    implemented: false,
  },
  pointcloud: { label: '3D 点云', color: 'orange', implemented: false },
  multimodal: { label: '多模态', color: 'volcano', implemented: false },
  metrics: { label: '仅指标', color: 'default', implemented: false },
  unknown: { label: '未识别', color: 'default', implemented: true },
};

export function isInferenceView(value: unknown): value is InferenceView {
  return (
    typeof value === 'string' &&
    (INFERENCE_VIEWS as readonly string[]).includes(value)
  );
}

export type ResultVisualProps = {
  result?: Record<string, unknown> | null;
  outputPath?: string | null;
  onDownloadObject?: (objectName: string, filename: string) => void;
};
