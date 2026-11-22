import { history, useLocation } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError, operationKey } from '@/services/labeling';
import type { Assignment, Package } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import MySubmissions from './MySubmissions';
import StatusBadge from './StatusBadge';
import useOwnerAction from './useOwnerAction';
import WorkspaceLayout from './WorkspaceLayout';
import ui from './workspaceUi.less';

type Row = Partial<Package> & Partial<Assignment>;
export default function TaskList() {
  const { pathname, search } = useLocation();
  const mine = pathname === '/labeling/assignments';
  const { message } = App.useApp();
  const releaseAction = useOwnerAction();
  const [releaseReason, setReleaseReason] = useState('');
  const [releaseTarget, setReleaseTarget] = useState<Row | null>(null);
  const [filter, setFilter] = useState<string>();
  const [mineView, setMineView] = useState('tasks');
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
    const requestedView = new URLSearchParams(search).get('view');
    setMineView(requestedView === 'submissions' ? requestedView : 'tasks');
    const current = version.current;
    setRows([]);
    setCursor(null);
    setCanAnnotate(false);
    setClaiming('');
    void tasks
      .me()
      .then((me) => {
        if (current === version.current) {
          setCanAnnotate(me.canAnnotate);
        }
      })
      .catch((e) => {
        if (current === version.current) setError(describeLabelingError(e));
      });
    void load();
    return () => {
      ++version.current;
    };
  }, [mine, search]);

  async function claim(row: Row) {
    if (claiming) return;
    const id = row.roundId || row.packageId || '';
    const current = version.current;
    const key = keys.current.get(id) || operationKey();
    keys.current.set(id, key);
    setClaiming(id);
    setError('');
    try {
      const result = row.roundId
        ? await tasks.claimRound(row.roundId, key)
        : await tasks.claim(id, key);
      if (current !== version.current) return;
      message.success('已领取，请逐样本保存草稿后提交整包');
      history.push(
        `/labeling/assignments/${encodeURIComponent(result.assignmentId)}`,
      );
    } catch (e) {
      if (current === version.current) setError(describeLabelingError(e));
    } finally {
      if (current === version.current) setClaiming('');
    }
  }

  async function releaseAssignment(): Promise<void> {
    const row = releaseTarget;
    if (
      !row?.assignmentId ||
      typeof row.revision !== 'number' ||
      !releaseReason.trim()
    )
      return;
    const current = version.current;
    const assignmentId = row.assignmentId;
    const body = { baseRevision: row.revision, reason: releaseReason.trim() };
    const value = await releaseAction.run(
      `release:${assignmentId}`,
      body,
      (key) => tasks.release(assignmentId, body.baseRevision, body.reason, key),
      '已放弃领取',
    );
    if (value && current === version.current) {
      keys.current.delete(row.roundId || row.packageId || '');
      setReleaseTarget(null);
      setReleaseReason('');
      void load();
    }
  }

  return (
    <WorkspaceLayout>
      <header className={ui.pageHeader}>
        <div>
          <h1>{mine ? '我的标注任务' : '任务广场'}</h1>
          <p>
            {mine
              ? '继续已领取任务，保存逐样本草稿后完整提交任务包。'
              : '查看开放任务包，核对项目与工作量后领取。'}
          </p>
        </div>
      </header>
      {mine && (
        <Tabs
          className={ui.taskSectionTabs}
          activeKey={mineView}
          onChange={setMineView}
          items={[
            { key: 'tasks', label: '领取任务' },
            { key: 'submissions', label: '提交记录' },
          ]}
        />
      )}
      {(error || releaseAction.error) && (
        <Alert
          type="error"
          showIcon
          message={error || releaseAction.error}
          style={{ marginBottom: 20 }}
        />
      )}
      {(!mine || mineView === 'tasks') && (
        <Card
          title={mine ? '已领取任务' : '可领取任务'}
          extra={
            <Button loading={busy} onClick={() => void load()}>
              刷新
            </Button>
          }
        >
          {mine && (
            <Space className={ui.taskListToolbar} wrap>
              <Select
                aria-label="本人任务状态筛选"
                allowClear
                value={filter}
                onChange={setFilter}
                placeholder="全部任务状态"
                style={{ width: 180 }}
                options={[
                  'CLAIMED',
                  'IN_PROGRESS',
                  'SUBMITTED',
                  'RELEASED',
                  'REVOKED',
                ].map((value) => ({
                  value,
                  label: (
                    {
                      CLAIMED: '已领取',
                      IN_PROGRESS: '标注中',
                      SUBMITTED: '已交回',
                      RELEASED: '已放弃',
                      REVOKED: '已撤销',
                    } as Record<string, string>
                  )[value],
                }))}
              />
              <Input.Search
                aria-label="已知提交编号"
                placeholder="输入提交编号查看结果"
                value={submissionId}
                onChange={(event) => setSubmissionId(event.target.value)}
                onSearch={(value) => {
                  if (value.trim())
                    history.push(
                      `/labeling/submissions/${encodeURIComponent(value.trim())}`,
                    );
                }}
                enterButton="查看提交"
                style={{ width: 380, maxWidth: '100%' }}
              />
              <Typography.Text type="secondary">
                状态筛选只作用于当前已加载的任务
              </Typography.Text>
            </Space>
          )}
          <Table<Row>
            rowKey={(row) =>
              row.assignmentId || row.roundId || row.packageId || ''
            }
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
                      ? filter
                        ? '当前已加载任务中没有符合该状态的任务'
                        : '尚未领取任务，可前往任务广场'
                      : '暂无可领取任务，请先在项目内发布任务包'
                  }
                >
                  {mine && filter ? (
                    <Button onClick={() => setFilter(undefined)}>
                      清除状态筛选
                    </Button>
                  ) : (
                    <Button
                      type="primary"
                      onClick={() =>
                        history.push(mine ? '/labeling/tasks' : '/labeling')
                      }
                    >
                      {mine ? '前往任务广场' : '创建或发布项目'}
                    </Button>
                  )}
                </Empty>
              ),
            }}
            scroll={{ x: 700 }}
            columns={
              mine
                ? [
                    {
                      title: '任务名称',
                      dataIndex: 'projectName',
                      ellipsis: true,
                      render: (value: string | undefined) =>
                        value || '未命名任务',
                    },
                    {
                      title: '领取时间',
                      dataIndex: 'claimedAt',
                      width: 180,
                      render: formatDisplayDateTime,
                    },
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
                    { title: '轮次', dataIndex: 'roundId', ellipsis: true },
                    { title: '席位', dataIndex: 'slotNo' },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      render: (value: string) => (
                        <StatusBadge status={value}>
                          {(
                            {
                              CLAIMED: '已领取',
                              IN_PROGRESS: '标注中',
                              SUBMITTED: '已交回 · 待需求方审核',
                            } as Record<string, string>
                          )[value] || value}
                        </StatusBadge>
                      ),
                    },
                    {
                      title: '操作',
                      render: (_, row) => (
                        <Space wrap>
                          <Button
                            type="link"
                            disabled={
                              releaseAction.busy ||
                              ['RELEASED', 'REVOKED'].includes(row.status || '')
                            }
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
                          {['CLAIMED', 'IN_PROGRESS'].includes(
                            row.status || '',
                          ) && (
                            <Button
                              danger
                              type="link"
                              disabled={releaseAction.busy || busy}
                              onClick={() => {
                                setReleaseReason('');
                                setReleaseTarget(row);
                              }}
                            >
                              放弃领取
                            </Button>
                          )}
                        </Space>
                      ),
                    },
                  ]
                : [
                    { title: '项目', dataIndex: 'projectName' },
                    {
                      title: '发布时间',
                      dataIndex: 'publishedAt',
                      width: 180,
                      render: formatDisplayDateTime,
                    },
                    { title: '图片数', dataIndex: 'sampleCount' },
                    { title: '所需人数', dataIndex: 'requiredCount' },
                    { title: '剩余席位', dataIndex: 'remainingSlots' },
                    {
                      title: '操作',
                      render: (_, row) => (
                        <Button
                          type="primary"
                          loading={claiming === (row.roundId || row.packageId)}
                          disabled={
                            !canAnnotate ||
                            Boolean(claiming) ||
                            !row.remainingSlots
                          }
                          onClick={() => void claim(row)}
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
      )}
      {mine && mineView === 'submissions' && (
        <Card>
          <MySubmissions />
        </Card>
      )}
      <Modal
        title="放弃领取"
        open={!!releaseTarget}
        onCancel={() => setReleaseTarget(null)}
        okText="确认放弃"
        cancelText="继续标注"
        confirmLoading={releaseAction.busy}
        okButtonProps={{ disabled: !releaseReason.trim() }}
        onOk={() => void releaseAssignment()}
      >
        <Typography.Paragraph>
          放弃后会释放席位，已保存的草稿保留在本次领取记录中。请填写原因。
        </Typography.Paragraph>
        <Input.TextArea
          aria-label="放弃领取原因"
          placeholder="填写放弃原因"
          value={releaseReason}
          maxLength={1000}
          showCount
          autoSize={{ minRows: 3, maxRows: 6 }}
          onChange={(event) => setReleaseReason(event.target.value)}
        />
      </Modal>
    </WorkspaceLayout>
  );
}
