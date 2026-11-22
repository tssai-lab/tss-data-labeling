import { LeftOutlined, ReloadOutlined, RightOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Select,
  Space,
  Spin,
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
import type { NativeProject, NativeSample } from '@/services/nativeLabeling';
import { hasProjectAnswer } from '@/services/projectCompatibility.mjs';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import AiDemoReview from './AiDemoReview';
import type { DemoResult } from './NativeAi';
import type { ReworkScope } from './NativePublish';
import ProjectSampleEditor from './ProjectSampleEditor';
import ReviewAudit from './ReviewAudit';
import { reviewStatusName, sampleName } from './resultDisplay';
import ui from './workspaceUi.less';

export default function NativeReview({
  project,
  submissions,
  run: execute,
  refresh,
  rework,
  busy,
  onGuard,
  aiResults,
  samples,
  onAiResultsChange,
}: {
  project: NativeProject;
  samples: NativeSample[];
  aiResults?: DemoResult[];
  onAiResultsChange: (results: DemoResult[]) => Promise<void>;
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
  const [resultSource, setResultSource] = useState<'AI' | 'HUMAN'>(
    aiResults?.length ? 'AI' : 'HUMAN',
  );
  const [sampleFilter, setSampleFilter] = useState('ALL');
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
          setSampleId((previous) =>
            s.items.some((row: any) => row.sampleId === previous)
              ? previous
              : s.items[0]?.sampleId || '',
          );
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
  const statusFor = (row: any) =>
    review?.items.find((value: any) => value.sampleId === row.sampleId)
      ?.action || 'PENDING';
  const statusLabel = (status?: string) =>
    (
      ({
        ACCEPT: '已确认',
        MODIFY: '修订并确认',
        REJECT: '需重标',
        CORRECTION: '待修正',
      }) as Record<string, string>
    )[status || ''] || reviewStatusName(status);
  const hasAnswer = (row: any) => hasProjectAnswer(row.result);
  const totalCount = submission?.items.length || 0;
  const answeredCount = submission?.items.filter(hasAnswer).length || 0;
  const acceptedCount =
    submission?.items.filter((row: any) =>
      ['ACCEPT', 'MODIFY'].includes(statusFor(row)),
    ).length || 0;
  const visibleItems = (submission?.items || []).filter((row: any) => {
    if (sampleFilter === 'ANSWERED') return hasAnswer(row);
    if (sampleFilter === 'NO_ANSWER') return !hasAnswer(row);
    if (sampleFilter === 'ACCEPTED')
      return ['ACCEPT', 'MODIFY'].includes(statusFor(row));
    return true;
  });
  const names = new Map(samples.map((sample) => [sample.sampleId, sample]));
  const displaySample = (row: any) => {
    const source = names.get(row.sampleId);
    const name = sampleName({
      ...row,
      displayName: row.displayName || source?.metadata?.displayName,
      sourcePath: row.sourcePath || source?.sourcePath,
    });
    return name.split(/[\\/]/).pop() || name;
  };
  const visibleIndex = visibleItems.findIndex(
    (row: any) => row.sampleId === sampleId,
  );
  const changeSample = (id: string) => {
    if (id !== sampleId && !busy && guard.current()) {
      setSampleId(id);
      setEditing(false);
      setReason('');
    }
  };
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
    <div className={ui.nativeReview}>
      {Boolean(aiResults?.length) && (
        <Space>
          <Button
            type={resultSource === 'AI' ? 'primary' : 'default'}
            onClick={() => {
              if (guard.current()) setResultSource('AI');
            }}
          >
            AI 候选结果
          </Button>
          <Button
            type={resultSource === 'HUMAN' ? 'primary' : 'default'}
            onClick={() => {
              if (guard.current()) setResultSource('HUMAN');
            }}
          >
            人工提交结果
          </Button>
        </Space>
      )}
      {resultSource === 'AI' && aiResults?.length ? (
        <>
          <Alert
            type="info"
            showIcon
            message="AI 候选来自本轮后台执行。核查确认后会保存到项目；未完成的结果不可确认。"
          />
          <AiDemoReview
            results={aiResults}
            onChange={onAiResultsChange}
            readOnly={locked}
          />
        </>
      ) : (
        <>
          {error && <Alert type="error" showIcon message={error} />}
          {!submissions.length ? (
            <Card>
              <Empty description="尚无交回结果。领取并完成标注后，提交结果会显示在这里。" />
            </Card>
          ) : (
            <>
              <div className={ui.reviewSubmission}>
                <div>
                  <strong>核查提交</strong>
                  <span>选择一个提交批次，查看样本结果并处理核查结论。</span>
                </div>
                <Select
                  aria-label="核查提交"
                  value={selected}
                  onChange={(id) => {
                    if (guard.current()) {
                      setSubmissionId(id);
                      setEditing(false);
                      setSampleFilter('ALL');
                      setReason('');
                    }
                  }}
                  options={submissions.map((row) => ({
                    value: row.submissionId,
                    label: `${row.submittedByName || (row.submissionId === submission?.submissionId ? submission?.submittedByName : undefined) || '未知标注人'} · ${formatDisplayDateTime(row.submittedAt)} · ${reviewStatusName(row.reviewStatus)}`,
                    title: `提交编号：${row.submissionId}`,
                  }))}
                />
              </div>
              {!submission ? (
                <Card>
                  <Spin tip="正在读取提交结果">
                    <div style={{ height: 120 }} />
                  </Spin>
                </Card>
              ) : (
                <>
                  <div className={ui.metrics}>
                    {[
                      ['ALL', '全部样本', totalCount],
                      ['ANSWERED', '有答案', answeredCount],
                      ['NO_ANSWER', '无答案', totalCount - answeredCount],
                      ['ACCEPTED', '已确认', acceptedCount],
                    ].map(([key, label, value]) => (
                      <button
                        key={key}
                        type="button"
                        className={ui.metric}
                        aria-pressed={sampleFilter === key}
                        onClick={() =>
                          setSampleFilter((current) =>
                            current === key ? 'ALL' : String(key),
                          )
                        }
                      >
                        <span>{label}</span>
                        <strong>{value}</strong>
                      </button>
                    ))}
                  </div>
                  <div className={ui.resultsTools}>
                    <span>统计基于当前提交 · 点击卡片筛选样本</span>
                  </div>
                  <div className={ui.nativeReviewGrid}>
                    <Card
                      className={ui.reviewPanel}
                      title="样本列表"
                      extra={
                        <Space wrap>
                          <Select
                            aria-label="按核查状态筛选"
                            size="small"
                            value={sampleFilter}
                            onChange={setSampleFilter}
                            options={[
                              { value: 'ALL', label: '全部状态' },
                              { value: 'ANSWERED', label: '有答案' },
                              { value: 'NO_ANSWER', label: '无答案' },
                              { value: 'ACCEPTED', label: '已确认' },
                            ]}
                          />
                          <Button
                            size="small"
                            aria-label="刷新结果"
                            title="刷新结果"
                            icon={<ReloadOutlined />}
                            disabled={busy}
                            onClick={() => {
                              if (guard.current()) {
                                setReload((value) => value + 1);
                                refresh();
                              }
                            }}
                          />
                        </Space>
                      }
                    >
                      <div className={ui.reviewSampleList}>
                        {visibleItems.map((row: any) => {
                          const action = statusFor(row);
                          const status = statusLabel(action);
                          return (
                            <button
                              key={row.sampleId}
                              type="button"
                              className={ui.reviewSample}
                              aria-current={row.sampleId === sampleId}
                              onClick={() => changeSample(row.sampleId)}
                            >
                              <span className={ui.reviewSampleName}>
                                {displaySample(row)}
                              </span>
                              <span className={ui.reviewSampleMeta}>
                                <span>
                                  {hasAnswer(row) ? '有答案' : '无答案'}
                                </span>
                                <Tag
                                  color={
                                    action === 'ACCEPT' || action === 'MODIFY'
                                      ? 'green'
                                      : action === 'REJECT'
                                        ? 'orange'
                                        : 'blue'
                                  }
                                >
                                  {status}
                                </Tag>
                              </span>
                            </button>
                          );
                        })}
                        {!visibleItems.length && (
                          <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="没有符合条件的样本"
                          />
                        )}
                      </div>
                    </Card>
                    <Card
                      className={ui.reviewPanel}
                      title="样本数据预览"
                      extra={item ? displaySample(item) : undefined}
                    >
                      {item && version ? (
                        <ProjectSampleEditor
                          project={project}
                          version={version}
                          key={`${sampleId}:${editing}:${reload}`}
                          sampleId={sampleId}
                          result={result}
                          onGuard={(fn) => {
                            guard.current = fn;
                            onGuard(fn);
                          }}
                          onSave={
                            editing && !locked
                              ? async (savedResult) => {
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
                                            result: savedResult,
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
                      ) : (
                        <Empty description="选择一个样本查看结果" />
                      )}
                    </Card>
                    <Card
                      className={ui.reviewPanel}
                      title="核查详情"
                      extra={
                        item && (
                          <Space wrap>
                            <Tag
                              color={
                                reviewed?.action === 'ACCEPT' ||
                                reviewed?.action === 'MODIFY'
                                  ? 'green'
                                  : reviewed?.action === 'REJECT'
                                    ? 'orange'
                                    : 'blue'
                              }
                            >
                              {statusLabel(
                                reviewed?.action || submission.reviewStatus,
                              )}
                            </Tag>
                            <Button
                              size="small"
                              disabled={locked || busy}
                              onClick={() => {
                                if (guard.current()) setEditing(!editing);
                              }}
                            >
                              {editing ? '取消修订' : '修订结果'}
                            </Button>
                          </Space>
                        )
                      }
                    >
                      <Space wrap>
                        <Button
                          icon={<LeftOutlined />}
                          disabled={busy || visibleIndex <= 0}
                          onClick={() =>
                            changeSample(
                              visibleItems[visibleIndex - 1].sampleId,
                            )
                          }
                        >
                          上一张
                        </Button>
                        <span>
                          {visibleIndex < 0 ? '—' : visibleIndex + 1} /{' '}
                          {visibleItems.length}
                        </span>
                        <Button
                          disabled={
                            busy ||
                            visibleIndex < 0 ||
                            visibleIndex >= visibleItems.length - 1
                          }
                          onClick={() =>
                            changeSample(
                              visibleItems[visibleIndex + 1].sampleId,
                            )
                          }
                        >
                          下一张 <RightOutlined />
                        </Button>
                      </Space>
                      <div className={ui.reviewDetails}>
                        <div>
                          <span>标注人</span>
                          <strong>
                            {submission.submittedByName || '未知标注人'}
                          </strong>
                        </div>
                        <div>
                          <span>提交时间</span>
                          <strong>
                            {formatDisplayDateTime(submission.submittedAt)}
                          </strong>
                        </div>
                        <div>
                          <span>当前样本</span>
                          <strong title={item?.sampleId}>
                            {item ? displaySample(item) : '—'}
                          </strong>
                        </div>
                        <div>
                          <span>核查结论</span>
                          <strong>
                            {statusLabel(
                              reviewed?.action || submission.reviewStatus,
                            )}
                          </strong>
                        </div>
                        {reviewed?.reviewedBy && (
                          <div>
                            <span>
                              {reviewed.action === 'MODIFY'
                                ? '修订人'
                                : '核查人'}
                            </span>
                            <strong title={reviewed.reviewedBy}>
                              {reviewed.reviewedByName || '未知用户'}
                            </strong>
                          </div>
                        )}
                      </div>
                      {!!reviewed?.reason && (
                        <Alert
                          style={{ marginTop: 10 }}
                          message="已有核查说明"
                          description={reviewed.reason}
                        />
                      )}
                      <Input.TextArea
                        aria-label="核查说明"
                        className={ui.reviewReason}
                        maxLength={500}
                        value={reason}
                        disabled={locked}
                        onChange={(e) => setReason(e.target.value)}
                        placeholder="填写核查或重标说明"
                      />
                      {item && (
                        <Space wrap className={ui.reviewActions}>
                          <Button
                            type="primary"
                            disabled={locked || busy}
                            onClick={() =>
                              void run(() => update('ACCEPT', [item]))
                            }
                          >
                            确认采用
                          </Button>
                          <Button
                            danger
                            disabled={locked || busy || !reason.trim()}
                            onClick={() =>
                              void run(async () => {
                                await update('REJECT', [item]);
                                rework({
                                  ids: [item.sampleId],
                                  references: { [item.sampleId]: selected },
                                  specVersionId: submission.specVersionId,
                                });
                              })
                            }
                          >
                            标记待修正
                          </Button>
                        </Space>
                      )}
                      <details className={ui.reviewPackageActions}>
                        <summary>整包操作</summary>
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
                            disabled={locked || busy || !reason.trim()}
                            onClick={() =>
                              void run(async () => {
                                await update('REJECT', submission.items);
                                rework({
                                  ids: submission.items.map(
                                    (row: any) => row.sampleId,
                                  ),
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
                      </details>
                      {reviewed?.reviewedAt && (
                        <Typography.Paragraph
                          type="secondary"
                          className={ui.reviewedAt}
                        >
                          {reviewed.action === 'MODIFY'
                            ? '修订时间'
                            : '核查时间'}
                          ：{formatDisplayDateTime(reviewed.reviewedAt)}
                        </Typography.Paragraph>
                      )}
                      <ReviewAudit
                        key={selected}
                        submissionId={selected}
                        revision={reload}
                      />
                    </Card>
                  </div>
                </>
              )}
            </>
          )}
        </>
      )}
    </div>
  );
}
