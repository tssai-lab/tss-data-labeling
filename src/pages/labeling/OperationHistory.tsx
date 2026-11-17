import { Link } from '@umijs/max';
import { Alert, Button, Table, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import { type OperationReceipt, ownerApi } from '@/services/labelingOwner';

export default function OperationHistory() {
  const [rows, setRows] = useState<OperationReceipt[]>([]),
    [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(false);
  async function load(next?: string | null, signal?: AbortSignal) {
    setLoading(true);
    setError('');
    try {
      const page = await ownerApi.receipts(next, signal);
      if (!signal?.aborted) {
        setRows((old) => (next ? [...old, ...page.items] : page.items));
        setCursor(page.nextCursor);
      }
    } catch (e) {
      if (!signal?.aborted) setError(describeLabelingError(e));
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }
  useEffect(() => {
    const abort = new AbortController();
    void load(null, abort.signal);
    return () => abort.abort();
  }, []);
  const label = (r: OperationReceipt) =>
    r.operation.split('/').at(-1) || r.operation;
  return (
    <>
      <p>
        服务器保存的本人操作回执；可复制成果、导出、执行或集成编号恢复查询。仅显示已持久保存响应的操作。
      </p>
      {error && <Alert type="error" message={error} />}
      <Button disabled={loading} onClick={() => void load()}>
        刷新操作记录
      </Button>
      <Table<OperationReceipt>
        rowKey={(r) => `${r.operation}:${r.idempotencyKey}`}
        loading={loading}
        dataSource={rows}
        pagination={false}
        scroll={{ x: 600 }}
        columns={[
          { title: '操作', render: (_, r) => label(r) },
          {
            title: '资源编号',
            render: (_, r) => (
              <Typography.Text copyable>{r.resourceId}</Typography.Text>
            ),
          },
          { title: '时间', dataIndex: 'createdAt' },
          {
            title: '查看',
            render: (_, r) =>
              typeof r.response.submissionId === 'string' ? (
                <Link
                  to={`/labeling/submissions/${encodeURIComponent(r.response.submissionId)}`}
                >
                  提交详情
                </Link>
              ) : (
                <details>
                  <summary>操作回执</summary>
                  <pre
                    style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
                  >
                    {JSON.stringify(r.response, null, 2)}
                  </pre>
                </details>
              ),
          },
        ]}
      />
      {cursor && (
        <Button disabled={loading} onClick={() => void load(cursor)}>
          更多操作记录
        </Button>
      )}
    </>
  );
}
