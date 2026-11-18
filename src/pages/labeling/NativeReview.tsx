import {
  Alert,
  Button,
  Card,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  operationKey,
  post,
} from '@/services/labeling';
import { categoryOf, type NativeProject } from '@/services/nativeLabeling';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import NativeEditor from './NativeEditor';
import type { ReworkScope } from './NativePublish';
import ReviewAudit from './ReviewAudit';
import { reviewStatusName, sampleName } from './resultDisplay';
import SubmissionIdentity from './SubmissionIdentity';

export default function NativeReview({
  project,
  submissions,
  run: execute,
  refresh,
  rework,
  busy,
  onGuard,
}: {
  project: NativeProject;
  submissions: any[];
  run: (action: () => Promise<void>) => Promise<void>;
  refresh: () => void;
  rework: (scope: ReworkScope) => void;
  busy: boolean;
  onGuard: (guard: () => boolean) => void;
}) {
  const [submissionId, setSubmissionId] = useState(''),
    [submission, setSubmission] = useState<any>(),
    [review, setReview] = useState<any>(),
    [sampleId, setSampleId] = useState(''),
    [reason, setReason] = useState(''),
    [editing, setEditing] = useState(false),
    [error, setError] = useState(''),
    [reload, setReload] = useState(0);
  const guard = useRef<() => boolean>(() => true);
  const run = (action: () => Promise<void>) =>
    guard.current() ? execute(action) : Promise.resolve();
  const selected = submissionId || submissions[0]?.submissionId;
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  useEffect(() => {
    if (!selected) return;
    const abort = new AbortController();
    setError('');
    setSubmission(undefined);
    setEditing(false);
    void Promise.all([
      labelingJson<any>(`/submissions/${selected}`, { signal: abort.signal }),
      labelingJson<any>(`/submissions/${selected}/review`, {
        signal: abort.signal,
      }),
    ])
      .then(([s, r]) => {
        if (!abort.signal.aborted) {
          setSubmission(s);
          setReview(r);
          setSampleId(s.items[0]?.sampleId || '');
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [selected, reload]);
  const item = submission?.items.find(
    (value: any) => value.sampleId === sampleId,
  );
  const reviewed = review?.items.find(
    (value: any) => value.sampleId === sampleId,
  );
  const version = project.specVersions.find(
    (value) => value.specVersionId === submission?.specVersionId,
  );
  const result = reviewed?.action === 'MODIFY' ? reviewed.result : item?.result;
  const update = async (action: string, items: any[], values?: unknown[]) => {
    await labelingJson(`/submissions/${selected}/review/items`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': operationKey(),
      },
      body: JSON.stringify({
        items: items.map((row) => ({
          sampleId: row.sampleId,
          action,
          reason,
          ...(values ? { result: { result: values } } : {}),
        })),
      }),
    });
    setEditing(false);
    setReload((v) => v + 1);
    refresh();
  };
  return (
    <Card title="需求方核查">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {error && <Alert type="error" message={error} />}
        {!submissions.length ? (
          <Alert message="尚无交回结果。可前往任务广场领取并标注，任何启用的账号都可以参与。" />
        ) : (
          <>
            <Select
              aria-label="核查提交"
              style={{ width: '100%' }}
              value={selected}
              onChange={(id) => {
                if (guard.current()) {
                  setSubmissionId(id);
                  setEditing(false);
                }
              }}
              options={submissions.map((row) => ({
                value: row.submissionId,
                label: `${row.submittedByName || '未知标注人'} · ${formatDisplayDateTime(row.submittedAt)} · ${reviewStatusName(row.reviewStatus)}`,
                title: `提交编号：${row.submissionId}`,
              }))}
            />
            {!submission ? (
              <Spin />
            ) : (
              <>
                <SubmissionIdentity submission={submission} />
                <Input.TextArea
                  aria-label="核查说明"
                  maxLength={500}
                  value={reason}
                  disabled={locked}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="退回或修订说明"
                />
                <Space wrap>
                  <Button
                    disabled={locked || busy}
                    onClick={() =>
                      void run(async () => {
                        await post(
                          `/submissions/${selected}/review/decide`,
                          { action: 'ACCEPT', reason },
                          operationKey(),
                        );
                        setReload((v) => v + 1);
                        refresh();
                      })
                    }
                  >
                    确认整包结果
                  </Button>
                  <Button
                    danger
                    disabled={locked || busy}
                    onClick={() =>
                      void run(async () => {
                        if (!reason.trim()) throw Error('请填写退回说明');
                        await update('REJECT', submission.items);
                        rework({
                          ids: submission.items.map((row: any) => row.sampleId),
                          references: Object.fromEntries(
                            submission.items.map((row: any) => [
                              row.sampleId,
                              selected,
                            ]),
                          ),
                          specVersionId: submission.specVersionId,
                        });
                      })
                    }
                  >
                    整包退回并准备重标
                  </Button>
                </Space>
                <Table<any>
                  rowKey="sampleId"
                  size="small"
                  dataSource={submission.items}
                  rowClassName={(row) =>
                    row.sampleId === sampleId ? 'ant-table-row-selected' : ''
                  }
                  columns={[
                    {
                      title: '样本',
                      render: (_, row) => (
                        <span title={row.sampleId}>{sampleName(row)}</span>
                      ),
                    },
                    {
                      title: '核查结论',
                      render: (_, row) => {
                        const action = review?.items.find(
                          (r: any) => r.sampleId === row.sampleId,
                        )?.action;
                        return (
                          <Tag>
                            {(
                              {
                                ACCEPT: '已确认',
                                MODIFY: '修订并确认',
                                REJECT: '需重标',
                                CORRECTION: '待修正',
                              } as Record<string, string>
                            )[action] || '待核查'}
                          </Tag>
                        );
                      },
                    },
                    {
                      title: '操作',
                      render: (_, row) => (
                        <Button
                          onClick={() => {
                            if (guard.current()) {
                              setSampleId(row.sampleId);
                              setEditing(false);
                            }
                          }}
                        >
                          查看结果
                        </Button>
                      ),
                    },
                  ]}
                />
                {!!reviewed?.reason && (
                  <Alert message="已有核查说明" description={reviewed.reason} />
                )}
                <ReviewAudit
                  key={selected}
                  submissionId={selected}
                  revision={reload}
                />
                {reviewed?.reviewedBy && (
                  <Typography.Paragraph>
                    {reviewed.action === 'MODIFY' ? '修订人' : '核查人'}：
                    <span title={reviewed.reviewedBy}>
                      {reviewed.reviewedByName || '未知用户'}
                    </span>
                    {'　'}
                    {reviewed.action === 'MODIFY' ? '修订时间' : '核查时间'}：
                    {formatDisplayDateTime(reviewed.reviewedAt)}
                  </Typography.Paragraph>
                )}
                {item && version && (
                  <>
                    <Space wrap>
                      <Button
                        disabled={locked || busy}
                        onClick={() => void run(() => update('ACCEPT', [item]))}
                      >
                        确认当前样本
                      </Button>
                      <Button
                        disabled={locked || busy}
                        onClick={() => {
                          if (guard.current()) setEditing(!editing);
                        }}
                      >
                        {editing ? '取消修订' : '修订结果'}
                      </Button>
                      <Button
                        danger
                        disabled={locked || busy}
                        onClick={() =>
                          void run(async () => {
                            if (!reason.trim()) throw Error('请填写退回说明');
                            await update('REJECT', [item]);
                            rework({
                              ids: [item.sampleId],
                              references: { [item.sampleId]: selected },
                              specVersionId: submission.specVersionId,
                            });
                          })
                        }
                      >
                        退回当前样本并准备重标
                      </Button>
                    </Space>
                    <NativeEditor
                      compact
                      key={`${sampleId}:${editing}:${reload}`}
                      path={`/projects/${project.projectId}/samples/${sampleId}/task?specVersionId=${version.specVersionId}`}
                      sampleId={sampleId}
                      category={categoryOf(project)}
                      result={result?.result || []}
                      emptyReason={result?.emptyReason}
                      onGuard={(fn) => {
                        guard.current = fn;
                        onGuard(fn);
                      }}
                      reviewMode
                      onSave={
                        editing && !locked
                          ? async (values, done, emptyReason) => {
                              if (!done) throw Error('请完成修订后保存');
                              await labelingJson(
                                `/submissions/${selected}/review/items`,
                                {
                                  method: 'PUT',
                                  headers: {
                                    'Content-Type': 'application/json',
                                    'Idempotency-Key': operationKey(),
                                  },
                                  body: JSON.stringify({
                                    items: [
                                      {
                                        sampleId,
                                        action: 'MODIFY',
                                        reason,
                                        result: {
                                          result: values,
                                          ...(emptyReason
                                            ? { emptyReason }
                                            : {}),
                                        },
                                      },
                                    ],
                                  }),
                                },
                              );
                              setEditing(false);
                              setReload((v) => v + 1);
                              refresh();
                            }
                          : undefined
                      }
                    />
                  </>
                )}
              </>
            )}
          </>
        )}
      </Space>
    </Card>
  );
}
