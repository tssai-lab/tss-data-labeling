import type { EChartsOption } from 'echarts';

export const METRIC_LABELS: Record<string, string> = {
  train_loss: '训练损失',
  val_loss: '验证损失',
  test_loss: '测试损失',
  train_accuracy: '训练准确率',
  val_accuracy: '验证准确率',
  test_accuracy: '测试准确率',
  train_precision: '训练精确率',
  val_precision: '验证精确率',
  test_precision: '测试精确率',
  train_recall: '训练召回率',
  val_recall: '验证召回率',
  test_recall: '测试召回率',
  train_f1: '训练 F1',
  val_f1: '验证 F1',
  test_f1: '测试 F1',
  train_roc_auc: '训练 ROC AUC',
  val_roc_auc: '验证 ROC AUC',
  test_roc_auc: '测试 ROC AUC',
  val_mAP50: '验证 mAP50',
  val_mAP50_95: '验证 mAP50-95',
  loss: '损失',
  accuracy: '准确率',
  precision: '精确率',
  recall: '召回率',
  f1: 'F1',
  roc_auc: 'ROC AUC',
};

/** 训练可视化标准 MLflow 指标（与训练脚本约定一致） */
export const TRAINING_MLFLOW_METRIC_KEYS = [
  'train_loss',
  'val_loss',
  'test_loss',
  'train_accuracy',
  'val_accuracy',
  'test_accuracy',
  'train_precision',
  'val_precision',
  'test_precision',
  'train_recall',
  'val_recall',
  'test_recall',
  'train_f1',
  'val_f1',
  'test_f1',
  'train_roc_auc',
  'val_roc_auc',
  'test_roc_auc',
  'val_mAP50',
  'val_mAP50_95',
  'loss',
  'accuracy',
  'precision',
  'recall',
  'f1',
  'roc_auc',
] as const;

export type TrainingMlflowMetricKey =
  (typeof TRAINING_MLFLOW_METRIC_KEYS)[number];

export type MetricPoint = { step: number; value: number };

export type MetricsDataMap = Record<string, MetricPoint[]>;

export type ChartStyle = 'combined-line' | 'split-line' | 'bar-latest';

export const CHART_STYLE_OPTIONS: { value: ChartStyle; label: string }[] = [
  { value: 'combined-line', label: '合并折线图' },
  { value: 'split-line', label: '分指标折线图' },
  { value: 'bar-latest', label: '末值柱状图' },
];

export const METRICS_POLL_INTERVAL_MS = 4000;
export const TASK_STATUS_POLL_INTERVAL_MS = 3000;

const METRIC_SUMMARY_FIELDS: { keys: string[]; label: string }[] = [
  { keys: ['train_loss', 'loss'], label: '训练损失' },
  { keys: ['val_accuracy', 'accuracy'], label: '验证准确率' },
  { keys: ['test_accuracy'], label: '测试准确率' },
  { keys: ['test_precision', 'precision'], label: '测试精确率' },
  { keys: ['test_recall', 'recall'], label: '测试召回率' },
  { keys: ['test_f1', 'f1'], label: '测试 F1' },
  { keys: ['test_roc_auc', 'roc_auc'], label: '测试 ROC AUC' },
  { keys: ['val_mAP50'], label: '验证 mAP50' },
  { keys: ['val_mAP50_95'], label: '验证 mAP50-95' },
  { keys: ['epochs', 'epoch'], label: '训练轮数' },
  { keys: ['sample_count'], label: '样本数' },
];

export function formatMetricValue(value: unknown): string {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : value.toFixed(6);
  }
  return String(value);
}

/**
 * 是否适合以 Step 为横轴的过程曲线。
 * 以下视为终值/无过程意义，不进折线（避免画出 y=a 水平线）：
 * - 空或仅 1 个点
 * - 多个点但 Step 全相同
 * - 多个点但数值全程不变（常见：验证准确率只记了一个常数却按 epoch 重复写入）
 */
export function isStepSeriesMetric(points?: MetricPoint[] | null): boolean {
  if (!points?.length) return false;
  if (points.length <= 1) return false;
  const steps = new Set(points.map((p) => p.step));
  if (steps.size <= 1) return false;
  const values = points
    .map((p) => p.value)
    .filter((v) => typeof v === 'number' && Number.isFinite(v));
  if (values.length <= 1) return false;
  const min = Math.min(...values);
  const max = Math.max(...values);
  // 全程同一数值 → 不画过程折线
  if (max === min) return false;
  // 浮点近似常数也排除（相对/绝对极差都极小）
  const span = max - min;
  const scale = Math.max(Math.abs(min), Math.abs(max), 1);
  if (span / scale < 1e-12) return false;
  return true;
}

export function getLatestMetricValue(
  points?: MetricPoint[],
): number | undefined {
  if (!points?.length) return undefined;
  return points[points.length - 1]?.value;
}

/** 有数据的标准指标（含终值单点） */
export function getAvailableMetricKeys(data: MetricsDataMap): string[] {
  return TRAINING_MLFLOW_METRIC_KEYS.filter(
    (key) => (data[key]?.length ?? 0) > 0,
  );
}

/** 适合过程曲线（Step 横轴）的指标 */
export function getSeriesMetricKeys(data: MetricsDataMap): string[] {
  return TRAINING_MLFLOW_METRIC_KEYS.filter((key) =>
    isStepSeriesMetric(data[key]),
  );
}

/** 仅有终值/单点的指标（用卡片或表格，不进过程曲线） */
export function getScalarMetricKeys(data: MetricsDataMap): string[] {
  return TRAINING_MLFLOW_METRIC_KEYS.filter((key) => {
    const points = data[key];
    return (points?.length ?? 0) > 0 && !isStepSeriesMetric(points);
  });
}

/** 标准 MLflow 指标末值摘要（无数据时 value 为 undefined） */
export function buildMlflowMetricSummaries(data: MetricsDataMap) {
  return TRAINING_MLFLOW_METRIC_KEYS.map((key) => ({
    key,
    label: METRIC_LABELS[key] || key,
    value: getLatestMetricValue(data[key]),
    hasData: (data[key]?.length ?? 0) > 0,
  }));
}

export function extractMetricSummaries(metrics?: Record<string, unknown>) {
  if (!metrics) return [];
  return METRIC_SUMMARY_FIELDS.map(({ keys, label }) => {
    const value = keys
      .map((key) => metrics[key])
      .find((v) => v !== undefined && v !== null && v !== '');
    return value !== undefined ? { label, value } : null;
  }).filter(Boolean) as { label: string; value: unknown }[];
}

function buildSeries(key: string, points: MetricPoint[], style: ChartStyle) {
  const name = METRIC_LABELS[key] || key;
  const data = points.map((p) => [p.step, p.value]);

  return {
    name,
    type: 'line' as const,
    smooth: style !== 'combined-line',
    showSymbol: points.length <= 30,
    data,
  };
}

export function buildMetricsChartOption(
  metricsData: MetricsDataMap,
  selectedKeys: string[],
  style: ChartStyle,
  singleKey?: string,
): EChartsOption {
  const keys = singleKey ? [singleKey] : selectedKeys;

  if (style === 'bar-latest') {
    const categories = keys.map((k) => METRIC_LABELS[k] || k);
    const values = keys.map((key) => {
      const points = metricsData[key] ?? [];
      return points.length ? points[points.length - 1]?.value : 0;
    });
    return {
      tooltip: { trigger: 'axis' },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '10%',
        top: '10%',
        containLabel: true,
      },
      xAxis: { type: 'category', data: categories },
      yAxis: { type: 'value', name: 'Value' },
      series: [
        {
          type: 'bar',
          data: values,
          itemStyle: { borderRadius: [4, 4, 0, 0] },
        },
      ],
    };
  }

  const series = keys
    .map((key) => {
      const points = metricsData[key] ?? [];
      // 折线仅绘制多 step 过程序列；终值单点不进 Step 曲线
      if (!isStepSeriesMetric(points)) return null;
      return buildSeries(key, points, style);
    })
    .filter(Boolean);

  // 分指标子图：单系列、无 legend（标题由外层卡片展示），Y 轴按该指标自适应
  if (singleKey && style === 'split-line') {
    return {
      tooltip: { trigger: 'axis' },
      grid: {
        left: 48,
        right: 20,
        top: 28,
        bottom: 36,
        containLabel: true,
      },
      xAxis: {
        type: 'value',
        name: 'Step',
        nameLocation: 'middle',
        nameGap: 22,
      },
      yAxis: {
        type: 'value',
        scale: true,
      },
      series: series as EChartsOption['series'],
    };
  }

  return {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, type: 'scroll' },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '15%',
      top: '10%',
      containLabel: true,
    },
    xAxis: { type: 'value', name: 'Step' },
    yAxis: { type: 'value', name: 'Value' },
    series: series as EChartsOption['series'],
  };
}

export const ACTIVE_TASK_STATUSES = new Set([
  'pending',
  'queued',
  'scheduled',
  'running',
]);

export function isActiveTaskStatus(status?: string) {
  return !!status && ACTIVE_TASK_STATUSES.has(status);
}

/** 训练结束后后端可能稍晚才回写 runId / 指标，前端再补拉一段时间 */
export const TASK_POST_FINISH_POLL_TIMES = 8;
export const METRICS_POST_FINISH_POLL_TIMES = 8;
