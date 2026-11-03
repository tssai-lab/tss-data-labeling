import { ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Input,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
} from 'antd';
import * as echarts from 'echarts';
import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { fetchMlflowMetricsBulk } from '@/services/platform';
import {
  buildMetricsChartOption,
  buildMlflowMetricSummaries,
  CHART_STYLE_OPTIONS,
  type ChartStyle,
  extractMetricSummaries,
  formatMetricValue,
  getScalarMetricKeys,
  getSeriesMetricKeys,
  isActiveTaskStatus,
  METRIC_LABELS,
  METRICS_POLL_INTERVAL_MS,
  METRICS_POST_FINISH_POLL_TIMES,
  type MetricsDataMap,
  TRAINING_MLFLOW_METRIC_KEYS,
} from '@/utils/trainingMetrics';
import { isTrainingTerminal } from '@/utils/trainingStatusDisplay';

const CHART_STYLE_STORAGE_KEY = 'taskMetricsChartStyle';

type TrainingMetricsPanelProps = {
  runId?: string;
  taskStatus?: string;
  progress?: number;
  backendMetrics?: Record<string, unknown>;
  runIdInput?: string;
  onRunIdInputChange?: (value: string) => void;
  onManualRunId?: (runId: string) => void;
};

/** 安全销毁：避免 ECharts dispose 与 React 卸载抢同一 DOM 触发 removeChild */
function disposeChart(instance: echarts.ECharts | null | undefined) {
  if (!instance || instance.isDisposed?.()) return;
  try {
    instance.dispose();
  } catch {
    // DOM 可能已被 React 卸掉，忽略
  }
}

type CombinedChartProps = {
  metricsData: MetricsDataMap;
  selectedKeys: string[];
  chartStyle: ChartStyle;
};

const CombinedMetricsChart: React.FC<CombinedChartProps> = ({
  metricsData,
  selectedKeys,
  chartStyle,
}) => {
  const hostRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const el = hostRef.current;
    if (!el || !selectedKeys.length) return;

    const instance = echarts.init(el);
    instance.setOption(
      buildMetricsChartOption(metricsData, selectedKeys, chartStyle),
      { notMerge: true },
    );

    const onResize = () => instance.resize();
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      disposeChart(instance);
    };
  }, [metricsData, selectedKeys, chartStyle]);

  return <div ref={hostRef} style={{ height: 400, width: '100%' }} />;
};

type SplitChartProps = {
  metricKey: string;
  metricsData: MetricsDataMap;
  selectedKeys: string[];
};

const SplitMetricChart: React.FC<SplitChartProps> = ({
  metricKey,
  metricsData,
  selectedKeys,
}) => {
  const hostRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const el = hostRef.current;
    if (!el) return;

    const instance = echarts.init(el);
    instance.setOption(
      buildMetricsChartOption(
        metricsData,
        selectedKeys,
        'split-line',
        metricKey,
      ),
      { notMerge: true },
    );
    instance.resize();

    const onResize = () => instance.resize();
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      disposeChart(instance);
    };
  }, [metricKey, metricsData, selectedKeys]);

  return (
    <div
      style={{
        border: '1px solid #f0f0f0',
        borderRadius: 8,
        padding: 12,
      }}
    >
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        {METRIC_LABELS[metricKey] || metricKey}
      </Typography.Text>
      <div ref={hostRef} style={{ height: 280, width: '100%' }} />
    </div>
  );
};

const TrainingMetricsPanelContent: React.FC<TrainingMetricsPanelProps> = ({
  runId,
  taskStatus,
  progress,
  backendMetrics,
  runIdInput = '',
  onRunIdInputChange,
  onManualRunId,
}) => {
  const [metricsData, setMetricsData] = useState<MetricsDataMap>({});
  const [metricsLoading, setMetricsLoading] = useState(false);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<string>('');
  const [metricsError, setMetricsError] = useState(false);
  const metricsRequest = useRef<{
    sequence: number;
    controller?: AbortController;
  }>({ sequence: 0 });
  const [chartStyle, setChartStyle] = useState<ChartStyle>(() => {
    const saved = localStorage.getItem(
      CHART_STYLE_STORAGE_KEY,
    ) as ChartStyle | null;
    return saved && CHART_STYLE_OPTIONS.some((o) => o.value === saved)
      ? saved
      : 'combined-line';
  });
  const [selectedMetrics, setSelectedMetrics] = useState<string[]>([]);
  const [autoRefresh, setAutoRefresh] = useState(true);

  const isActive = isActiveTaskStatus(taskStatus);
  const hasAnyMetricPoints = useMemo(
    () =>
      Object.values(metricsData).some(
        (points) => Array.isArray(points) && points.length > 0,
      ),
    [metricsData],
  );
  const shouldPoll =
    !!runId &&
    autoRefresh &&
    (isActive || (isTrainingTerminal(taskStatus) && !hasAnyMetricPoints));

  // 过程曲线仅用多 step 序列；终值单点只进上方卡片
  const seriesMetricKeys = useMemo(
    () => getSeriesMetricKeys(metricsData),
    [metricsData],
  );
  const scalarMetricKeys = useMemo(
    () => getScalarMetricKeys(metricsData),
    [metricsData],
  );

  const effectiveSelected = useMemo(() => {
    const picked = selectedMetrics.filter((k) => seriesMetricKeys.includes(k));
    return picked.length ? picked : seriesMetricKeys;
  }, [selectedMetrics, seriesMetricKeys]);

  const useSplitLayout = chartStyle === 'split-line';

  const loadMetrics = useCallback(
    async (silent = false) => {
      if (!runId) return;
      // 自动刷新不叠加请求；手动刷新取代旧请求，避免慢响应覆盖较新的结果。
      if (silent && metricsRequest.current.controller) return;
      metricsRequest.current.controller?.abort();
      const controller = new AbortController();
      const sequence = ++metricsRequest.current.sequence;
      metricsRequest.current.controller = controller;
      if (!silent) setMetricsLoading(true);
      try {
        const data = await fetchMlflowMetricsBulk(runId, undefined, {
          skipErrorHandler: true,
          signal: controller.signal,
        });
        if (sequence !== metricsRequest.current.sequence) return;
        setMetricsData(data);
        setLastUpdatedAt(new Date().toLocaleTimeString());
        setMetricsError(false);
      } catch {
        if (sequence !== metricsRequest.current.sequence) return;
        controller.abort();
        // 失败不能擦掉上次成功值，也不能更新“成功时间”。
        setMetricsError(true);
      } finally {
        if (sequence === metricsRequest.current.sequence) {
          metricsRequest.current.controller = undefined;
          setMetricsLoading(false);
        }
      }
    },
    [runId],
  );

  useEffect(() => {
    loadMetrics(false);
    return () => {
      metricsRequest.current.sequence += 1;
      metricsRequest.current.controller?.abort();
      metricsRequest.current.controller = undefined;
    };
  }, [loadMetrics]);

  useEffect(() => {
    if (!shouldPoll) return;
    if (isActive) {
      const timer = window.setInterval(() => {
        loadMetrics(true);
      }, METRICS_POLL_INTERVAL_MS);
      return () => window.clearInterval(timer);
    }
    let left = METRICS_POST_FINISH_POLL_TIMES;
    const timer = window.setInterval(() => {
      left -= 1;
      loadMetrics(true);
      if (left <= 0) {
        window.clearInterval(timer);
      }
    }, METRICS_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [shouldPoll, isActive, loadMetrics]);

  useEffect(() => {
    if (!seriesMetricKeys.length) {
      setSelectedMetrics([]);
      return;
    }
    setSelectedMetrics((prev) => {
      if (!prev.length) return seriesMetricKeys;
      const merged = [...new Set([...prev, ...seriesMetricKeys])].filter((k) =>
        seriesMetricKeys.includes(k),
      );
      return merged.length ? merged : seriesMetricKeys;
    });
  }, [seriesMetricKeys]);

  const mlflowMetricSummaries = useMemo(
    () =>
      buildMlflowMetricSummaries(metricsData).filter((item) => item.hasData),
    [metricsData],
  );

  useEffect(() => {
    localStorage.setItem(CHART_STYLE_STORAGE_KEY, chartStyle);
  }, [chartStyle]);

  const metricSummaries = extractMetricSummaries(backendMetrics);
  const hasSeriesCharts = seriesMetricKeys.length > 0;
  const hasScalarOnly = !hasSeriesCharts && scalarMetricKeys.length > 0;

  if (!runId) {
    return (
      <div style={{ padding: 24, background: '#fafafa', borderRadius: 8 }}>
        <div style={{ marginBottom: 12, color: '#8c8c8c' }}>
          当前任务暂无可视化指标。需要手动定位时，可输入运行记录编号：
        </div>
        <Input.Search
          placeholder="运行记录编号（高级）"
          value={runIdInput}
          onChange={(e) => onRunIdInputChange?.(e.target.value)}
          onSearch={(value) => onManualRunId?.(value.trim())}
          enterButton="加载指标"
          style={{ maxWidth: 480 }}
        />
        {metricSummaries.length > 0 && (
          <MetricSummaryGrid
            summaries={metricSummaries}
            style={{ marginTop: 24 }}
          />
        )}
      </div>
    );
  }

  return (
    <div>
      <Space wrap style={{ marginBottom: 16 }} align="center">
        {hasSeriesCharts ? (
          <>
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>图表样式</span>
            <Select
              value={chartStyle}
              onChange={setChartStyle}
              options={CHART_STYLE_OPTIONS}
              style={{ width: 160 }}
            />
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>过程指标</span>
            <Select
              mode="multiple"
              allowClear
              placeholder="选择过程曲线指标"
              value={effectiveSelected}
              onChange={setSelectedMetrics}
              options={TRAINING_MLFLOW_METRIC_KEYS.map((k) => ({
                value: k,
                label: METRIC_LABELS[k] || k,
                disabled: !seriesMetricKeys.includes(k),
              }))}
              style={{ minWidth: 280 }}
              maxTagCount={4}
            />
            <Button
              size="small"
              disabled={!seriesMetricKeys.length}
              onClick={() => setSelectedMetrics([...seriesMetricKeys])}
            >
              全选过程指标
            </Button>
          </>
        ) : null}
        <Space size={4}>
          <Switch
            size="small"
            checked={autoRefresh}
            onChange={setAutoRefresh}
            disabled={!isActive}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            自动刷新
          </Typography.Text>
        </Space>
        <Button
          size="small"
          icon={<ReloadOutlined />}
          onClick={() => loadMetrics(false)}
          loading={metricsLoading}
        >
          刷新
        </Button>
        {lastUpdatedAt && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            更新于 {lastUpdatedAt}
          </Typography.Text>
        )}
        {isActive && (
          <Tag color="processing">
            训练中 · 指标 {METRICS_POLL_INTERVAL_MS / 1000}s 刷新
          </Tag>
        )}
        {hasScalarOnly && (
          <Tag color="blue">终值指标以下方卡片展示，不画 Step 曲线</Tag>
        )}
        {typeof progress === 'number' && progress > 0 && isActive ? (
          <Tag>进度 {progress}%</Tag>
        ) : null}
      </Space>

      {metricsError && (
        <Alert
          showIcon
          type={lastUpdatedAt ? 'warning' : 'error'}
          message="训练指标加载失败"
          description={
            lastUpdatedAt
              ? '本次未能更新，当前保留上一次成功查询的结果；请点击「刷新」重试。'
              : '无法读取训练指标，请点击「刷新」重试；这不表示训练没有产生指标。'
          }
          style={{ marginBottom: 16 }}
        />
      )}

      {runId && mlflowMetricSummaries.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <Typography.Text
            type="secondary"
            style={{ fontSize: 12, display: 'block', marginBottom: 8 }}
          >
            训练指标末值
            {scalarMetricKeys.length > 0 && seriesMetricKeys.length > 0
              ? '（含终值/常数指标与过程序列末值；常数不进折线）'
              : scalarMetricKeys.length > 0
                ? '（当前均为终值或常数，与 Step 过程无关，不画折线）'
                : '（训练写入后自动更新）'}
          </Typography.Text>
          <MlflowMetricSummaryGrid summaries={mlflowMetricSummaries} />
        </div>
      )}

      {metricsLoading && !hasSeriesCharts && !mlflowMetricSummaries.length ? (
        <div
          style={{
            height: 400,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Spin size="large" />
        </div>
      ) : hasSeriesCharts ? (
        useSplitLayout ? (
          <div
            key={`split-${effectiveSelected.join('|')}`}
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
              gap: 16,
            }}
          >
            {effectiveSelected.map((key) => (
              <SplitMetricChart
                key={key}
                metricKey={key}
                metricsData={metricsData}
                selectedKeys={effectiveSelected}
              />
            ))}
          </div>
        ) : (
          <CombinedMetricsChart
            key={`combined-${chartStyle}`}
            metricsData={metricsData}
            selectedKeys={effectiveSelected}
            chartStyle={chartStyle}
          />
        )
      ) : hasScalarOnly ? (
        <div
          style={{
            padding: '16px 20px',
            background: '#fafafa',
            borderRadius: 8,
            color: '#8c8c8c',
            fontSize: 13,
            lineHeight: 1.6,
          }}
        >
          当前训练指标均为终值或常数（单点、同
          Step，或全程同一数值），已用上方卡片展示，不再绘制 Step
          过程折线，避免出现水平直线。若指标随 step/epoch
          有真实变化，刷新后会出现过程曲线。
        </div>
      ) : metricsError ? null : (
        <div
          style={{
            height: 320,
            background: '#fafafa',
            borderRadius: 8,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#8c8c8c',
          }}
        >
          <div style={{ textAlign: 'center' }}>
            <div style={{ fontSize: 16, marginBottom: 8 }}>
              暂无训练指标数据
            </div>
            <div style={{ fontSize: 12 }}>
              {isActive
                ? '训练进行中，指标写入后将自动刷新；也可点击「刷新」手动拉取'
                : '请确认训练指标服务可用，且该运行记录已写入指标'}
            </div>
          </div>
        </div>
      )}

      {metricSummaries.length > 0 && (
        <MetricSummaryGrid
          summaries={metricSummaries}
          style={{ marginTop: 24 }}
        />
      )}
    </div>
  );
};

function MlflowMetricSummaryGrid({
  summaries,
}: {
  summaries: ReturnType<typeof buildMlflowMetricSummaries>;
}) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
        gap: 12,
      }}
    >
      {summaries.map(({ key, label, value, hasData }) => (
        <div
          key={key}
          style={{
            background: hasData ? '#fafafa' : '#fff',
            border: hasData ? 'none' : '1px dashed #d9d9d9',
            padding: 14,
            borderRadius: 6,
          }}
        >
          <div style={{ color: '#8c8c8c', fontSize: 12, marginBottom: 6 }}>
            {label}
          </div>
          <div
            style={{
              fontSize: hasData ? 20 : 14,
              fontWeight: hasData ? 600 : 400,
              color: hasData ? undefined : '#bfbfbf',
            }}
          >
            {hasData ? formatMetricValue(value) : '暂无数据'}
          </div>
        </div>
      ))}
    </div>
  );
}

function MetricSummaryGrid({
  summaries,
  style,
}: {
  summaries: { label: string; value: unknown }[];
  style?: React.CSSProperties;
}) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
        gap: 12,
        ...style,
      }}
    >
      {summaries.map(({ label, value }) => (
        <div
          key={label}
          style={{ background: '#fafafa', padding: 14, borderRadius: 6 }}
        >
          <div style={{ color: '#8c8c8c', fontSize: 12, marginBottom: 6 }}>
            {label}
          </div>
          <div style={{ fontSize: 22, fontWeight: 600 }}>
            {formatMetricValue(value)}
          </div>
        </div>
      ))}
    </div>
  );
}

/** Run 改变时隔离整个展示状态，防止上一任务的曲线短暂显示在新任务下。 */
const TrainingMetricsPanel: React.FC<TrainingMetricsPanelProps> = (props) => (
  <TrainingMetricsPanelContent key={props.runId || ''} {...props} />
);

export default TrainingMetricsPanel;
