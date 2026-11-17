import {
  Alert,
  App,
  Button,
  Card,
  Input,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError, type SpecVersion } from '@/services/labeling';
import {
  type HumanReview,
  ownerApi,
  type ReviewAudit,
  readAllPages,
} from '@/services/labelingOwner';
import { resultLabel } from '@/services/labelingStructured';
import {
  type Submission,
  type SubmissionSummary,
  tasks,
} from '@/services/labelingTasks';
import { statusName } from '@/services/labelingWorkspace';
import OriginalImage from './OriginalImage';
import StructuredPreview from './StructuredPreview';
import useOwnerAction from './useOwnerAction';

export function ReviewConclusion({ submissionId }: { submissionId: string }) {
  const [review, setReview] = useState<HumanReview>(),
    [error, setError] = useState('');
  useEffect(() => {
    const abort = new AbortController();
    setReview(undefined);
    setError('');
    void ownerApi
      .humanReview(submissionId, abort.signal)
      .then((value) => {
        if (!abort.signal.aborted) setReview(value);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [submissionId]);
  return (
    <Card title="需求方审核结论">
      {error && <Alert type="error" message={error} />}
      {review && (
        <>
          <p>提交审核状态：{statusName(review.status)} · 原始提交保持冻结</p>
          <Table
            rowKey="sampleId"
            dataSource={review.items}
            pagination={{ pageSize: 10 }}
            scroll={{ x: 650 }}
            columns={[
              { title: '样本', dataIndex: 'sampleId' },
              { title: '结论', dataIndex: 'action', render: statusName },
              {
                title: '修订标签',
                render: (_, item) => resultLabel(item.result) || '—',
              },
              { title: '原因', dataIndex: 'reason' },
              { title: '审核时间', dataIndex: 'reviewedAt' },
            ]}
          />
        </>
      )}
    </Card>
  );
}

export default function SubmissionReview({
  projectId,
  specs,
  onChanged,
  onBusyChange,
  writeBlocked,
}: {
  projectId: string;
  specs: SpecVersion[];
  onChanged: () => void;
  onBusyChange?: (busy: boolean) => void;
  writeBlocked?: string;
}) {
  const { modal } = App.useApp(),
    action = useOwnerAction(onBusyChange, writeBlocked);
  const [summaries, setSummaries] = useState<SubmissionSummary[]>([]),
    [listCursor, setListCursor] = useState<string | null>(null);
  const [input, setInput] = useState(''),
    [target, setTarget] = useState('');
  const [submission, setSubmission] = useState<Submission>(),
    [review, setReview] = useState<HumanReview>();
  const [audit, setAudit] = useState<ReviewAudit[]>([]),
    [sample, setSample] = useState('');
  const [reason, setReason] = useState(''),
    [label, setLabel] = useState<string>();
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(false),
    [refresh, setRefresh] = useState(0);
  const version = useRef(0);
  useEffect(() => {
    const abort = new AbortController();
    void tasks
      .submissions(projectId, null, abort.signal)
      .then((page) => {
        if (!abort.signal.aborted) {
          setSummaries(page.items);
          setListCursor(page.nextCursor);
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [projectId, refresh]);
  useEffect(() => {
    const current = ++version.current;
    setSubmission(undefined);
    setReview(undefined);
    setAudit([]);
    setSample('');
    setLabel(undefined);
    setReason('');
    setError('');
    if (!target) {
      setLoading(false);
      return;
    }
    setLoading(true);
    void Promise.all([
      tasks.submission(target),
      ownerApi.humanReview(target),
      readAllPages(
        (c) => ownerApi.audit(target, c),
        (row) => row.auditId,
      ),
    ])
      .then(([value, conclusion, events]) => {
        if (version.current !== current) return;
        if (value.projectId !== projectId || conclusion.projectId !== projectId)
          throw new Error('提交不属于当前项目');
        setSubmission(value);
        setReview(conclusion);
        setAudit(events);
        setSample(value.items[0]?.sampleId || '');
      })
      .catch((e) => {
        if (version.current === current) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (version.current === current) setLoading(false);
      });
    return () => {
      ++version.current;
    };
  }, [projectId, target, refresh]);
  const spec = specs.find(
    (item) => item.specVersionId === submission?.specVersionId,
  );
  function decide(
    decision: 'ACCEPT' | 'MODIFY' | 'REJECT' | 'CORRECTION',
    whole = false,
  ) {
    if (!submission || action.busy || loading) return;
    if (decision !== 'ACCEPT' && !reason.trim()) {
      action.setError('请填写本次审核原因');
      return;
    }
    if (
      decision === 'MODIFY' &&
      (!label || !spec?.labels.some((item) => item.id === label))
    ) {
      action.setError('请从该提交绑定的规范中选择修订标签');
      return;
    }
    const captured = submission;
    modal.confirm({
      title: `${whole ? '整份提交' : '当前样本'}：${{ ACCEPT: '接受原始答案', MODIFY: '修改并接受', REJECT: '退回', CORRECTION: '标记待修正' }[decision]}？`,
      content: whole
        ? `影响该提交全部 ${captured.items.length} 个样本。整份接受沿用原始冻结答案，会覆盖已有逐样本修订结论，请核对后确认。`
        : '审核记录独立保存，原始提交不变。修改并接受会更新该样本的需求方确认答案。',
      onOk: async () => {
        const body = {
          action: decision,
          reason: reason.trim(),
          sampleId: sample,
          label,
          whole,
        };
        const value = await action.run(
          `submission-review:${captured.submissionId}`,
          body,
          (key) =>
            whole
              ? ownerApi.decide(
                  captured.submissionId,
                  {
                    action: decision as 'ACCEPT' | 'REJECT' | 'CORRECTION',
                    reason: reason.trim(),
                  },
                  key,
                )
              : ownerApi.reviewItems(
                  captured.submissionId,
                  [
                    {
                      sampleId: sample,
                      action: decision,
                      reason: reason.trim(),
                      ...(decision === 'MODIFY'
                        ? { result: { labelId: label } }
                        : {}),
                    },
                  ],
                  key,
                ),
          '提交审核已保存',
        );
        if (value) {
          setRefresh((n) => n + 1);
          onChanged();
        }
      },
    });
  }
  return (
    <Card title="人工提交审核与修订">
      {(error || action.error) && (
        <Alert type="error" message={error || action.error} />
      )}
      <Space wrap>
        <Select
          aria-label="选择人工提交"
          style={{ width: 320 }}
          placeholder="选择已加载的提交"
          value={target || undefined}
          disabled={action.busy}
          onChange={setTarget}
          options={summaries.map((item) => ({
            value: item.submissionId,
            label: `${item.submittedBy} · ${item.submissionId}`,
          }))}
        />
        {listCursor && (
          <Button
            disabled={loading || action.busy}
            onClick={async () => {
              try {
                const page = await tasks.submissions(projectId, listCursor);
                setSummaries((old) => [...old, ...page.items]);
                setListCursor(page.nextCursor);
              } catch (e) {
                setError(describeLabelingError(e));
              }
            }}
          >
            更多提交
          </Button>
        )}
        <Input.Search
          aria-label="按已知提交编号查询审核"
          placeholder="粘贴已知提交编号（可选）"
          value={input}
          disabled={action.busy}
          onChange={(e) => setInput(e.target.value)}
          onSearch={() => setTarget(input.trim())}
        />
      </Space>
      {submission && (
        <>
          <p>
            提交 {submission.submissionId} · 规范 {submission.specVersionId} ·{' '}
            <Tag>{statusName(review?.status)}</Tag>
          </p>
          <Table
            rowKey="sampleId"
            dataSource={submission.items}
            pagination={{ pageSize: 10 }}
            loading={loading}
            scroll={{ x: 600 }}
            columns={[
              { title: '样本', dataIndex: 'sampleId' },
              {
                title: '原始标签',
                render: (_, item) =>
                  spec?.labels.find((l) => l.id === item.result.labelId)
                    ?.name || item.result.labelId,
              },
              {
                title: '当前结论',
                render: (_, item) =>
                  statusName(
                    review?.items.find((r) => r.sampleId === item.sampleId)
                      ?.action,
                  ),
              },
              {
                title: '审核',
                render: (_, item) => (
                  <Button
                    disabled={action.busy}
                    onClick={() => {
                      setSample(item.sampleId);
                      setLabel(undefined);
                      setReason('');
                    }}
                  >
                    选择
                  </Button>
                ),
              },
            ]}
          />
          {sample && (
            <>
              <p>当前样本：{sample}</p>
              {spec?.taskType === 'TIMESERIES_ANNOTATION' ? (
                <>
                  <StructuredPreview
                    projectId={projectId}
                    spec={spec}
                    row={{
                      sampleId: sample,
                      resultId:
                        submission.items.find((i) => i.sampleId === sample)
                          ?.resultId || null,
                      reviewRevision: 0,
                      result:
                        submission.items.find((i) => i.sampleId === sample)
                          ?.result || null,
                    }}
                  />
                  <Alert
                    type="info"
                    message="能耗答案修订请在结果与审核中选择具体结果，填写原因并保存修订，再确认采用。"
                  />
                </>
              ) : (
                <OriginalImage
                  key={sample}
                  projectId={projectId}
                  sampleId={sample}
                />
              )}
              <Space wrap style={{ marginTop: 12 }}>
                <Select
                  aria-label="人工审核修订标签"
                  placeholder="选择修订标签"
                  style={{ width: 220 }}
                  value={label}
                  onChange={setLabel}
                  disabled={action.busy || !spec}
                  options={spec?.labels.map((item) => ({
                    value: item.id,
                    label: item.name,
                  }))}
                />
                <Input.TextArea
                  aria-label="人工提交审核原因"
                  placeholder="修改、退回、待修正时必填原因"
                  maxLength={500}
                  value={reason}
                  disabled={action.busy}
                  onChange={(e) => setReason(e.target.value)}
                  style={{ width: 340 }}
                />
                {(['ACCEPT', 'MODIFY', 'REJECT', 'CORRECTION'] as const).map(
                  (decision) => (
                    <Button
                      key={decision}
                      disabled={
                        action.busy ||
                        loading ||
                        (decision === 'MODIFY' &&
                          spec?.taskType === 'TIMESERIES_ANNOTATION')
                      }
                      onClick={() => decide(decision)}
                    >
                      {
                        {
                          ACCEPT: '接受原始答案',
                          MODIFY: '修改并接受',
                          REJECT: '退回',
                          CORRECTION: '待修正',
                        }[decision]
                      }
                    </Button>
                  ),
                )}
              </Space>
            </>
          )}
          <details style={{ marginTop: 16 }}>
            <summary>整份提交操作（影响全部样本）</summary>
            <Space wrap style={{ marginTop: 16 }}>
              {(['ACCEPT', 'REJECT', 'CORRECTION'] as const).map((decision) => (
                <Button
                  key={decision}
                  disabled={action.busy || loading}
                  onClick={() => decide(decision, true)}
                >
                  {
                    {
                      ACCEPT: '整份接受原始答案',
                      REJECT: '整份退回',
                      CORRECTION: '整份待修正',
                    }[decision]
                  }
                </Button>
              ))}
            </Space>
          </details>
          <details style={{ marginTop: 16 }}>
            <summary>查看审核记录</summary>
            <Table
              rowKey="auditId"
              dataSource={audit}
              pagination={{ pageSize: 10 }}
              scroll={{ x: 700 }}
              columns={[
                { title: '样本', dataIndex: 'sampleId' },
                { title: '动作', dataIndex: 'action' },
                {
                  title: '原标签',
                  render: (_, row) => resultLabel(row.beforeResult) || '—',
                },
                {
                  title: '新标签',
                  render: (_, row) => resultLabel(row.afterResult) || '—',
                },
                { title: '原因', dataIndex: 'reason' },
                { title: '时间', dataIndex: 'createdAt' },
              ]}
            />
          </details>
        </>
      )}
    </Card>
  );
}
