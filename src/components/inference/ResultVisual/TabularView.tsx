import { Card, Progress, Space, Table, Tag, Typography } from 'antd';
import React, { useMemo } from 'react';
import MetricsCards, { pickMetrics } from './MetricsCards';
import { formatNumber, isFiniteNumber, isObjectArray } from './resolveView';

function formatCell(value: unknown) {
  if (isFiniteNumber(value)) return formatNumber(value);
  if (value == null) return '-';
  return String(value);
}

const TabularView: React.FC<{
  result: Record<string, unknown>;
}> = ({ result }) => {
  const rows = isObjectArray(result.preview)
    ? result.preview
    : isObjectArray(result.samples)
      ? result.samples
      : [];

  const metrics = pickMetrics(result, [
    { key: 'accuracy', label: '准确率', percent: true },
    { key: 'rowCount', label: '样本数' },
    { key: 'positiveCount', label: '正样本预测' },
    { key: 'negativeCount', label: '负样本预测' },
    { key: 'threshold', label: '阈值' },
    { key: 'featureCount', label: '特征数' },
  ]);

  const columns = useMemo(
    () => [
      { title: '#', dataIndex: 'index', width: 56 },
      {
        title: 'ID',
        dataIndex: 'id',
        ellipsis: true,
        render: (v: unknown, row: Record<string, unknown>) =>
          formatCell(v ?? row.sampleId),
      },
      {
        title: '概率',
        dataIndex: 'probability',
        width: 150,
        render: (v: unknown) =>
          isFiniteNumber(v) ? (
            <Progress
              percent={Number((v * 100).toFixed(1))}
              size="small"
              status="active"
            />
          ) : (
            '-'
          ),
      },
      {
        title: '预测',
        dataIndex: 'prediction',
        width: 72,
        render: (v: unknown) => formatCell(v),
      },
      {
        title: '真值',
        dataIndex: 'label',
        width: 72,
        render: (v: unknown) => formatCell(v),
      },
      {
        title: '结果',
        dataIndex: 'correct',
        width: 80,
        render: (v: unknown) => {
          const ok = v === 1 || v === true;
          const bad = v === 0 || v === false;
          if (!ok && !bad) return '-';
          return (
            <Tag color={ok ? 'success' : 'error'}>{ok ? '正确' : '错误'}</Tag>
          );
        },
      },
    ],
    [],
  );

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <MetricsCards items={metrics} />
      <Card size="small" title="预测预览">
        {rows.length === 0 ? (
          <Typography.Text type="secondary">暂无预览数据</Typography.Text>
        ) : (
          <Table
            size="small"
            rowKey={(_, i) => String(i)}
            dataSource={rows}
            columns={columns}
            pagination={
              rows.length > 10 ? { pageSize: 10, size: 'small' } : false
            }
            scroll={{ x: 520 }}
          />
        )}
      </Card>
    </Space>
  );
};

export default TabularView;
