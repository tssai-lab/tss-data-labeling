import { Link, useParams } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Image,
  Progress,
  Radio,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  assignmentSampleBlob,
  LabelingError,
  operationKey,
} from '@/services/labeling';
import type { PendingSubmission } from '@/services/labelingClassification.mjs';
import {
  confirmedDraft,
  parsePendingSubmission,
  submissionManifest,
} from '@/services/labelingClassification.mjs';
import type {
  Draft,
  LabelingIdentity,
  WorkPage,
  WorkSample,
} from '@/services/labelingTasks';
import { allWorkItems, tasks } from '@/services/labelingTasks';
import styles from './index.less';
import Navigation from './Navigation';

const { Paragraph, Text } = Typography;
function describe(error: unknown): string {
  return error instanceof LabelingError
    ? `${error.message}${error.code ? `（${error.code}）` : ''}${error.requestId ? ` · 请求编号 ${error.requestId}` : ''}`
    : error instanceof Error
      ? error.message
      : '操作失败';
}

function WorkImage({
  assignmentId,
  sample,
}: {
  assignmentId: string;
  sample: WorkSample;
}) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    let objectUrl = '';
    setUrl('');
    setError('');
    assignmentSampleBlob(assignmentId, sample.sampleId, abort.signal)
      .then((blob) => {
        if (abort.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch((e: unknown) => {
        if (!abort.signal.aborted) setError(describe(e));
      });
    return () => {
      abort.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [assignmentId, sample.sampleId, retry]);
  if (error)
    return (
      <Alert
        type="error"
        showIcon
        message="图片读取失败"
        description={error}
        action={<Button onClick={() => setRetry(retry + 1)}>重试</Button>}
      />
    );
  return (
    <div className={styles.preview}>
      {url ? (
        <Image
          src={url}
          alt={sample.sourcePath}
          style={{ maxHeight: 500, objectFit: 'contain' }}
        />
      ) : (
        <Spin>
          <div style={{ height: 240 }} />
        </Spin>
      )}
    </div>
  );
}

export default function ClassificationAssignment() {
  const { assignmentId = '' } = useParams<{ assignmentId: string }>();
  const { message, modal } = App.useApp();
  const [work, setWork] = useState<WorkPage>();
  const [identity, setIdentity] = useState<LabelingIdentity>();
  const [selectedId, setSelectedId] = useState('');
  const [draft, setDraft] = useState<Draft>();
  const [labelId, setLabelId] = useState('');
  const [error, setError] = useState('');
  const [draftError, setDraftError] = useState('');
  const [loading, setLoading] = useState(false);
  const [draftLoading, setDraftLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [needsReadback, setNeedsReadback] = useState(false);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [pending, setPending] = useState<PendingSubmission>();
  const [receiptId, setReceiptId] = useState('');
  const [recoveryError, setRecoveryError] = useState(false);
  const [draftRetry, setDraftRetry] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const requestVersion = useRef(0);
  const savingLock = useRef(false);
  const submittingLock = useRef(false);
  const selected = work?.items.find((item) => item.sampleId === selectedId);
  const readOnly =
    work?.readOnly || work?.status === 'SUBMITTED' || Boolean(receiptId);
  const dirty = Boolean(draft && labelId !== (draft.result.labelId || ''));
  const pendingKey = identity
    ? `labeling:pending-submission:${identity.actorId}:${assignmentId}`
    : '';
  const completed =
    work?.items.filter(
      (item) => item.explicitDone === true && item.draftRevision,
    ).length || 0;
  let complete = false;
  if (work) {
    try {
      submissionManifest(work.items, work.specification.labels);
      complete = true;
    } catch {
      /* Not all samples are saved yet. */
    }
  }

  function canLeave() {
    if (saving || submitting) {
      message.info('请求进行中，请等待结果');
      return false;
    }
    return !dirty || window.confirm('当前分类尚未保存，确定离开吗？');
  }

  useEffect(() => {
    ++requestVersion.current;
    const current = requestVersion.current;
    const abort = new AbortController();
    setWork(undefined);
    setIdentity(undefined);
    setSelectedId('');
    setDraft(undefined);
    setLabelId('');
    setPending(undefined);
    setReceiptId('');
    setError('');
    setDraftError('');
    setRecoveryError(false);
    setNeedsRefresh(false);
    setNeedsReadback(false);
    setLoading(true);
    setSaving(false);
    setSubmitting(false);
    savingLock.current = false;
    submittingLock.current = false;
    Promise.all([
      tasks.me(abort.signal),
      allWorkItems(assignmentId, abort.signal),
    ])
      .then(([me, value]) => {
        if (abort.signal.aborted || current !== requestVersion.current) return;
        setIdentity(me);
        setWork(value);
        setSelectedId(value.items[0]?.sampleId || '');
        const key = `labeling:pending-submission:${me.actorId}:${assignmentId}`;
        try {
          const saved = parsePendingSubmission(
            sessionStorage.getItem(key),
            me.actorId,
            assignmentId,
          );
          setPending(saved);
          setReceiptId(sessionStorage.getItem(`${key}:receipt`) || '');
        } catch (e) {
          setRecoveryError(true);
          setError(describe(e));
        }
      })
      .catch((e: unknown) => {
        if (!abort.signal.aborted) setError(describe(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => {
      ++requestVersion.current;
      abort.abort();
    };
  }, [assignmentId, refresh]);

  useEffect(() => {
    setDraft(undefined);
    setLabelId('');
    setDraftError('');
    setNeedsReadback(false);
    if (!selectedId || !work) return;
    const abort = new AbortController();
    setDraftLoading(true);
    tasks
      .draft(assignmentId, selectedId, abort.signal)
      .then((value) => {
        if (abort.signal.aborted) return;
        const next = confirmedDraft(value, assignmentId, selectedId);
        setDraft(next);
        setLabelId(next.result.labelId || '');
        setWork((previous) =>
          previous
            ? {
                ...previous,
                items: previous.items.map((item) =>
                  item.sampleId === selectedId
                    ? {
                        ...item,
                        draftRevision: next.revision,
                        explicitDone: next.explicitDone,
                        resultFormat: next.resultFormat,
                        result: next.result,
                      }
                    : item,
                ),
              }
            : previous,
        );
      })
      .catch((e: unknown) => {
        if (!abort.signal.aborted) setDraftError(describe(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setDraftLoading(false);
      });
    return () => abort.abort();
  }, [assignmentId, selectedId, draftRetry, Boolean(work)]);

  useEffect(() => {
    if (!dirty && !saving && !submitting) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, saving, submitting]);

  async function save() {
    if (
      !draft ||
      !work ||
      !selected ||
      savingLock.current ||
      readOnly ||
      needsReadback ||
      pending ||
      recoveryError ||
      needsRefresh
    )
      return;
    const current = requestVersion.current;
    savingLock.current = true;
    setSaving(true);
    setDraftError('');
    try {
      const value = confirmedDraft(
        await tasks.save(
          assignmentId,
          selected.sampleId,
          draft.revision,
          labelId,
        ),
        assignmentId,
        selected.sampleId,
      );
      if (current !== requestVersion.current) return;
      if (
        value.revision <= draft.revision ||
        value.result.labelId !== (labelId || undefined) ||
        value.explicitDone !== Boolean(labelId)
      )
        throw new Error('保存回执与本次选择不一致，请重新读取草稿');
      setDraft(value);
      setLabelId(value.result.labelId || '');
      setWork((previous) =>
        previous
          ? {
              ...previous,
              status: 'IN_PROGRESS',
              items: previous.items.map((item) =>
                item.sampleId === value.sampleId
                  ? {
                      ...item,
                      draftRevision: value.revision,
                      explicitDone: value.explicitDone,
                      resultFormat: value.resultFormat,
                      result: value.result,
                    }
                  : item,
              ),
            }
          : previous,
      );
      message.success(
        value.explicitDone
          ? '草稿已保存，尚未提交整包'
          : '选择已清空，该图片恢复为未完成',
      );
    } catch (e) {
      if (current === requestVersion.current) {
        setNeedsReadback(true);
        setDraftError(
          `${describe(e)}。本次保存结果需先重新读取确认，不能直接覆盖重试。`,
        );
      }
    } finally {
      if (current === requestVersion.current) {
        savingLock.current = false;
        setSaving(false);
      }
    }
  }

  function clearPending() {
    sessionStorage.removeItem(pendingKey);
    setPending(undefined);
  }
  async function sendSubmission(attempt: PendingSubmission) {
    if (submittingLock.current) return;
    const current = requestVersion.current;
    submittingLock.current = true;
    setSubmitting(true);
    setError('');
    try {
      // Persist the exact request before sending it so a lost receipt can be replayed after refresh.
      sessionStorage.setItem(pendingKey, JSON.stringify(attempt));
      setPending(attempt);
      const result = await tasks.submit(
        assignmentId,
        attempt.items,
        attempt.key,
      );
      if (current !== requestVersion.current) return;
      if (
        result.assignmentId !== assignmentId ||
        result.status !== 'SUBMITTED' ||
        !result.submissionId ||
        result.sampleCount !== attempt.items.length
      )
        throw new Error('提交回执异常，请使用原请求确认');
      setReceiptId(result.submissionId);
      setWork((previous) =>
        previous
          ? { ...previous, status: 'SUBMITTED', readOnly: true }
          : previous,
      );
      try {
        sessionStorage.setItem(`${pendingKey}:receipt`, result.submissionId);
        clearPending();
      } catch {
        /* The successful response is already shown and editing is locked. */
      }
      message.success('整包已提交，结果已冻结为只读');
    } catch (e) {
      if (current !== requestVersion.current) return;
      if (
        e instanceof LabelingError &&
        (e.code === 'REVISION_CONFLICT' ||
          e.code === 'INCOMPLETE_PACKAGE' ||
          e.code === 'INVALID_RESULT')
      ) {
        try {
          clearPending();
        } catch {
          /* Keep original recovery metadata if storage is unavailable. */
        }
        setNeedsRefresh(true);
        setError(`${describe(e)}。请重新加载整包，核对最新草稿后再提交。`);
      } else
        setError(
          `${describe(e)}。如请求已发出，点击“确认本次提交”重放原请求；不要另建提交。`,
        );
    } finally {
      if (current === requestVersion.current) {
        submittingLock.current = false;
        setSubmitting(false);
      }
    }
  }

  function submit() {
    if (
      !work ||
      !identity ||
      dirty ||
      needsReadback ||
      needsRefresh ||
      recoveryError
    )
      return;
    try {
      const attempt = pending || {
        actorId: identity.actorId,
        assignmentId,
        key: operationKey(),
        items: submissionManifest(work.items, work.specification.labels),
      };
      modal.confirm({
        title: pending ? '确认上一次整包提交结果' : '提交整个任务包？',
        content: pending
          ? '将使用相同版本清单和同一请求编号确认，不会创建第二份提交。'
          : `将提交全部 ${attempt.items.length} 张图片的已保存分类，提交后不能修改或撤回。`,
        okText: pending ? '确认本次提交' : '确认提交',
        cancelText: '取消',
        onOk: () => sendSubmission(attempt),
      });
    } catch (e) {
      setError(describe(e));
    }
  }

  const editingDisabled =
    readOnly ||
    saving ||
    submitting ||
    draftLoading ||
    !draft ||
    needsReadback ||
    needsRefresh ||
    Boolean(pending) ||
    recoveryError ||
    !identity?.canAnnotate;
  return (
    <main className={styles.page}>
      <section className={styles.content}>
        <Navigation canLeave={canLeave} />
        <h1>图片分类标注</h1>
        {error && (
          <Alert
            type="error"
            showIcon
            message={error}
            style={{ marginBottom: 16 }}
          />
        )}
        {loading && (
          <Spin tip="正在读取任务包全部图片">
            <div style={{ height: 180 }} />
          </Spin>
        )}
        {!loading && !work && (
          <Button onClick={() => setRefresh(refresh + 1)}>重新加载任务</Button>
        )}
        {work && (
          <>
            <Card style={{ marginBottom: 20 }}>
              <Space wrap>
                <Tag color={readOnly ? 'green' : 'blue'}>
                  {readOnly ? '已提交 · 只读' : '标注中'}
                </Tag>
                <Text>
                  草稿已完成 {completed} / {work.items.length} 张
                </Text>
                <Button
                  disabled={saving || submitting}
                  onClick={() => {
                    if (canLeave()) setRefresh(refresh + 1);
                  }}
                >
                  重新加载整包
                </Button>
                {receiptId && (
                  <Link
                    to={`/labeling/submissions/${encodeURIComponent(receiptId)}`}
                  >
                    查看冻结提交结果
                  </Link>
                )}
              </Space>
              <Progress
                percent={
                  work.items.length
                    ? Math.round((completed / work.items.length) * 100)
                    : 0
                }
              />
              {pending && !receiptId && (
                <Alert
                  type="warning"
                  showIcon
                  message="上一次提交结果待确认，编辑暂时锁定"
                  description="可安全重放原版本清单与幂等键，确认服务器是否已经接受。"
                  style={{ marginBottom: 16 }}
                />
              )}
              {(!readOnly || pending) && (
                <Button
                  type="primary"
                  loading={submitting}
                  disabled={
                    saving ||
                    loading ||
                    dirty ||
                    needsReadback ||
                    needsRefresh ||
                    recoveryError ||
                    (!pending && (!complete || Boolean(draftError)))
                  }
                  onClick={submit}
                >
                  {pending ? '确认本次提交' : '提交整个任务包'}
                </Button>
              )}
            </Card>
            <Card title="本任务绑定的分类规范">
              <Space wrap>
                {work.specification.labels.map((label) => (
                  <Tag key={label.id}>{label.name}</Tag>
                ))}
              </Space>
              <Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 16 }}>
                {work.specification.instructions || '暂无补充说明'}
              </Paragraph>
            </Card>
            <div className={styles.workspace}>
              <Card title="包内图片">
                <Table<WorkSample>
                  rowKey="sampleId"
                  dataSource={work.items}
                  size="small"
                  pagination={{ pageSize: 20 }}
                  scroll={{ y: 480 }}
                  columns={[
                    {
                      title: '图片',
                      dataIndex: 'sourcePath',
                      render: (value: string, item) => (
                        <Button
                          type="link"
                          className={styles.sampleButton}
                          disabled={saving || submitting}
                          onClick={() => {
                            if (item.sampleId !== selectedId && canLeave())
                              setSelectedId(item.sampleId);
                          }}
                        >
                          {value}
                        </Button>
                      ),
                    },
                    {
                      title: '草稿',
                      render: (_, item) => (
                        <Tag color={item.explicitDone ? 'green' : 'default'}>
                          {item.explicitDone ? '已保存' : '未完成'}
                        </Tag>
                      ),
                    },
                  ]}
                  rowClassName={(item) =>
                    item.sampleId === selectedId ? styles.selectedRow : ''
                  }
                />
              </Card>
              <Card title={selected?.sourcePath || '图片预览'}>
                {selected && (
                  <>
                    <WorkImage
                      key={`${assignmentId}:${selected.sampleId}`}
                      assignmentId={assignmentId}
                      sample={selected}
                    />
                    <div className={styles.classification}>
                      {draftError && (
                        <Alert type="error" showIcon message={draftError} />
                      )}
                      <Space>
                        <Text strong>分类</Text>
                        {draftLoading && <Spin size="small" />}
                        {dirty && <Tag color="orange">当前选择尚未保存</Tag>}
                      </Space>
                      <Radio.Group
                        value={labelId}
                        disabled={editingDisabled}
                        onChange={(event) => setLabelId(event.target.value)}
                        optionType="button"
                        options={work.specification.labels.map((label) => ({
                          value: label.id,
                          label: label.name,
                        }))}
                      />
                      {draft?.savedAt && (
                        <Text type="secondary">
                          草稿保存时间：
                          {new Date(draft.savedAt).toLocaleString('zh-CN')}
                          ；整包提交状态以上方为准。
                        </Text>
                      )}
                      {!readOnly && (
                        <Space wrap>
                          <Button
                            type="primary"
                            loading={saving}
                            disabled={editingDisabled || !dirty}
                            onClick={() => void save()}
                          >
                            保存分类草稿
                          </Button>
                          <Button
                            disabled={editingDisabled || !labelId}
                            onClick={() => setLabelId('')}
                          >
                            清空选择
                          </Button>
                          <Button
                            disabled={saving || submitting || draftLoading}
                            onClick={() => {
                              if (
                                !dirty ||
                                window.confirm(
                                  '重新读取会替换当前未保存选择，是否继续？',
                                )
                              )
                                setDraftRetry(draftRetry + 1);
                            }}
                          >
                            重新读取草稿
                          </Button>
                        </Space>
                      )}
                    </div>
                  </>
                )}
              </Card>
            </div>
          </>
        )}
      </section>
    </main>
  );
}
