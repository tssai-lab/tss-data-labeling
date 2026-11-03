import { request } from '@umijs/max';
import { API_CONFIG } from '@/constants/platform';
import {
  TRAINING_MLFLOW_METRIC_KEYS,
  type TrainingMlflowMetricKey,
} from '@/utils/trainingMetrics';
import { normalizeMlflowMetricHistory } from './mlflowMetricHistory.mjs';

const mlflowBasePath =
  process.env.REACT_APP_MLFLOW_BASE_PATH || API_CONFIG.ENDPOINTS.MLFLOW_METRICS_HISTORY;

interface MlflowMetricsResponse {
  metrics?: API.MlflowMetricPoint[];
}

/** 空记录与读取失败必须分开；保留旧响应省略 metrics 的兼容形式。 */
function readMetricPoints(payload: unknown): API.MlflowMetricPoint[] {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new Error('训练指标响应格式异常，请刷新重试');
  }
  const response = payload as Record<string, unknown>;
  if (
    response.error_code ||
    response.errorCode ||
    response.success === false ||
    (response.metrics !== undefined && !Array.isArray(response.metrics))
  ) {
    throw new Error('训练指标响应异常，请刷新重试');
  }
  return (response.metrics as API.MlflowMetricPoint[] | undefined) ?? [];
}

/** 与标准 key 对应的 MLflow 常见别名（如 Ultralytics / 自定义脚本） */
export const MLFLOW_METRIC_ALIASES: Partial<Record<TrainingMlflowMetricKey, string[]>> = {
  train_loss: ['loss', 'train/loss', 'metrics/train/loss', 'training_loss'],
  val_accuracy: [
    'validation_accuracy',
    'accuracy',
    'val/accuracy',
    'metrics/accuracy',
    'top1_acc',
  ],
  val_precision: ['validation_precision', 'precision', 'val/precision'],
  val_recall: ['validation_recall', 'recall', 'val/recall'],
  val_f1: ['validation_f1', 'f1', 'val/f1'],
  val_mAP50: ['val/mAP50', 'mAP50', 'metrics/mAP50', 'val/mAP50(B)', 'mAP_0.5'],
  val_mAP50_95: [
    'val/mAP50-95',
    'mAP50-95',
    'metrics/mAP50-95',
    'val/mAP50-95(B)',
    'mAP_0.5:0.95',
  ],
};

export const MLFLOW_METRIC_KEYS = [...TRAINING_MLFLOW_METRIC_KEYS] as const;

export async function fetchMlflowMetricHistory(
  runId: string,
  metricKey: string,
  maxResults = 10000,
  options?: { [key: string]: any },
) {
  const url = `${mlflowBasePath}?run_id=${encodeURIComponent(runId)}&metric_key=${encodeURIComponent(metricKey)}&max_results=${maxResults}`;

  return request<MlflowMetricsResponse>(url, {
    method: 'GET',
    skipErrorHandler: true,
    baseURL: '',
    ...(options || {}),
  });
}

async function fetchMetricSeries(
  runId: string,
  canonicalKey: TrainingMlflowMetricKey,
  options?: { [key: string]: any },
): Promise<{ step: number; value: number }[]> {
  const candidates = [
    canonicalKey,
    ...(MLFLOW_METRIC_ALIASES[canonicalKey] ?? []),
  ];

  for (const key of candidates) {
    const res = await fetchMlflowMetricHistory(runId, key, 10000, options);
    const list = readMetricPoints(res);
    // 只有成功查得空记录才尝试别名；鉴权、超时等错误交给页面明确展示。
    if (!list.length) continue;
    return normalizeMlflowMetricHistory(list);
  }

  return [];
}

export async function fetchMlflowMetricsBulk(
  runId: string,
  metricKeys: string[] = [...MLFLOW_METRIC_KEYS],
  options?: { [key: string]: any },
): Promise<Record<string, { step: number; value: number }[]>> {
  const result: Record<string, { step: number; value: number }[]> = {};

  await Promise.all(
    metricKeys.map(async (key) => {
      if (TRAINING_MLFLOW_METRIC_KEYS.includes(key as TrainingMlflowMetricKey)) {
        result[key] = await fetchMetricSeries(
          runId,
          key as TrainingMlflowMetricKey,
          options,
        );
        return;
      }
      const res = await fetchMlflowMetricHistory(runId, key, 10000, options);
      result[key] = normalizeMlflowMetricHistory(readMetricPoints(res));
    }),
  );

  return result;
}
