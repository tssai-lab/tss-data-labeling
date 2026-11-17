import { Link } from '@umijs/max';
import { Alert, Button, Table } from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import { type SubmissionSummary, tasks } from '@/services/labelingTasks';

export default function MySubmissions() {
  const [rows, setRows] = useState<SubmissionSummary[]>([]),
    [cursor, setCursor] = useState<string | null>(null),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(false);
  async function load(next?: string | null, signal?: AbortSignal) {
    setLoading(true);
    try {
      const page = await tasks.mySubmissions(next, signal);
      if (!signal?.aborted) {
        setRows((old) => (next ? [...old, ...page.items] : page.items));
        setCursor(page.nextCursor);
        setError('');
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
  return (
    <>
      <h3>本人已提交结果</h3>
      {error && <Alert type="error" message={error} />}
      <Button disabled={loading} onClick={() => void load()}>
        刷新本人提交
      </Button>
      <Table<SubmissionSummary>
        rowKey="submissionId"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: '提交时间', dataIndex: 'submittedAt' },
          { title: '提交编号', dataIndex: 'submissionId', ellipsis: true },
          {
            title: '操作',
            render: (_, r) => (
              <Link
                to={`/labeling/submissions/${encodeURIComponent(r.submissionId)}`}
              >
                查看结果与审核结论
              </Link>
            ),
          },
        ]}
      />
      {cursor && (
        <Button disabled={loading} onClick={() => void load(cursor)}>
          更多提交
        </Button>
      )}
    </>
  );
}
