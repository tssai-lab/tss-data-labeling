import { Card, Progress, Space, Table, Tag, Typography } from 'antd';
import React, { useMemo } from 'react';
import DistributionChart from './DistributionChart';
import InputPreviewCell from './InputPreviewCell';
import MetricsCards, { pickMetrics } from './MetricsCards';
import { isFiniteNumber, isObjectArray, isPlainObject } from './resolveView';
import {
  confidencePercent,
  listTextClassificationRows,
} from './textClassificationPresentation.mjs';
import type { ResultVisualProps } from './types';

function display(value: unknown) {
  if (typeof value === 'string' && value.length) return value;
  if (isFiniteNumber(value)) return String(value);
  return '-';
}

const TextClassificationView: React.FC<{
  result: Record<string, unknown>;
  outputPath?: string | null;
  onDownloadObject?: ResultVisualProps['onDownloadObject'];
}> = ({ result, outputPath, onDownloadObject }) => {
  const rows = listTextClassificationRows(result) as Record<string, unknown>[];
  const metrics = pickMetrics(result, [
    { key: 'accuracy', label: '准确率', percent: true },
    { key: 'precision', label: '精确率', percent: true },
    { key: 'recall', label: '召回率', percent: true },
    { key: 'f1', label: 'F1', percent: true },
    { key: 'sampleCount', label: '文本数' },
  ]);

  const columns = useMemo(
    () => [
      { title: '#', dataIndex: 'index', width: 56 },
      {
        title: '原始输入',
        key: 'inputPreview',
        width: 360,
        render: (_: unknown, row: Record<string, unknown>) => (
          <InputPreviewCell
            row={row}
            outputPath={outputPath}
            onDownloadObject={onDownloadObject}
          />
        ),
      },
      {
        title: '真值',
        dataIndex: 'label',
        width: 110,
        render: (value: unknown) => display(value),
      },
      {
        title: '预测',
        dataIndex: 'prediction',
        width: 110,
        render: (value: unknown) => display(value),
      },
      {
        title: '置信度',
        dataIndex: 'confidence',
        width: 150,
        render: (value: unknown) => {
          const percent = confidencePercent(value);
          return percent == null ? (
            '-'
          ) : (
            <Progress percent={percent} size="small" status="active" />
          );
        },
      },
      {
        title: '结果',
        dataIndex: 'correct',
        width: 80,
        render: (value: unknown) => {
          if (value !== true && value !== false) return '-';
          return (
            <Tag color={value ? 'success' : 'error'}>
              {value ? '正确' : '错误'}
            </Tag>
          );
        },
      },
    ],
    [onDownloadObject, outputPath],
  );

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <MetricsCards items={metrics} />
      {isPlainObject(result.labelCounts) ||
      isPlainObject(result.predictionCounts) ? (
        <DistributionChart
          title="类别分布（真值 vs 预测）"
          compare={[
            {
              name: '真值',
              data: (result.labelCounts || {}) as Record<string, unknown>,
            },
            {
              name: '预测',
              data: (result.predictionCounts || {}) as Record<string, unknown>,
            },
          ]}
        />
      ) : null}
      <Card size="small" title="预测样例（预览）">
        {rows.length === 0 ? (
          <Typography.Text type="secondary">暂无预测样例</Typography.Text>
        ) : (
          <Table
            size="small"
            rowKey={(record, index) => `${String(record.id ?? 'row')}-${index}`}
            dataSource={rows}
            columns={columns}
            pagination={
              rows.length > 8 ? { pageSize: 8, size: 'small' } : false
            }
            scroll={{ x: 920 }}
            expandable={{
              expandedRowRender: (record) => {
                const topK = isObjectArray(record.topKRecords)
                  ? record.topKRecords
                  : [];
                if (!topK.length) {
                  return (
                    <Typography.Text type="secondary">无 Top-K</Typography.Text>
                  );
                }
                return (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    {topK.map((item) => {
                      const percent = confidencePercent(item.confidence);
                      return (
                        <div key={String(item.label)}>
                          <Typography.Text>
                            {display(item.label)}
                          </Typography.Text>
                          {percent != null ? (
                            <Progress
                              percent={percent}
                              size="small"
                              style={{ maxWidth: 280, marginLeft: 8 }}
                            />
                          ) : null}
                        </div>
                      );
                    })}
                  </Space>
                );
              },
              rowExpandable: (record) => isObjectArray(record.topKRecords),
            }}
          />
        )}
      </Card>
    </Space>
  );
};

export default TextClassificationView;
