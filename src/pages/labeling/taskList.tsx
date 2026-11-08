import { history, useLocation } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Empty,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { operationKey } from '@/services/labeling';
import type { Assignment, Package } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';
import styles from './index.less';
import Navigation from './Navigation';

type Row = Partial<Package> & Partial<Assignment>;
export default function TaskList() {
  const { pathname } = useLocation();
  const mine = pathname === '/labeling/assignments';
  const { message } = App.useApp();
  const [rows, setRows] = useState<Row[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [claiming, setClaiming] = useState('');
  const [canAnnotate, setCanAnnotate] = useState(false);
  const keys = useRef(new Map<string, string>());
  const version = useRef(0);

  async function load(next?: string | null) {
    const current = version.current;
    setBusy(true);
    setError('');
    try {
      const page = mine ? await tasks.mine(next) : await tasks.pool(next);
      if (current !== version.current) return;
      setRows((old) => (next ? [...old, ...page.items] : page.items));
      setCursor(page.nextCursor);
    } catch (e) {
      if (current === version.current)
        setError(e instanceof Error ? e.message : '任务读取失败');
    } finally {
      if (current === version.current) setBusy(false);
    }
  }
  useEffect(() => {
    ++version.current;
    const current = version.current;
    setRows([]);
    setCursor(null);
    setCanAnnotate(false);
    setClaiming('');
    void tasks
      .me()
      .then((me) => {
        if (current === version.current) setCanAnnotate(me.canAnnotate);
      })
      .catch(() => {});
    void load();
    return () => {
      ++version.current;
    };
  }, [mine]);

  async function claim(id: string) {
    if (claiming) return;
    const current = version.current;
    const key = keys.current.get(id) || operationKey();
    keys.current.set(id, key);
    setClaiming(id);
    setError('');
    try {
      const result = await tasks.claim(id, key);
      if (current !== version.current) return;
      message.success('已领取，请逐张保存分类草稿后提交整包');
      history.push(
        `/labeling/assignments/${encodeURIComponent(result.assignmentId)}`,
      );
    } catch (e) {
      if (current === version.current)
        setError(e instanceof Error ? e.message : '领取失败，请刷新席位后重试');
    } finally {
      if (current === version.current) setClaiming('');
    }
  }

  return (
    <main className={styles.page}>
      <section className={styles.content}>
        <Navigation />
        <h1>{mine ? '我的标注任务' : '任务广场'}</h1>
        {error && (
          <Alert
            type="error"
            showIcon
            message={error}
            style={{ marginBottom: 20 }}
          />
        )}
        <Card
          extra={
            <Button loading={busy} onClick={() => void load()}>
              刷新
            </Button>
          }
        >
          <Table<Row>
            rowKey={(row) => row.assignmentId || row.packageId || ''}
            dataSource={rows}
            loading={busy}
            pagination={false}
            locale={{
              emptyText: (
                <Empty
                  description={
                    mine
                      ? '尚未领取任务，可前往任务广场'
                      : '暂无可领取任务，请先在项目内发布任务包'
                  }
                />
              ),
            }}
            scroll={{ x: 700 }}
            columns={
              mine
                ? [
                    {
                      title: '领取编号',
                      dataIndex: 'assignmentId',
                      ellipsis: true,
                    },
                    {
                      title: '任务包编号',
                      dataIndex: 'packageId',
                      ellipsis: true,
                    },
                    { title: '席位', dataIndex: 'slotNo' },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      render: (value: string) => (
                        <Tag color={value === 'SUBMITTED' ? 'green' : 'blue'}>
                          {(
                            {
                              CLAIMED: '已领取',
                              IN_PROGRESS: '标注中',
                              SUBMITTED: '已提交',
                            } as Record<string, string>
                          )[value] || value}
                        </Tag>
                      ),
                    },
                    {
                      title: '操作',
                      render: (_, row) => (
                        <Button
                          type="link"
                          onClick={() =>
                            history.push(
                              `/labeling/assignments/${encodeURIComponent(row.assignmentId || '')}`,
                            )
                          }
                        >
                          {row.status === 'SUBMITTED' ? '查看任务' : '进入标注'}
                        </Button>
                      ),
                    },
                  ]
                : [
                    { title: '项目', dataIndex: 'projectName' },
                    { title: '图片数', dataIndex: 'sampleCount' },
                    { title: '所需人数', dataIndex: 'requiredCount' },
                    { title: '剩余席位', dataIndex: 'remainingSlots' },
                    {
                      title: '操作',
                      render: (_, row) => (
                        <Button
                          type="primary"
                          loading={claiming === row.packageId}
                          disabled={
                            !canAnnotate ||
                            Boolean(claiming) ||
                            !row.remainingSlots
                          }
                          onClick={() => void claim(row.packageId || '')}
                        >
                          领取任务包
                        </Button>
                      ),
                    },
                  ]
            }
          />
          {cursor && (
            <Space style={{ marginTop: 16 }}>
              <Button loading={busy} onClick={() => void load(cursor)}>
                加载更多
              </Button>
            </Space>
          )}
        </Card>
      </section>
    </main>
  );
}
