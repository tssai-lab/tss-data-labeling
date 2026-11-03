import type { ProgressProps } from 'antd';

const STATUS_TEXT: Record<string, string> = {
  pending: '待提交',
  queued: '调度中',
  scheduled: '已分配',
  running: '训练中',
  success: '已完成',
  failed: '失败',
  stopped: '已停止',
  cancelled: '已取消',
};

export function getTrainingStatusText(status?: string): string {
  if (!status) return '-';
  return STATUS_TEXT[status] || status;
}

export function getTrainingStatusTagColor(status?: string): string {
  if (status === 'success') return 'success';
  if (status === 'running') return 'processing';
  if (status === 'scheduled') return 'processing';
  if (status === 'queued') return 'warning';
  if (status === 'failed') return 'error';
  if (status === 'stopped') return 'default';
  if (status === 'cancelled') return 'default';
  return 'default';
}

export function getTrainingProgressStatus(
  status?: string,
): ProgressProps['status'] {
  if (status === 'success') return 'success';
  if (status === 'failed') return 'exception';
  if (status === 'running' || status === 'queued' || status === 'scheduled')
    return 'active';
  return 'normal';
}

export function normalizeTrainingProgress(progress?: number): number {
  if (typeof progress !== 'number' || Number.isNaN(progress)) return 0;
  return Math.min(100, Math.max(0, Math.round(progress)));
}

export function isTrainingTerminal(status?: string): boolean {
  return (
    status === 'success' ||
    status === 'failed' ||
    status === 'stopped' ||
    status === 'cancelled'
  );
}

/** 从 metrics 中提取一行成功摘要，便于横幅展示 */
export function buildTrainingSuccessSummary(
  metrics?: Record<string, unknown>,
): string | undefined {
  if (!metrics || typeof metrics !== 'object') return undefined;
  const candidates = [
    ['测试集准确率', metrics.test_accuracy],
    ['验证集准确率', metrics.val_accuracy],
    ['accuracy', metrics.accuracy],
    ['test_f1', metrics.test_f1],
    ['val_f1', metrics.val_f1],
    ['test_roc_auc', metrics.test_roc_auc],
  ] as const;
  for (const [label, value] of candidates) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      return `${label}：${value.toFixed(4)}`;
    }
  }
  return undefined;
}
