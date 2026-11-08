import { Link } from '@umijs/max';
import { Alert, Button, Card, Table } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { SubmissionSummary } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';

export default function ProjectSubmissions({
  projectId,
}: {
  projectId: string;
}) {
  const [rows, setRows] = useState<SubmissionSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const version = useRef(0);
  async function load(next?: string | null) {
    const current = ++version.current;
    setBusy(true);
    setError('');
    try {
      const page = await tasks.submissions(projectId, next);
      if (current !== version.current) return;
      setRows((old) => (next ? [...old, ...page.items] : page.items));
      setCursor(page.nextCursor);
    } catch (e) {
      if (current === version.current)
        setError(e instanceof Error ? e.message : '结果读取失败');
    } finally {
      if (current === version.current) setBusy(false);
    }
  }
  useEffect(() => {
    setRows([]);
    setCursor(null);
    void load();
    return () => {
      ++version.current;
    };
  }, [projectId]);
  return (
    <Card
      title="项目已提交结果"
      style={{ marginTop: 20 }}
      extra={
        <Button loading={busy} onClick={() => void load()}>
          刷新结果
        </Button>
      }
    >
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          style={{ marginBottom: 16 }}
        />
      )}
      <Table<SubmissionSummary>
        rowKey="submissionId"
        dataSource={rows}
        loading={busy}
        pagination={false}
        scroll={{ x: 650 }}
        columns={[
          {
            title: '提交编号',
            dataIndex: 'submissionId',
            render: (id: string) => (
              <Link to={`/labeling/submissions/${encodeURIComponent(id)}`}>
                {id}
              </Link>
            ),
          },
          { title: '标注人编号', dataIndex: 'submittedBy', ellipsis: true },
          { title: '任务包编号', dataIndex: 'packageId', ellipsis: true },
          {
            title: '提交时间',
            dataIndex: 'submittedAt',
            render: (value: string) => new Date(value).toLocaleString('zh-CN'),
          },
        ]}
      />
      {cursor && (
        <Button
          style={{ marginTop: 16 }}
          loading={busy}
          onClick={() => void load(cursor)}
        >
          加载更多结果
        </Button>
      )}
    </Card>
  );
}
