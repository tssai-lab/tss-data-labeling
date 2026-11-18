export const sampleName = (row: {
  displayName?: string;
  sourcePath?: string;
  sampleId?: string;
}) => row.displayName || row.sourcePath || row.sampleId || '—';

export const reviewStatusName = (status?: string) =>
  (
    ({
      OPEN: '待核查',
      PENDING: '待核查',
      PARTIAL: '部分已核查',
      ACCEPTED: '已确认',
      REJECTED: '需重标',
      MIXED: '核查结论不一致',
      REVIEWED: '已核查',
      CORRECTION: '待修正',
    }) as Record<string, string>
  )[status || ''] || '待核查';

export const fileSizeName = (value?: number) => {
  if (value == null || !Number.isFinite(value)) return '—';
  if (value < 1024) return `${value} B`;
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 ** 2).toFixed(1)} MB`;
};
