import { objectNameFromMinioPath } from '@/services/inference';
import { type InferenceView, isInferenceView } from './types';

export function isPlainObject(
  value: unknown,
): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

export function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

export function isObjectArray(
  value: unknown,
): value is Record<string, unknown>[] {
  return (
    Array.isArray(value) &&
    value.length > 0 &&
    value.slice(0, 12).every((item) => isPlainObject(item))
  );
}

export function hasBBox(value: unknown): boolean {
  if (!isPlainObject(value)) return false;
  const box = isPlainObject(value.bbox) ? value.bbox : value;
  return ['x1', 'y1', 'x2', 'y2'].every((k) => isFiniteNumber(box[k]));
}

export function getBBox(det: Record<string, unknown>) {
  const box = isPlainObject(det.bbox) ? det.bbox : det;
  if (!hasBBox({ bbox: box })) return null;
  return {
    x1: box.x1 as number,
    y1: box.y1 as number,
    x2: box.x2 as number,
    y2: box.y2 as number,
  };
}

export function joinOutputObject(
  outputPath?: string | null,
  relative?: string | null,
) {
  if (!relative?.trim()) return '';
  const raw = relative.trim();
  if (raw.startsWith('/workspace/') || raw.startsWith('workspace/')) {
    return '';
  }
  if (raw.startsWith('users/') || raw.startsWith('minio://')) {
    return objectNameFromMinioPath(raw);
  }
  const base = objectNameFromMinioPath(outputPath);
  if (!base) return '';
  return `${base.replace(/\/?$/, '/')}${raw.replace(/^\/+/, '')}`;
}

export function formatNumber(value: number) {
  if (Number.isInteger(value)) return String(value);
  if (Math.abs(value) <= 1) return value.toFixed(4);
  return value.toLocaleString(undefined, { maximumFractionDigits: 4 });
}

/** 按字段形状兜底识别 view（无 result.view 时） */
export function detectViewByShape(
  result?: Record<string, unknown> | null,
): InferenceView {
  if (!result || !isPlainObject(result)) return 'unknown';

  // 1 检测
  if (isObjectArray(result.images)) {
    const hit = result.images.some(
      (img) =>
        (Array.isArray(img.detections) &&
          typeof img.annotatedImage === 'string' &&
          img.annotatedImage.trim().length > 0) ||
        (isObjectArray(img.detections) &&
          img.detections.some((det) => hasBBox(det))),
    );
    if (hit) return 'image_detection';
  }
  if (
    isObjectArray(result.predictions) &&
    result.predictions.some((p) => hasBBox(p)) &&
    (result.media || result.image || result.annotatedImage)
  ) {
    return 'image_detection';
  }

  // 2 分割
  if (
    isObjectArray(result.predictions) &&
    result.predictions.some(
      (p) =>
        isObjectArray(p.polygon) ||
        typeof p.mask === 'string' ||
        typeof p.maskPath === 'string' ||
        isPlainObject(p.mask),
    )
  ) {
    return 'image_segmentation';
  }

  // 3 关键点
  if (
    isObjectArray(result.predictions) &&
    result.predictions.some(
      (p) => isObjectArray(p.keypoints) || isObjectArray(p.keyPoints),
    )
  ) {
    return 'image_keypoints';
  }

  // 4 OCR：框 + 文本
  if (
    isObjectArray(result.predictions) &&
    result.predictions.some(
      (p) => hasBBox(p) && typeof p.text === 'string' && p.text.trim(),
    )
  ) {
    return 'image_ocr';
  }

  // 5 图像对比
  if (
    (result.inputImage || result.sourceImage) &&
    (result.outputImage || result.generatedImage)
  ) {
    return 'image_pair';
  }

  // 6 图像分类
  if (
    isObjectArray(result.predictionsPreview) ||
    isFiniteNumber(result.top1Accuracy) ||
    (isPlainObject(result.labelCounts) &&
      isPlainObject(result.predictionCounts))
  ) {
    return 'image_classification';
  }

  // 7 表格分类
  if (
    isObjectArray(result.preview) &&
    result.preview.some(
      (row) =>
        isFiniteNumber(row.probability) ||
        row.prediction === 0 ||
        row.prediction === 1 ||
        isFiniteNumber(row.prediction),
    )
  ) {
    return 'table_classification';
  }

  // 8 回归
  if (
    isFiniteNumber(result.mae) ||
    isFiniteNumber(result.rmse) ||
    isFiniteNumber(result.mse) ||
    (isObjectArray(result.preview) &&
      result.preview.some(
        (row) =>
          isFiniteNumber(row.y_true) ||
          isFiniteNumber(row.yTrue) ||
          isFiniteNumber(row.y_pred) ||
          isFiniteNumber(row.yPred),
      ))
  ) {
    return 'table_regression';
  }

  // 9 点云
  if (
    result.points ||
    result.pointCloud ||
    typeof result.pcd === 'string' ||
    typeof result.ply === 'string' ||
    isObjectArray(result.bboxes3d)
  ) {
    return 'pointcloud';
  }

  // 10 NER
  if (
    typeof result.text === 'string' &&
    (isObjectArray(result.spans) || isObjectArray(result.entities))
  ) {
    return 'text_spans';
  }

  // 11 生成
  if (
    typeof result.input === 'string' &&
    typeof result.output === 'string' &&
    result.input.length > 20 &&
    result.output.length > 20
  ) {
    return 'text_generation';
  }

  // 12 检索
  if (
    result.query != null &&
    (isObjectArray(result.candidates) || isObjectArray(result.hits))
  ) {
    return 'text_retrieval';
  }

  // 13 文本分类
  if (
    typeof result.text === 'string' &&
    (typeof result.label === 'string' || isFiniteNumber(result.score)) &&
    !result.image &&
    !result.images
  ) {
    return 'text_classification';
  }

  // 14 多模态
  if (
    (result.image || result.media) &&
    (typeof result.text === 'string' || typeof result.question === 'string') &&
    (isFiniteNumber(result.score) || result.answer != null)
  ) {
    return 'multimodal';
  }

  // 15 仅指标
  const metricKeys = ['accuracy', 'f1', 'precision', 'recall', 'mAP', 'map'];
  const hasMetric = metricKeys.some((k) => isFiniteNumber(result[k]));
  const hasSamples =
    isObjectArray(result.samples) ||
    isObjectArray(result.preview) ||
    isObjectArray(result.predictionsPreview) ||
    isObjectArray(result.images);
  if (hasMetric && !hasSamples) {
    return 'metrics';
  }

  return 'unknown';
}

/** 优先 result.view，否则字段探测 */
export function resolveView(
  result?: Record<string, unknown> | null,
): InferenceView {
  if (result && isInferenceView(result.view)) {
    return result.view;
  }
  return detectViewByShape(result);
}
