import { FilterOutlined } from '@ant-design/icons';
import type { ResultStatistics } from '@/services/labelingOwner';
import ui from './workspaceUi.less';

export type ResultMetric = 'all' | 'hasResult' | 'noResult' | 'accepted';

/** Counts are server statistics for the current specification/source scope. */
export default function ResultMetrics({
  statistics,
  active,
  onChange,
  disabled = false,
}: {
  statistics?: ResultStatistics;
  active?: ResultMetric;
  onChange: (metric: ResultMetric) => void;
  disabled?: boolean;
}) {
  const metrics = [
    { key: 'all', label: '全部样本', value: statistics?.total },
    { key: 'hasResult', label: '有候选', value: statistics?.hasResultCount },
    { key: 'noResult', label: '无候选', value: statistics?.noResultCount },
    { key: 'accepted', label: '已确认', value: statistics?.acceptedCount },
  ] as const;
  return (
    <section className={ui.metrics} aria-label="样本统计与快速筛选">
      {metrics.map((metric) => (
        <button
          key={metric.key}
          type="button"
          className={ui.metric}
          aria-pressed={active === metric.key}
          disabled={disabled || metric.value === undefined}
          onClick={() => onChange(metric.key)}
        >
          <span>{metric.label}</span>
          <strong>
            {metric.value ?? '—'}
            <span> 个</span>
          </strong>
          <FilterOutlined aria-hidden="true" />
        </button>
      ))}
    </section>
  );
}
