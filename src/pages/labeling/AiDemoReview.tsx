import { Button, Card, Input, Progress, Space, Table, Tag, Typography } from 'antd';
import { useState } from 'react';
import type { DemoResult } from './NativeAi';
import ui from './workspaceUi.less';

export default function AiDemoReview({ results, onChange }: {
  results: DemoResult[];
  onChange: (results: DemoResult[]) => void;
}) {
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const [lower, setLower] = useState('0.5');
  const [upper, setUpper] = useState('0.8');
  const acceptedCount = results.filter((row) => row.status === '已确认').length;
  const selectedResults = results.filter((row) => selectedIds.includes(row.sample.sampleId) && row.status === '待核查');
  function markSelected(status: '已确认' | '需重标'): void {
    if (status === '需重标' && !reason.trim()) return;
    onChange(results.map((row) => selectedIds.includes(row.sample.sampleId) && row.status === '待核查'
      ? { ...row, status, reason: status === '需重标' ? reason.trim() : undefined } : row));
    setSelectedIds([]);
    setReason('');
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
      extra={`演示模型 · ${acceptedCount} 个已确认`}
    >
      <Progress percent={100} status="normal" />
      <Space wrap>
        <Input
          aria-label="标记需重标的原因"
          placeholder="标记需重标时填写原因"
          value={reason}
          maxLength={300}
          onChange={(event) => setReason(event.target.value)}
          disabled={!selectedResults.length}
        />
        <Button
          type="primary"
          disabled={!selectedResults.length}
          onClick={() => markSelected('已确认')}
        >
          确认选中结果
        </Button>
        <Button
          disabled={!selectedResults.length || !reason.trim()}
          onClick={() => markSelected('需重标')}
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
            disabled: row.status !== '待核查',
          }),
        }}
        columns={[
          {
            title: '样本',
            render: (_, row) => row.sample.metadata.displayName || row.sample.sourcePath,
            ellipsis: true,
          },
          {
            title: 'AI 候选标签',
            dataIndex: 'label',
            render: (value: string) => <strong className={ui.aiDemoLabel}>{value}</strong>,
          },
          {
            title: '置信度',
            dataIndex: 'confidence',
            render: (value: number | null) => value == null
              ? '—'
              : <span className={ui.aiDemoConfidence}>{(value * 100).toFixed(1)}%</span>,
          },
          {
            title: '置信度分档',
            render: (_, row) => {
              const band = bandOf(row.confidence);
              return band === '—' ? band : <Tag color={band === '高' ? 'green' : band === '中' ? 'gold' : 'orange'}>{band}</Tag>;
            },
          },
          {
            title: '核查状态',
            dataIndex: 'status',
            render: (value: DemoResult['status']) =>
              value === '已确认' ? <Tag color="green">已确认</Tag> :
              value === '需重标' ? <Tag color="orange">需重标</Tag> :
              value === '执行失败' ? <Tag color="red">执行失败</Tag> :
              <Tag color="blue">待核查</Tag>,
          },
        ]}
      />
      <details>
        <summary>置信度分档阈值（演示设置）</summary>
        <Space wrap style={{ marginTop: 12 }}>
          <label>
            低阈值{' '}
            <Input
              aria-label="演示低置信度阈值"
              value={lower}
              onChange={(event) => setLower(event.target.value)}
              style={{ width: 90 }}
            />
          </label>
          <label>
            高阈值{' '}
            <Input
              aria-label="演示高置信度阈值"
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
