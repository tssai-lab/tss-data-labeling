import { Card, Progress, Space, Table, Tag, Typography } from 'antd';
import React, { useMemo } from 'react';
import DistributionChart from './DistributionChart';
import InputPreviewCell from './InputPreviewCell';
import MetricsCards, { pickMetrics } from './MetricsCards';
import {
  formatNumber,
  isFiniteNumber,
  isObjectArray,
  isPlainObject,
} from './resolveView';
import type { ResultVisualProps } from './types';

function formatCell(value: unknown) {
  if (isFiniteNumber(value)) return formatNumber(value);
  if (typeof value === 'string') return value;
  if (value == null) return '-';
  return String(value);
}

const ClassificationView: React.FC<{
  result: Record<string, unknown>;
  outputPath?: string | null;
  onDownloadObject?: ResultVisualProps['onDownloadObject'];
}> = ({ result, outputPath, onDownloadObject }) => {
  const rows = isObjectArray(result.predictionsPreview)
    ? result.predictionsPreview
    : isObjectArray(result.samples)
      ? result.samples
      : [];

  const metrics = pickMetrics(result, [
    { key: 'top1Accuracy', label: 'Top-1 准确率', percent: true },
    { key: 'imageCount', label: '图像数' },
    { key: 'labeledImageCount', label: '有标签数' },
  ]);

  const columns = useMemo(
    () => [
      { title: '#', dataIndex: 'index', width: 56 },
      {
        title: '原始输入',
        key: 'inputPreview',
        width: 110,
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
        ellipsis: true,
        render: (v: unknown) => formatCell(v),
      },
      {
        title: '预测',
        dataIndex: 'prediction',
        ellipsis: true,
        render: (v: unknown) => formatCell(v),
      },
      {
        title: '置信度',
        dataIndex: 'confidence',
        width: 140,
        render: (v: unknown, row: Record<string, unknown>) => {
          const score = isFiniteNumber(v)
            ? v
            : isFiniteNumber(row.score)
              ? row.score
              : null;
          return score != null ? (
            <Progress
              percent={Number((score * 100).toFixed(1))}
              size="small"
              status="active"
            />
          ) : (
            '-'
          );
        },
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
              data: result.labelCounts as Record<string, unknown>,
            },
            {
              name: '预测',
              data: result.predictionCounts as Record<string, unknown>,
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
            rowKey={(_, i) => String(i)}
            dataSource={rows}
            columns={columns}
            pagination={
              rows.length > 8 ? { pageSize: 8, size: 'small' } : false
            }
            scroll={{ x: 670 }}
            expandable={{
              expandedRowRender: (record) => {
                const topK = isObjectArray(record.topKRecords)
                  ? record.topKRecords
                  : isObjectArray(record.topK)
                    ? record.topK
                    : [];
                if (!topK.length) {
                  return (
                    <Typography.Text type="secondary">无 Top-K</Typography.Text>
                  );
                }
                return (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    {topK.map((item) => {
                      const key = `${item.labelId ?? ''}-${item.label ?? ''}-${item.confidence ?? item.score ?? ''}`;
                      const conf = isFiniteNumber(item.confidence)
                        ? item.confidence
                        : isFiniteNumber(item.score)
                          ? item.score
                          : null;
                      return (
                        <div key={key}>
                          <Typography.Text>
                            {typeof item.label === 'string'
                              ? item.label
                              : `label ${item.labelId}`}
                          </Typography.Text>
                          {conf != null ? (
                            <Progress
                              percent={Number((conf * 100).toFixed(1))}
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
              rowExpandable: (record) =>
                isObjectArray(record.topKRecords) || isObjectArray(record.topK),
            }}
          />
        )}
      </Card>
    </Space>
  );
};

export default ClassificationView;
