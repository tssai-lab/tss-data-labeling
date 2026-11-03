import { Card, Statistic } from 'antd';
import React from 'react';
import { formatNumber, isFiniteNumber } from './resolveView';

export type MetricItem = {
  key: string;
  label: string;
  value: number;
  percent?: boolean;
};

export function pickMetrics(
  result: Record<string, unknown>,
  specs: { key: string; label: string; percent?: boolean }[],
): MetricItem[] {
  return specs
    .map((spec) => {
      const value = result[spec.key];
      if (!isFiniteNumber(value)) return null;
      return { ...spec, value };
    })
    .filter(Boolean) as MetricItem[];
}

const MetricsCards: React.FC<{ title?: string; items: MetricItem[] }> = ({
  title = '指标摘要',
  items,
}) => {
  if (!items.length) return null;
  return (
    <Card size="small" title={title}>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))',
          gap: 12,
        }}
      >
        {items.map((item) => (
          <Statistic
            key={item.key}
            title={item.label}
            value={
              item.percent
                ? Number((item.value * 100).toFixed(2))
                : Number.isInteger(item.value)
                  ? item.value
                  : Number(formatNumber(item.value))
            }
            suffix={item.percent ? '%' : undefined}
            valueStyle={{ fontSize: 20 }}
          />
        ))}
      </div>
    </Card>
  );
};

export default MetricsCards;
