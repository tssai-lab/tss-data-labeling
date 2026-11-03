import { Line } from '@ant-design/plots';
import React, { useMemo } from 'react';
import { isMetricAvailable } from './metricDisplay.mjs';

/** Grafana / 监控面板常用配色 */
export const SERIES_COLOR_MAP = {
  CPU: '#5794F2',
  内存: '#73BF69',
  GPU: '#B877D9',
};

const SERIES_ORDER = ['CPU', '内存', 'GPU'];

/** 各粒度 X 轴左侧最大值（右侧固定为 0，表示距当前的时间单位数） */
const INTERVAL_MAX_OFFSET = {
  '1min': 60,
  '10min': 12,
  '1hour': 24,
  '1day': 7,
};

/**
 * 将数据映射为相对偏移量
 * 左侧 = maxOffset（最早），右侧 = 0（当前）
 *
 * 不依赖后端 tickIndex 的语义（0=最新 还是 0=最早），统一按 fullTime
 * 降序排列（最新在前），用数组下标计算偏移，mock 与真实接口均正确。
 */
const transformChartData = (data = [], interval = '1hour') => {
  const maxOffset = INTERVAL_MAX_OFFSET[interval] ?? 24;

  // 按时间降序：index 0 = 最新 → offset 0 → 右侧
  const sorted = [...data].sort((a, b) => {
    if (!a.fullTime || !b.fullTime) return 0;
    return b.fullTime.localeCompare(a.fullTime);
  });

  // 按 type 分组，各自用组内序号算 offset。
  // 若用整段数组下标，三条曲线交织时每条线的首尾点都会内缩，
  // 导致线两端够不到横轴边缘、看起来短一截。
  const groups = new Map();
  for (const d of sorted) {
    const key = d.type ?? 'default';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(d);
  }

  const result = [];
  for (const series of groups.values()) {
    const maxIndex = Math.max(series.length - 1, 1);
    series.forEach((d, index) => {
      result.push({
        ...d,
        offset: +((index * maxOffset) / maxIndex).toFixed(2),
      });
    });
  }
  return result;
};

/** 生成 X 轴刻度（右 0 → 左 max，中间均分） */
const buildXTicks = (maxOffset) => {
  const count = 5;
  return Array.from({ length: count }, (_, i) =>
    Math.round((maxOffset * i) / (count - 1)),
  );
};

const getAxisConfig = (maxOffset) => {
  const ticks = buildXTicks(maxOffset);

  return {
    x: {
      position: 'bottom',
      title: false,
      line: true,
      lineStroke: '#e5e6eb',
      tick: true,
      tickStroke: '#e5e6eb',
      tickLength: 4,
      size: 36,
      label: true,
      labelFontSize: 12,
      labelFill: '#595959',
      labelAlign: 'horizontal',
      labelAutoRotate: false,
      labelFormatter: (v) => String(Math.round(Number(v))),
      tickFilter: (v) => ticks.includes(Math.round(Number(v))),
      labelFilter: (v) => ticks.includes(Math.round(Number(v))),
    },
    y: {
      position: 'left',
      title: false,
      label: true,
      labelFontSize: 12,
      labelFill: '#595959',
      labelFormatter: (v) => `${v}%`,
      grid: true,
      gridStroke: '#f0f0f0',
      gridLineDash: [4, 4],
      line: false,
      tick: false,
    },
  };
};

export const getResourceTrendChartConfig = (data = [], options = {}) => {
  const { height = 380, interval = '1hour' } = options;
  const maxOffset = INTERVAL_MAX_OFFSET[interval] ?? 24;
  const chartData = transformChartData(data, interval);

  return {
    data: chartData,
    xField: 'offset',
    yField: 'value',
    colorField: 'type',
    height,
    autoFit: true,
    padding: [40, 32, 48, 56],
    style: { lineWidth: 2 },
    connectNulls: false,
    area: { style: { fillOpacity: 0.08 } },
    scale: {
      x: {
        type: 'linear',
        domain: [0, maxOffset],
        range: [1, 0],
        tickMethod: () => buildXTicks(maxOffset),
      },
      y: { domain: [0, 100], nice: true },
      color: {
        domain: SERIES_ORDER,
        range: SERIES_ORDER.map((key) => SERIES_COLOR_MAP[key]),
      },
    },
    axis: getAxisConfig(maxOffset),
    legend: {
      color: {
        position: 'top',
        layout: { justifyContent: 'center' },
        itemMarker: 'hyphen',
        itemSpacing: 24,
      },
    },
    interaction: {
      tooltip: {
        shared: true,
        crosshairs: true,
        crosshairsStroke: '#8c8c8c',
        crosshairsLineDash: [4, 4],
      },
    },
    tooltip: {
      title: 'fullTime',
      items: [
        {
          channel: 'y',
          name: 'type',
          valueFormatter: (v) => `${v}%`,
        },
      ],
    },
    animation: {
      appear: { animation: 'path-in', duration: 800 },
    },
  };
};

const ResourceTrendChart = ({
  data = [],
  height = 380,
  interval = '1hour',
}) => {
  const safeData = Array.isArray(data) ? data : [];
  const hasAvailableData = safeData.some((point) =>
    isMetricAvailable(point?.value),
  );

  const config = useMemo(
    () => getResourceTrendChartConfig(safeData, { height, interval }),
    [safeData, height, interval],
  );

  if (!hasAvailableData) {
    return (
      <div
        style={{
          height,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: '#999',
        }}
      >
        暂无趋势数据
      </div>
    );
  }

  return <Line {...config} />;
};

export default ResourceTrendChart;
