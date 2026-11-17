import { Link, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Select,
  Space,
  Spin,
  Table,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError, labelingJson } from '@/services/labeling';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import NativeEditor from './NativeEditor';
import { sampleName } from './resultDisplay';
import SubmissionIdentity from './SubmissionIdentity';
import LegacySubmission from './submission';
import WorkspaceLayout from './WorkspaceLayout';

export default function NativeSubmission() {
  const { submissionId = '' } = useParams<{ submissionId: string }>();
  const [submission, setSubmission] = useState<any>(),
    [review, setReview] = useState<any>(),
    [me, setMe] = useState<any>(),
    [sampleId, setSampleId] = useState(''),
    [corrected, setCorrected] = useState(false),
    [error, setError] = useState('');
  useEffect(() => {
    const abort = new AbortController();
    setError('');
    setSubmission(undefined);
    void Promise.all([
      labelingJson<any>(`/submissions/${submissionId}`, {
        signal: abort.signal,
      }),
      labelingJson<any>(`/submissions/${submissionId}/review`, {
        signal: abort.signal,
      }),
      labelingJson<any>('/me', { signal: abort.signal }),
    ])
      .then(([s, r, user]) => {
        if (!abort.signal.aborted) {
          setSubmission(s);
          setReview(r);
          setMe(user);
          setSampleId(s.items[0]?.sampleId || '');
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [submissionId]);
  if (submission && submission.items[0]?.resultFormat !== 'label-studio-v1')
    return <LegacySubmission />;
  const item = submission?.items.find((row: any) => row.sampleId === sampleId);
  const reviewed = review?.items.find((row: any) => row.sampleId === sampleId);
  const envelope =
    corrected && reviewed?.action === 'MODIFY' ? reviewed.result : item?.result;
  const path =
    submission &&
    (submission.submittedBy === me?.actorId
      ? `/assignments/${submission.assignmentId}/samples/${sampleId}/task`
      : `/projects/${submission.projectId}/samples/${sampleId}/task?specVersionId=${submission.specVersionId}`);
  return (
    <WorkspaceLayout workbench focusMode>
      <Space
        direction="vertical"
        size="middle"
        style={{ width: '100%', padding: 16 }}
      >
        <Link to="/labeling/assignments">返回我的任务</Link>
        {error && <Alert type="error" message={error} />}
        {!submission ? (
          !error && <Spin />
        ) : (
          <>
            <Typography.Title level={4} style={{ margin: 0 }}>
              已提交结果
            </Typography.Title>
            <Alert message="原始提交只读。需求方修订会单独留存，不覆盖这份提交。" />
            <SubmissionIdentity submission={submission} />
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
            <Select
              aria-label="当前查看样本"
              style={{ width: 340, maxWidth: '100%' }}
              value={sampleId}
              onChange={(id) => {
                setSampleId(id);
                setCorrected(false);
              }}
              options={submission.items.map((row: any, index: number) => ({
                value: row.sampleId,
                label: `${index + 1} · ${sampleName(row)}`,
              }))}
            />
            <details>
              <summary style={{ cursor: 'pointer' }}>
                任务包结果及核查结论
              </summary>
              <Card>
                <Table<any>
                  rowKey="sampleId"
                  size="small"
                  dataSource={submission.items}
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
                          (value: any) => value.sampleId === row.sampleId,
                        )?.action;
                        return (
                          (
                            {
                              ACCEPT: '已确认',
                              MODIFY: '需求方已修订并确认',
                              REJECT: '已退回重标',
                              CORRECTION: '待修正',
                            } as Record<string, string>
                          )[action] || '待核查'
                        );
                      },
                    },
                    {
                      title: '操作',
                      render: (_, row) => (
                        <Button
                          onClick={() => {
                            setSampleId(row.sampleId);
                            setCorrected(false);
                          }}
                        >
                          查看
                        </Button>
                      ),
                    },
                  ]}
                />
              </Card>
            </details>
            {!!reviewed?.reason && (
              <Alert message="核查说明" description={reviewed.reason} />
            )}
            {reviewed?.action === 'MODIFY' && (
              <Select
                aria-label="结果版本"
                value={corrected ? 'corrected' : 'original'}
                onChange={(value) => setCorrected(value === 'corrected')}
                options={[
                  { value: 'original', label: '原始提交' },
                  { value: 'corrected', label: '需求方修订结果' },
                ]}
              />
            )}
            {item && path && (
              <NativeEditor
                compact
                key={`${sampleId}:${corrected}`}
                path={path}
                sampleId={sampleId}
                category="Time Series Analysis"
                result={envelope?.result || []}
                emptyReason={envelope?.emptyReason}
              />
            )}
          </>
        )}
      </Space>
    </WorkspaceLayout>
  );
}
