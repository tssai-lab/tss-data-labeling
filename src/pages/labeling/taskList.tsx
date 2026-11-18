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
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError, operationKey } from '@/services/labeling';
import type { Assignment, Package } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';
import MySubmissions from './MySubmissions';
import OperationHistory from './OperationHistory';
import StatusBadge from './StatusBadge';
import useOwnerAction from './useOwnerAction';
import WorkspaceLayout from './WorkspaceLayout';
import ui from './workspaceUi.less';

type Row = Partial<Package> & Partial<Assignment>;
export default function TaskList() {
  const { pathname } = useLocation();
  const mine = pathname === '/labeling/assignments';
  const { message, modal } = App.useApp();
  const releaseAction = useOwnerAction();
  const [releaseReason, setReleaseReason] = useState('');
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
        <details>
          <summary>本人提交与操作记录</summary>
          <MySubmissions />
          <OperationHistory />
        </details>
      )}
      {(error || releaseAction.error) && (
        <Alert
          type="error"
          showIcon
          message={error || releaseAction.error}
          style={{ marginBottom: 20 }}
        />
      )}
      {mine && (
        <Card title="任务筛选与提交查询">
          <Alert
            type="info"
            message="状态筛选只作用于已加载的任务"
            description="展开上方“本人提交与操作记录”可查看提交历史；也可在这里输入提交编号直接查看结果。"
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
          <Input.TextArea
            aria-label="放弃领取原因"
            placeholder="放弃领取前填写原因；已交回的领取不能放弃"
            maxLength={1000}
            value={releaseReason}
            onChange={(event) => setReleaseReason(event.target.value)}
            disabled={releaseAction.busy}
            style={{ marginTop: 16 }}
          />
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
                            disabled={
                              releaseAction.busy ||
                              busy ||
                              !releaseReason.trim()
                            }
                            onClick={() =>
                              modal.confirm({
                                title: '放弃此领取？',
                                content:
                                  '服务器保存的草稿保留在本次领取历史中；席位将释放，后续领取不会自动沿用本次草稿。',
                                onOk: async () => {
                                  if (!row.assignmentId || !row.revision)
                                    return;
                                  const current = version.current;
                                  const body = {
                                    baseRevision: row.revision,
                                    reason: releaseReason.trim(),
                                  };
                                  const value = await releaseAction.run(
                                    `release:${row.assignmentId}`,
                                    body,
                                    (key) =>
                                      tasks.release(
                                        row.assignmentId || '',
                                        body.baseRevision,
                                        body.reason,
                                        key,
                                      ),
                                    '已放弃领取',
                                  );
                                  if (value && current === version.current) {
                                    keys.current.delete(
                                      row.roundId || row.packageId || '',
                                    );
                                    void load();
                                  }
                                },
                              })
                            }
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
    </WorkspaceLayout>
  );
}
