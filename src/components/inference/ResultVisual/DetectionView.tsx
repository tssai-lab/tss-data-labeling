import { DownloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Space, Table, Tag, Typography } from 'antd';
import React, { useMemo, useState } from 'react';
import InputPreviewCell from './InputPreviewCell';
import MetricsCards, { pickMetrics } from './MetricsCards';
import MinioImage from './MinioImage';
import {
  formatNumber,
  getBBox,
  isFiniteNumber,
  isObjectArray,
  joinOutputObject,
} from './resolveView';
import type { ResultVisualProps } from './types';

const DetectionView: React.FC<{
  result: Record<string, unknown>;
  outputPath?: string | null;
  onDownloadObject?: ResultVisualProps['onDownloadObject'];
}> = ({ result, outputPath, onDownloadObject }) => {
  const images = isObjectArray(result.images) ? result.images : [];
  const [active, setActive] = useState(0);
  const current = images[active] || images[0];
  const detections =
    current && isObjectArray(current.detections) ? current.detections : [];

  const annotatedRel =
    current && typeof current.annotatedImage === 'string'
      ? current.annotatedImage
      : '';
  const annotatedObject = joinOutputObject(outputPath, annotatedRel);
  const labelRel =
    current && typeof current.labelFile === 'string' ? current.labelFile : '';
  const labelObject = joinOutputObject(outputPath, labelRel);

  const metrics = pickMetrics(result, [
    { key: 'totalDetections', label: '检测框数' },
    { key: 'imageCount', label: '图像数' },
  ]);

  const columns = useMemo(
    () => [
      {
        title: '类别',
        dataIndex: 'className',
        render: (v: unknown, row: Record<string, unknown>) => (
          <Tag color="purple">
            {(typeof v === 'string' && v) || `class ${row.classId}`}
          </Tag>
        ),
      },
      {
        title: '置信度',
        dataIndex: 'confidence',
        width: 110,
        render: (v: unknown) =>
          isFiniteNumber(v) ? `${(v * 100).toFixed(1)}%` : '-',
      },
      {
        title: 'BBox',
        key: 'bbox',
        render: (_: unknown, row: Record<string, unknown>) => {
          const box = getBBox(row);
          if (!box) return '-';
          return (
            <Typography.Text code style={{ fontSize: 12 }}>
              [{formatNumber(box.x1)}, {formatNumber(box.y1)},{' '}
              {formatNumber(box.x2)}, {formatNumber(box.y2)}]
            </Typography.Text>
          );
        },
      },
    ],
    [],
  );

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <MetricsCards items={metrics} />
      {images.length > 1 && (
        <Space wrap>
          {images.map((img, index) => {
            const key =
              (typeof img.annotatedImage === 'string' && img.annotatedImage) ||
              (typeof img.image === 'string' && img.image) ||
              `img-${index}`;
            return (
              <Button
                key={key}
                size="small"
                type={index === active ? 'primary' : 'default'}
                onClick={() => setActive(index)}
              >
                图像 {index + 1}
                {isObjectArray(img.detections)
                  ? `（${img.detections.length}）`
                  : ''}
              </Button>
            );
          })}
        </Space>
      )}
      <Card size="small" title="原始输入">
        {current ? (
          <Space direction="vertical" size={4}>
            <InputPreviewCell
              row={current}
              outputPath={outputPath}
              onDownloadObject={onDownloadObject}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              点击缩略图可查看大图；旧任务没有预览时会显示为不可视化。
            </Typography.Text>
          </Space>
        ) : (
          <Typography.Text type="secondary">暂无原始输入</Typography.Text>
        )}
      </Card>
      <Card
        size="small"
        title={
          <Space>
            <span>检测可视化</span>
            <Tag color="blue">{detections.length} 个目标</Tag>
          </Space>
        }
        extra={
          <Space>
            {annotatedObject && onDownloadObject && (
              <Button
                size="small"
                icon={<DownloadOutlined />}
                onClick={() =>
                  onDownloadObject(
                    annotatedObject,
                    annotatedRel.split('/').pop() || 'annotated.jpg',
                  )
                }
              >
                下载标注图
              </Button>
            )}
            {labelObject && onDownloadObject && (
              <Button
                size="small"
                icon={<DownloadOutlined />}
                onClick={() =>
                  onDownloadObject(
                    labelObject,
                    labelRel.split('/').pop() || 'labels.json',
                  )
                }
              >
                标签文件
              </Button>
            )}
          </Space>
        }
      >
        {annotatedObject ? (
          <div style={{ maxWidth: 560 }}>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
              展示推理产物标注图（含检测框与类别）。下方列表对应每个框的数值。
            </Typography.Paragraph>
            <MinioImage objectName={annotatedObject} alt={annotatedRel} />
          </div>
        ) : (
          <Alert
            type="info"
            showIcon
            message="暂无标注图可预览"
            description="需要 result.images[].annotatedImage 相对 outputPath 可下载。原图若仅为 /workspace 路径则无法直接加载。"
          />
        )}
      </Card>
      <Card size="small" title="检测列表">
        <Table
          size="small"
          rowKey={(_, i) => String(i)}
          dataSource={detections}
          columns={columns}
          pagination={
            detections.length > 8 ? { pageSize: 8, size: 'small' } : false
          }
          scroll={{ x: 480 }}
        />
      </Card>
    </Space>
  );
};

export default DetectionView;
