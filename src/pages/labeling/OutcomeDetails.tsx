import { Link } from '@umijs/max';
import { Alert, Button, Table, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError, labelingJson } from '@/services/labeling';
import type { NativeSpec } from '@/services/nativeLabeling';
import { sampleName } from './resultDisplay';

export default function OutcomeDetails({
  snapshotId,
  versions,
}: {
  snapshotId: string;
  versions: NativeSpec[];
}) {
  const [open, setOpen] = useState(false),
    [snapshot, setSnapshot] = useState<any>();
  const [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!open) return;
    const abort = new AbortController();
    setBusy(true);
    setError('');
    setSnapshot(undefined);
    void labelingJson<any>(`/final-snapshots/${snapshotId}`, {
      signal: abort.signal,
    })
      .then((value) => {
        if (!abort.signal.aborted) setSnapshot(value);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [open, snapshotId, retry]);
  return (
    <details
      onToggle={(event) => setOpen(event.currentTarget.open)}
      style={{ marginTop: 12 }}
    >
      <summary style={{ cursor: 'pointer' }}>查看所选成果明细</summary>
      <Typography.Paragraph type="secondary">
        展示保存成果时固定的结果，不随后续标注和核查变化。
      </Typography.Paragraph>
      {error && (
        <Alert
          type="error"
          message={error}
          action={<Button onClick={() => setRetry((v) => v + 1)}>重试</Button>}
        />
      )}
      <Table<any>
        rowKey="sampleId"
        size="small"
        loading={busy}
        dataSource={snapshot?.items || []}
        scroll={{ x: 680 }}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        columns={[
          {
            title: '样本',
            ellipsis: true,
            width: 280,
            render: (_, row) => (
              <span title={row.sampleId}>{sampleName(row)}</span>
            ),
          },
          {
            title: '规范',
            render: (_, row) =>
              `第 ${
                versions.find((v) => v.specVersionId === row.specVersionId)
                  ?.versionNo || '—'
              } 版`,
          },
          {
            title: '来源',
            render: (_, row) =>
              (
                ({
                  HUMAN: '人工标注',
                  AI: 'AI 标注',
                  OWNER: '需求方修订',
                  AGGREGATION: '集成结果',
                }) as Record<string, string>
              )[row.sourceKind] || '其他来源',
          },
          { title: '核查版本', dataIndex: 'reviewRevision' },
          {
            title: '来源记录',
            render: (_, row) =>
              row.submissionId ? (
                <Link to={`/labeling/submissions/${row.submissionId}`}>
                  查看提交
                </Link>
              ) : (
                <span title={row.executionId}>执行记录</span>
              ),
          },
        ]}
      />
    </details>
  );
}
