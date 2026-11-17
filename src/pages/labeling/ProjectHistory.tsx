import { Alert, Button, Space, Table } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import { type HistoryRow, ownerApi } from '@/services/labelingOwner';
import { statusName } from '@/services/labelingWorkspace';

const historyStatus: Record<string, string> = {
  OPEN: '开放',
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  PAUSED: '暂停',
  ARCHIVED: '已归档',
  UPLOADING: '上传中',
  PROCESSING: '解析中',
  READY: '已就绪',
};

/** All history comes from persisted server pages; IDs are opaque and never chronological. */
export default function ProjectHistory({
  projectId,
  kind,
  onOpen,
  refresh = 0,
}: {
  projectId: string;
  kind: 'imports' | 'rounds' | 'ai-executions' | 'package-plans';
  onOpen: (row: HistoryRow) => void;
  refresh?: number;
}) {
  const [rows, setRows] = useState<HistoryRow[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const generation = useRef(0);
  const lock = useRef(false);
  const cursors = useRef(new Set<string>());
  const rowId = (r: HistoryRow) =>
    r.batchId || r.roundId || r.executionId || r.planId || '';
  async function load(next?: string | null) {
    if (lock.current) return;
    lock.current = true;
    const token = generation.current;
    setLoading(true);
    setError('');
    try {
      const page = await ownerApi.historyList(projectId, kind, next);
      if (token !== generation.current) return;
      if (page.items.some((r) => r.projectId !== projectId))
        throw new Error('历史不属于当前项目');
      if (
        next &&
        page.items.some((r) => rows.some((old) => rowId(old) === rowId(r)))
      )
        throw new Error('历史分页重复，请刷新');
      if (!next) cursors.current.clear();
      if (page.nextCursor && cursors.current.has(page.nextCursor))
        throw new Error('历史游标重复，请刷新');
      if (page.nextCursor) cursors.current.add(page.nextCursor);
      setRows((old) => (next ? [...old, ...page.items] : page.items));
      setCursor(page.nextCursor);
    } catch (e) {
      if (token === generation.current) setError(describeLabelingError(e));
    } finally {
      lock.current = false;
      if (token === generation.current) setLoading(false);
    }
  }
  useEffect(() => {
    ++generation.current;
    lock.current = false;
    setRows([]);
    setCursor(null);
    void load();
    return () => {
      ++generation.current;
    };
  }, [projectId, kind, refresh]);
  return (
    <>
      {error && <Alert type="error" message={error} />}
      <Space style={{ margin: '12px 0' }}>
        <Button disabled={loading} onClick={() => void load()}>
          刷新历史
        </Button>
        <span>按编号分页，创建时间见下表</span>
      </Space>
      <Table<HistoryRow>
        rowKey={rowId}
        loading={loading}
        dataSource={rows}
        pagination={false}
        scroll={{ x: 600 }}
        columns={[
          {
            title: '记录',
            render: (_, r) =>
              r.filename ||
              r.modelVersion ||
              (kind === 'rounds'
                ? r.mode === 'AI_AGENT'
                  ? 'AI 轮次'
                  : '人工轮次'
                : '分包计划'),
          },
          {
            title: '状态',
            dataIndex: 'status',
            render: (status: string) =>
              historyStatus[status] || statusName(status),
          },
          {
            title: '创建时间',
            dataIndex: 'createdAt',
            render: (v) => new Date(v).toLocaleString('zh-CN'),
          },
          {
            title: '操作',
            render: (_, r) => (
              <Button disabled={loading} onClick={() => onOpen(r)}>
                查看
              </Button>
            ),
          },
        ]}
      />
      {cursor && (
        <Button disabled={loading} onClick={() => void load(cursor)}>
          加载更多历史
        </Button>
      )}
    </>
  );
}
