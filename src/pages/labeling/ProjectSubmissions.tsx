import { Link } from '@umijs/max';
import { Alert, Button, Card, Select, Space, Table, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import type { Plan, SubmissionSummary } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';

export default function ProjectSubmissions({
  projectId,
}: {
  projectId: string;
}) {
  const [packageFilter, setPackageFilter] = useState<string>();
  const [actorFilter, setActorFilter] = useState<string>();
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loaded, setLoaded] = useState(false);
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
      setLoaded(true);
    } catch (e) {
      if (current === version.current) setError(describeLabelingError(e));
    } finally {
      if (current === version.current) setBusy(false);
    }
  }
  useEffect(() => {
    setRows([]);
    setLoaded(false);
    setPackageFilter(undefined);
    setActorFilter(undefined);
    setCursor(null);
    void load();
    return () => {
      ++version.current;
    };
  }, [projectId]);
  useEffect(() => {
    let disposed = false;
    setPlans([]);
    void tasks
      .me()
      .then(async () => {
        const prefix = `labeling:plan:${projectId}:`;
        const ids = Object.keys(localStorage)
          .filter((k) => k.startsWith(prefix))
          .map((k) => localStorage.getItem(k))
          .filter((id): id is string => Boolean(id));
        const results = await Promise.allSettled(
          ids.map((id) => tasks.getPlan(id)),
        );
        if (disposed) return;
        setPlans(
          results.flatMap((r) => (r.status === 'fulfilled' ? [r.value] : [])),
        );
        const failure = results.find((r) => r.status === 'rejected');
        if (failure?.status === 'rejected')
          setError(describeLabelingError(failure.reason));
      })
      .catch((e) => {
        if (!disposed) setError(describeLabelingError(e));
      });
    return () => {
      disposed = true;
    };
  }, [projectId, rows]);
  const filtered = rows.filter(
    (r) =>
      (!packageFilter || r.packageId === packageFilter) &&
      (!actorFilter || r.submittedBy === actorFilter),
  );
  const packages = plans.flatMap((p) => p.packages);
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
      <Typography.Paragraph type="secondary">
        已读取 {rows.length} 份提交；
        {cursor
          ? '仍有后续页，筛选仅针对当前已加载数据。'
          : loaded
            ? '已读完此次查询分页。'
            : '正在读取。'}{' '}
        全项目分母未知；下方进度仅限本机已知并经真实查询确认的包。
      </Typography.Paragraph>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          aria-label="按任务包筛选已加载结果"
          allowClear
          placeholder="任务包（已加载）"
          style={{ width: 240, maxWidth: '100%' }}
          value={packageFilter}
          onChange={setPackageFilter}
          options={[...new Set(rows.map((r) => r.packageId))].map((value) => ({
            value,
            label: value,
          }))}
        />
        <Select
          aria-label="按标注人筛选已加载结果"
          allowClear
          placeholder="标注人（真实 ID）"
          style={{ width: 240, maxWidth: '100%' }}
          value={actorFilter}
          onChange={setActorFilter}
          options={[...new Set(rows.map((r) => r.submittedBy))].map(
            (value) => ({ value, label: value }),
          )}
        />
      </Space>
      {packages.length > 0 && (
        <Table
          rowKey="packageId"
          dataSource={packages}
          pagination={false}
          scroll={{ x: 650 }}
          columns={[
            { title: '已知任务包', dataIndex: 'packageId', ellipsis: true },
            { title: '所需人数', dataIndex: 'requiredCount' },
            {
              title: '已提交人数',
              render: (_, p) =>
                `${new Set(rows.filter((r) => r.packageId === p.packageId).map((r) => r.submittedBy)).size}${cursor || !loaded ? '（已加载，非全量）' : ''}`,
            },
          ]}
        />
      )}
      <Table<SubmissionSummary>
        rowKey="submissionId"
        dataSource={filtered}
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
