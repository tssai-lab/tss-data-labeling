import { Alert, Button, Table } from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError, labelingJson } from '@/services/labeling';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import { sampleName } from './resultDisplay';

export default function ReviewAudit({
  submissionId,
  revision,
}: {
  submissionId: string;
  revision: number;
}) {
  const [open, setOpen] = useState(false),
    [rows, setRows] = useState<any[]>([]);
  const [cursor, setCursor] = useState<string | null>(null),
    [page, setPage] = useState<string>();
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    setRows([]);
    setCursor(null);
    setPage(undefined);
    setError('');
  }, [submissionId, revision]);
  useEffect(() => {
    if (!open) return;
    const abort = new AbortController();
    setBusy(true);
    setError('');
    const query = new URLSearchParams({
      limit: '20',
      ...(page ? { cursor: page } : {}),
    });
    void labelingJson<any>(
      `/submissions/${submissionId}/review/audit?${query}`,
      { signal: abort.signal },
    )
      .then((value) => {
        if (!abort.signal.aborted) {
          setRows((current) =>
            page
              ? Array.from(
                  new Map(
                    [...current, ...value.items].map((row) => [
                      row.auditId,
                      row,
                    ]),
                  ).values(),
                )
              : value.items,
          );
          setCursor(value.nextCursor);
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [open, submissionId, revision, page, retry]);
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary style={{ cursor: 'pointer' }}>核查操作记录</summary>
      {error && (
        <Alert
          type="error"
          message={error}
          action={<Button onClick={() => setRetry((v) => v + 1)}>重试</Button>}
        />
      )}
      <Table<any>
        rowKey="auditId"
        size="small"
        loading={busy}
        dataSource={rows}
        pagination={false}
        scroll={{ x: 680 }}
        columns={[
          {
            title: '样本',
            render: (_, row) => (
              <span title={row.sampleId}>{sampleName(row)}</span>
            ),
          },
          {
            title: '操作人',
            render: (_, row) => (
              <span title={row.actorId}>{row.actorName || '未知用户'}</span>
            ),
          },
          {
            title: '操作',
            render: (_, row) =>
              (
                ({
                  REVIEW_ITEM: '样本核查',
                  REVIEW_DECIDE: '整包核查',
                }) as Record<string, string>
              )[row.action] || '核查操作',
          },
          {
            title: '时间',
            dataIndex: 'createdAt',
            render: formatDisplayDateTime,
          },
          {
            title: '说明',
            dataIndex: 'reason',
            render: (value) => value || '—',
          },
        ]}
        expandable={{
          expandedRowRender: (row) => (
            <div style={{ overflowWrap: 'anywhere' }}>
              <p>操作前结果</p>
              <pre style={{ whiteSpace: 'pre-wrap' }}>
                {JSON.stringify(row.beforeResult, null, 2) || '无'}
              </pre>
              <p>操作后结果</p>
              <pre style={{ whiteSpace: 'pre-wrap' }}>
                {JSON.stringify(row.afterResult, null, 2) || '无'}
              </pre>
            </div>
          ),
        }}
      />
      {cursor && (
        <Button disabled={busy || !!error} onClick={() => setPage(cursor)}>
          加载更多记录
        </Button>
      )}
    </details>
  );
}
