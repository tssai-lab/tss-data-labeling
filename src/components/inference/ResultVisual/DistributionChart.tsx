import { Card } from 'antd';
import * as echarts from 'echarts';
import React, { useEffect, useRef } from 'react';
import { isFiniteNumber, isPlainObject } from './resolveView';

function toNumberMap(data?: Record<string, unknown> | null) {
  if (!isPlainObject(data)) return null;
  const entries = Object.entries(data).filter(([, v]) => isFiniteNumber(v)) as [
    string,
    number,
  ][];
  if (!entries.length) return null;
  return Object.fromEntries(entries);
}

const DistributionChart: React.FC<{
  title: string;
  compare: { name: string; data?: Record<string, unknown> | null }[];
}> = ({ title, compare }) => {
  const ref = useRef<HTMLDivElement>(null);
  const seriesMaps = compare
    .map((item) => ({ name: item.name, map: toNumberMap(item.data) }))
    .filter((item) => item.map);

  useEffect(() => {
    if (!ref.current || !seriesMaps.length) return;
    const chart = echarts.init(ref.current);
    const labels = Array.from(
      new Set(seriesMaps.flatMap((s) => Object.keys(s.map || {}))),
    );
    chart.setOption({
      tooltip: { trigger: 'axis' },
      legend: seriesMaps.length > 1 ? { top: 0 } : undefined,
      grid: {
        left: 40,
        right: 16,
        top: seriesMaps.length > 1 ? 36 : 24,
        bottom: 48,
      },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: {
          interval: 0,
          rotate: labels.length > 4 ? 25 : 0,
          fontSize: 11,
        },
      },
      yAxis: { type: 'value', minInterval: 1 },
      series: seriesMaps.map((s, index) => ({
        name: s.name,
        type: 'bar',
        data: labels.map((label) => s.map?.[label] ?? 0),
        barMaxWidth: 36,
        itemStyle: {
          color: index === 0 ? '#1677ff' : '#52c41a',
          borderRadius: [4, 4, 0, 0],
        },
      })),
    });
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [title, compare]);

  if (!seriesMaps.length) return null;
  return (
    <Card size="small" title={title}>
      <div ref={ref} style={{ width: '100%', height: 260 }} />
    </Card>
  );
};

export default DistributionChart;
