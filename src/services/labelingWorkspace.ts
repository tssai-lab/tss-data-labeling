import type { Batch, Project } from './labeling';

export const workbenchViews = [
  {
    key: 'review',
    label: '结果与审核',
    description: '查看样本答案、选择需要处理的样本，确认或标记待修正。',
  },
  {
    key: 'publish',
    label: '重新标注',
    description: '把所选样本重新分包给人工处理。首次导入数据请使用“任务发布”。',
  },
  {
    key: 'ai',
    label: 'AI 处理',
    description: '让 Agent 处理所选样本，查看进度；AI 答案仍需审核。',
  },
  {
    key: 'sources',
    label: '来源审核与集成',
    description: '查看标注人交回的原始提交、修订答案和审核记录。',
  },
  {
    key: 'export',
    label: '成果与导出',
    description: '将所选已确认答案保存为成果快照，导出正式 CSV / JSONL ZIP。',
  },
] as const;
export type WorkbenchView = (typeof workbenchViews)[number]['key'];
export const workbenchView = (value?: string | null): WorkbenchView =>
  workbenchViews.find((view) => view.key === value)?.key || 'review';
export function publishingStep(
  project: Project | undefined,
  batch: Batch | undefined,
  search: string,
): number {
  const requested = new URLSearchParams(search).get('step');
  if (!project?.specVersions?.length) return 0;
  if (requested === 'spec') return 0;
  if (requested === 'data') return 1;
  if (requested === 'publish') return 2;
  return batch?.status === 'READY' ? 2 : 1;
}
export function selectedForSpec<T extends { specVersionId: string }>(
  items: T[],
  spec: string,
): T[] {
  return items.filter((item) => item.specVersionId === spec);
}
export function canFinalize(
  items: {
    reviewStatus: string;
    resultId: string | null;
    selectedResultId?: string | null;
    activeProcessing: boolean;
  }[],
): boolean {
  return (
    items.length > 0 &&
    items.every(
      (item) =>
        item.reviewStatus === 'ACCEPTED' &&
        Boolean(item.resultId) &&
        item.resultId === item.selectedResultId &&
        !item.activeProcessing,
    )
  );
}
export function statusName(status?: string | null): string {
  return (
    (
      {
        QUEUED: '排队中',
        RUNNING: '处理中',
        SUCCEEDED: '已完成',
        PARTIAL: '部分完成',
        FAILED: '失败',
        CANCELLED: '已取消',
        OPEN: '未审核',
        ACCEPTED: '已接受',
        ACCEPT: '接受原始答案',
        MODIFY: '修改并接受',
        REJECT: '退回',
        REJECTED: '已退回',
        CORRECTION: '待修正',
        NEEDS_REVISION: '待修正',
        CLOSED: '已关闭',
        UNREVIEWED: '未审核',
        REVIEW_ITEM: '逐样本审核',
        REVIEW_DECIDE: '整份审核',
      } as Record<string, string>
    )[status || ''] ||
    status ||
    '未审核'
  );
}
