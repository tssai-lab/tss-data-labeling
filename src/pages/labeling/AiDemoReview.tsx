import {
  App,
  Button,
  Card,
  Image,
  Input,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useId, useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import type { NativeSample } from '@/services/nativeLabeling';
import type { DemoResult } from './NativeAi';
import { aiSampleName, useAiSampleImage } from './useAiSampleImage';
import ui from './workspaceUi.less';

function ReviewImage({
  projectId,
  sample,
}: {
  projectId: string;
  sample: NativeSample;
}) {
  const image = useAiSampleImage(
    projectId,
    sample.sampleId,
    sample.mediaType,
    true,
  );
  const name = image.fileName || aiSampleName(sample);
  return (
    <Space>
      {image.url ? (
        <Image
          src={image.url}
          alt={name}
          width={64}
          height={48}
          style={{ objectFit: 'contain' }}
        />
      ) : (
        <Typography.Text type="secondary">
          {image.loading ? '加载中…' : '原图不可用'}
        </Typography.Text>
      )}
      <Typography.Text title={name}>{name}</Typography.Text>
    </Space>
  );
}

export default function AiDemoReview({
  projectId,
  showImages,
  results,
  onChange,
  readOnly = false,
}: {
  projectId: string;
  showImages: boolean;
  results: DemoResult[];
  onChange: (results: DemoResult[]) => Promise<void>;
  readOnly?: boolean;
}) {
  const { message } = App.useApp();
  const thresholdId = useId();
  const [saving, setSaving] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const [lower, setLower] = useState('0.5');
  const [upper, setUpper] = useState('0.8');
  const acceptedCount = results.filter((row) => row.status === '已确认').length;
  const selectedResults = results.filter(
    (row) =>
      selectedIds.includes(row.sample.sampleId) &&
      row.status === '待核查' &&
      row.reviewable,
  );
  async function markSelected(status: '已确认' | '需重标') {
    if (readOnly || saving || (status === '需重标' && !reason.trim())) return;
    const ids = new Set(selectedResults.map((row) => row.sample.sampleId));
    setSaving(true);
    try {
      await onChange(
        results.map((row) =>
          ids.has(row.sample.sampleId)
            ? {
                ...row,
                status,
                reason: status === '需重标' ? reason.trim() : undefined,
              }
            : row,
        ),
      );
      setSelectedIds([]);
      setReason('');
    } catch (error) {
      message.error(describeLabelingError(error));
    } finally {
      setSaving(false);
    }
  }
  function bandOf(confidence: number | null): string {
    if (confidence == null) return '—';
    if (confidence >= Number(upper)) return '高';
    if (confidence >= Number(lower)) return '中';
    return '低';
  }
  return (
    <Card
      className={`${ui.aiExecutionCard} ${ui.aiDemoResultsCard}`}
      title="候选结果核查"
      extra={`模型结果 · ${acceptedCount} 个已确认`}
    >
      <Typography.Paragraph type="secondary">
        已读取 {results.length} 条候选，{selectedResults.length}{' '}
        条待处理结果已勾选。
        {readOnly && '当前项目只读，不能修改核查结果。'}
      </Typography.Paragraph>
      <Space wrap>
        <Input
          aria-label="标记需重标的原因"
          placeholder="标记需重标时填写原因"
          value={reason}
          maxLength={300}
          onChange={(event) => setReason(event.target.value)}
          disabled={readOnly || saving || !selectedResults.length}
        />
        <Button
          type="primary"
          disabled={readOnly || saving || !selectedResults.length}
          onClick={() => void markSelected('已确认')}
        >
          确认选中结果
        </Button>
        <Button
          disabled={
            readOnly || saving || !selectedResults.length || !reason.trim()
          }
          onClick={() => void markSelected('需重标')}
        >
          标记需重标
        </Button>
      </Space>
      <Table<DemoResult>
        rowKey={(row) => row.sample.sampleId}
        size="small"
        dataSource={results}
        pagination={{ pageSize: 8, showSizeChanger: false }}
        scroll={{ x: 700 }}
        rowSelection={{
          selectedRowKeys: selectedIds,
          onChange: (keys) => setSelectedIds(keys.map(String)),
          getCheckboxProps: (row) => ({
            disabled:
              readOnly || saving || !row.reviewable || row.status !== '待核查',
          }),
        }}
        columns={[
          {
            title: '样本',
            render: (_, row) =>
              showImages ? (
                <ReviewImage projectId={projectId} sample={row.sample} />
              ) : (
                aiSampleName(row.sample)
              ),
            ellipsis: true,
          },
          {
            title: 'AI 候选标签',
            dataIndex: 'label',
            render: (value: string, row) => (
              <Space direction="vertical" size={2}>
                <strong className={ui.aiDemoLabel}>{value}</strong>
                {row.rationale && (
                  <details>
                    <summary>判断依据</summary>
                    <Typography.Text type="secondary">
                      {row.rationale}
                    </Typography.Text>
                  </details>
                )}
              </Space>
            ),
          },
          {
            title: '置信度',
            dataIndex: 'confidence',
            render: (value: number | null) =>
              value == null ? (
                '—'
              ) : (
                <span className={ui.aiDemoConfidence}>
                  {(value * 100).toFixed(1)}%
                </span>
              ),
          },
          {
            title: '置信度分档',
            render: (_, row) => {
              const band = bandOf(row.confidence);
              return band === '—' ? (
                band
              ) : (
                <Tag
                  color={
                    band === '高' ? 'green' : band === '中' ? 'gold' : 'orange'
                  }
                >
                  {band}
                </Tag>
              );
            },
          },
          {
            title: '核查状态',
            dataIndex: 'status',
            render: (value: DemoResult['status']) =>
              value === '已确认' ? (
                <Tag color="green">已确认</Tag>
              ) : value === '需重标' ? (
                <Tag color="orange">需重标</Tag>
              ) : value === '执行失败' ? (
                <Tag color="red">执行失败</Tag>
              ) : (
                <Tag color="blue">待核查</Tag>
              ),
          },
        ]}
      />
      <details>
        <summary>置信度分档阈值</summary>
        <Space wrap style={{ marginTop: 12 }}>
          <label htmlFor={`${thresholdId}-lower`}>
            低阈值{' '}
            <Input
              id={`${thresholdId}-lower`}
              aria-label="低置信度阈值"
              value={lower}
              onChange={(event) => setLower(event.target.value)}
              style={{ width: 90 }}
            />
          </label>
          <label htmlFor={`${thresholdId}-upper`}>
            高阈值{' '}
            <Input
              id={`${thresholdId}-upper`}
              aria-label="高置信度阈值"
              value={upper}
              onChange={(event) => setUpper(event.target.value)}
              style={{ width: 90 }}
            />
          </label>
          <Typography.Text type="secondary">
            阈值只改变本页分档显示，不会自动确认结果。
          </Typography.Text>
        </Space>
      </details>
    </Card>
  );
}
