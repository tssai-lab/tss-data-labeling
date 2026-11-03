/**
 * MLflow 可能同时收到“逐轮指标”和任务完成时的“最终值兜底”。
 * 同一个 step 只保留最后写入值，避免图表出现同一轮的竖线或重复点。
 */
export function normalizeMlflowMetricHistory(metrics = []) {
  const byStep = new Map();
  metrics.forEach((metric) => {
    if (!Number.isFinite(metric?.step) || !Number.isFinite(metric?.value)) return;
    byStep.set(metric.step, { step: metric.step, value: metric.value });
  });
  return [...byStep.values()].sort((a, b) => a.step - b.step);
}
