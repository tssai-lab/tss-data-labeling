import { history, useLocation } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Empty,
  Input,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError, operationKey } from '@/services/labeling';
import type { Assignment, Package } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';
import styles from './index.less';
import Navigation from './Navigation';

type Row = Partial<Package> & Partial<Assignment>;
export default function TaskList() {
  const { pathname } = useLocation();
  const mine = pathname === '/labeling/assignments';
  const { message } = App.useApp();
  const [filter, setFilter] = useState<string>();
  const [submissionId, setSubmissionId] = useState('');
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
      if (current === version.current) setError(describeLabelingError(e));
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
      .catch((e) => {
        if (current === version.current) setError(describeLabelingError(e));
      });
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
      if (current === version.current) setError(describeLabelingError(e));
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
        {mine && (
          <Card title="本人提交历史与查询">
            <Alert
              type="info"
              message="已提交领取来自真实本人任务分页；状态筛选仅针对已加载数据。"
              description="服务尚无按本人查询提交历史/由领取查 submissionId 的接口。新会话可通过已知提交编号读取真实冻结结果；本机回执不能代表跨设备历史。"
            />
            <Space wrap style={{ marginTop: 16 }}>
              <Input
                aria-label="已知提交编号"
                placeholder="输入本人已知 submissionId"
                value={submissionId}
                onChange={(e) => setSubmissionId(e.target.value)}
              />
              <Button
                disabled={!submissionId.trim()}
                onClick={() =>
                  history.push(
                    `/labeling/submissions/${encodeURIComponent(submissionId.trim())}`,
                  )
                }
              >
                读取已提交结果
              </Button>
            </Space>
            <Select
              aria-label="本人任务状态筛选"
              allowClear
              value={filter}
              onChange={setFilter}
              placeholder="筛选已加载任务状态"
              style={{ width: '100%', marginTop: 16 }}
              options={[
                'CLAIMED',
                'IN_PROGRESS',
                'SUBMITTED',
                'RELEASED',
                'REVOKED',
              ].map((value) => ({ value, label: value }))}
            />
            <Button disabled style={{ marginTop: 16 }}>
              放弃领取 · 待后端接入
            </Button>
          </Card>
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
            dataSource={
              mine && filter ? rows.filter((r) => r.status === filter) : rows
            }
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
                >
                  <Button
                    type="primary"
                    onClick={() =>
                      history.push(mine ? '/labeling/tasks' : '/labeling')
                    }
                  >
                    {mine ? '前往任务广场' : '创建或发布项目'}
                  </Button>
                </Empty>
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
                              SUBMITTED: '已交回 · 待需求方审核',
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
                          {row.status === 'SUBMITTED'
                            ? '查看已交回包'
                            : '进入标注'}
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
