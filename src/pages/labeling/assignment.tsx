import { history, Link, useParams } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Image,
  Progress,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { type ReactNode, useEffect, useRef, useState } from 'react';
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
import ClassificationEditorAdapter, {
  type ClassificationEditorHandle,
  type EditorState,
  type SaveInput,
} from './ClassificationEditorAdapter';
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
  render,
}: {
  assignmentId: string;
  sample: WorkSample;
  render?: (url: string) => ReactNode;
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
  if (url && render) return <>{render(url)}</>;
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
  const editorRef = useRef<ClassificationEditorHandle>(null);
  const [editorState, setEditorState] = useState<EditorState>();
  const [work, setWork] = useState<WorkPage>();
  const [identity, setIdentity] = useState<LabelingIdentity>();
  const [selectedId, setSelectedId] = useState('');
  const [draft, setDraft] = useState<Draft>();
  const [error, setError] = useState('');
  const [draftError, setDraftError] = useState('');
  const [loading, setLoading] = useState(false);
  const [draftLoading, setDraftLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const preparingLock = useRef(false);
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
  const dirty = Boolean(editorState?.dirty);
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

  async function canLeave() {
    if (saving || submitting || preparingLock.current) {
      message.info('请求进行中，请等待结果');
      return false;
    }
    const state = editorRef.current?.getState();
    if (state?.saving) {
      message.info('保存中，请等待平台回读');
      return false;
    }
    return (
      !state?.dirty || window.confirm('当前分类尚未保存，确定放弃并离开吗？')
    );
  }

  const leaveRef = useRef(canLeave);
  leaveRef.current = canLeave;
  useEffect(() => {
    const unblock = history.block((tx) => {
      void leaveRef.current().then((allowed) => {
        if (allowed) {
          unblock();
          tx.retry();
        }
      });
    });
    return unblock;
  }, [assignmentId]);

  useEffect(() => {
    ++requestVersion.current;
    const current = requestVersion.current;
    const abort = new AbortController();
    setWork(undefined);
    setIdentity(undefined);
    setSelectedId('');
    setDraft(undefined);
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
    if (!dirty && !saving && !submitting && !preparing) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, saving, submitting, preparing]);

  async function save(input: SaveInput): Promise<Draft> {
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
      throw new Error('当前任务不能保存，请重新读取状态');
    const current = requestVersion.current;
    const sampleId = selected.sampleId;
    savingLock.current = true;
    setSaving(true);
    setDraftError('');
    try {
      const response = confirmedDraft(
        await tasks.save(
          assignmentId,
          sampleId,
          input.baseRevision,
          input.result.labelId || '',
        ),
        assignmentId,
        sampleId,
      );
      const value = confirmedDraft(
        await tasks.draft(assignmentId, sampleId),
        assignmentId,
        sampleId,
      );
      if (current !== requestVersion.current)
        throw new Error('视图已变化，忽略旧保存响应');
      if (
        value.revision !== response.revision ||
        value.revision <= input.baseRevision ||
        value.result.labelId !== input.result.labelId ||
        value.explicitDone !== input.explicitDone
      )
        throw new Error('保存与持久化回读不一致，请重新读取核对');
      setDraft(value);
      setWork((previous) =>
        previous
          ? {
              ...previous,
              status: 'IN_PROGRESS',
              items: previous.items.map((item) =>
                item.sampleId === sampleId
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
          ? '当前图片草稿已保存并读回，尚未交回该包'
          : '已保存未完成草稿',
      );
      return value;
    } catch (e) {
      if (current === requestVersion.current) {
        setNeedsReadback(true);
        setDraftError(`${describe(e)}。请重新读取平台草稿；不自动重放或覆盖。`);
      }
      throw e;
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
      message.success('本包人工结果已交回，冻结只读；等待需求方审核');
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

  async function submit() {
    if (
      preparingLock.current ||
      submittingLock.current ||
      editorRef.current?.getState().dirty ||
      editorRef.current?.getState().saving ||
      !work ||
      !identity ||
      dirty ||
      needsReadback ||
      needsRefresh ||
      recoveryError
    )
      return;
    preparingLock.current = true;
    setPreparing(true);
    const version = requestVersion.current;
    const unlock = () => {
      preparingLock.current = false;
      setPreparing(false);
    };
    try {
      const fresh = pending ? work : await allWorkItems(assignmentId);
      if (version !== requestVersion.current) {
        unlock();
        return;
      }
      const attempt = pending || {
        actorId: identity.actorId,
        assignmentId,
        key: operationKey(),
        items: submissionManifest(fresh.items, fresh.specification.labels),
      };
      modal.confirm({
        title: pending ? '确认上一次整包提交结果' : '提交当前领取包？',
        content: pending
          ? '重放原幂等键和版本清单，不创建第二份提交。'
          : `已从服务器读取本包全部 ${attempt.items.length} 张图片的保存版本。仅交回本包，其他包不等待；交回不代表需求方审核通过。`,
        okText: pending ? '确认本次提交' : '确认提交',
        cancelText: '取消',
        onCancel: unlock,
        onOk: async () => {
          try {
            if (version !== requestVersion.current)
              throw new Error('视图已变化，请重新读取');
            await sendSubmission(attempt);
          } finally {
            unlock();
          }
        },
      });
    } catch (e) {
      setError(describe(e));
      unlock();
    }
  }

  const editingDisabled =
    preparing ||
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
        <Navigation beforeAccountAction={canLeave} />
        <h1>图片分类标注</h1>
        {error && (
          <Alert
            type="error"
            showIcon
            message={error}
            style={{ marginBottom: 16 }}
          />
        )}
        {draftError && (
          <Alert
            type="error"
            showIcon
            message={draftError}
            action={
              <Button
                disabled={draftLoading}
                onClick={() => setDraftRetry((n) => n + 1)}
              >
                重新读取草稿
              </Button>
            }
          />
        )}
        {draftLoading && (
          <Spin tip="正在读取当前图片的平台草稿">
            <div style={{ height: 60 }} />
          </Spin>
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
                  {readOnly ? '已交回 · 待需求方审核 · 只读' : '标注中'}
                </Tag>
                <Text>
                  草稿已完成 {completed} / {work.items.length} 张
                </Text>
                <Button
                  disabled={saving || submitting}
                  onClick={() => {
                    void canLeave().then((ok) => {
                      if (ok) setRefresh(refresh + 1);
                    });
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
                  loading={submitting || preparing}
                  disabled={
                    saving ||
                    preparing ||
                    loading ||
                    dirty ||
                    needsReadback ||
                    needsRefresh ||
                    recoveryError ||
                    (!pending && (!complete || Boolean(draftError)))
                  }
                  onClick={() => void submit()}
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
              <Paragraph
                ellipsis={{ rows: 4, expandable: true, symbol: '展开说明' }}
                style={{ whiteSpace: 'pre-wrap', marginTop: 16 }}
              >
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
                  scroll={{ x: 300, y: 480 }}
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
                            if (item.sampleId !== selectedId)
                              void canLeave().then((ok) => {
                                if (ok) setSelectedId(item.sampleId);
                              });
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
              <Card
                title={
                  <Paragraph
                    ellipsis={{
                      rows: 2,
                      expandable: true,
                      tooltip: selected?.sourcePath,
                      symbol: '展开文件名',
                    }}
                    style={{ margin: 0, overflowWrap: 'anywhere' }}
                  >
                    {selected?.sourcePath || '图片预览'}
                  </Paragraph>
                }
              >
                {selected && (
                  <>
                    <Space wrap style={{ marginBottom: 16 }}>
                      {[-1, 1].map((delta) => {
                        const index = work.items.findIndex(
                          (item) => item.sampleId === selectedId,
                        );
                        const next = work.items[index + delta];
                        return (
                          <Button
                            key={delta}
                            disabled={!next || saving || submitting}
                            onClick={async () => {
                              if (await canLeave())
                                setSelectedId(next.sampleId);
                            }}
                          >
                            {delta < 0 ? '上一张' : '下一张'}
                          </Button>
                        );
                      })}
                      <Text>
                        第{' '}
                        {work.items.findIndex(
                          (item) => item.sampleId === selectedId,
                        ) + 1}{' '}
                        / {work.items.length} 张
                      </Text>
                      <Button
                        disabled={saving || submitting || draftLoading}
                        onClick={() => {
                          if (
                            !editorRef.current?.getState().dirty ||
                            window.confirm(
                              '重新读取将替换未保存选择，是否继续？',
                            )
                          )
                            setDraftRetry((n) => n + 1);
                        }}
                      >
                        重新读取草稿
                      </Button>
                    </Space>
                    {draft?.sampleId === selected.sampleId && !draftLoading && (
                      <WorkImage
                        key={`${assignmentId}:${selected.sampleId}`}
                        assignmentId={assignmentId}
                        sample={selected}
                        render={(url) => (
                          <ClassificationEditorAdapter
                            key={`${assignmentId}:${selected.sampleId}:${work.specification.specVersionId}`}
                            ref={editorRef}
                            assignmentId={assignmentId}
                            sampleId={selected.sampleId}
                            specVersionId={work.specification.specVersionId}
                            imageUrl={url}
                            labels={work.specification.labels}
                            instructions={work.specification.instructions}
                            draft={draft}
                            reference={
                              selected.referenceResult &&
                              selected.referenceResultId &&
                              selected.sourceKind
                                ? {
                                    resultId: selected.referenceResultId,
                                    result: selected.referenceResult,
                                    sourceKind: selected.sourceKind,
                                    modelVersion: selected.modelVersion,
                                    confidence: selected.confidence,
                                  }
                                : null
                            }
                            readOnly={Boolean(readOnly)}
                            disabled={editingDisabled}
                            onStateChange={setEditorState}
                            onSave={save}
                            onError={(value) => {
                              setNeedsReadback(true);
                              setDraftError(value.message);
                            }}
                          />
                        )}
                      />
                    )}
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
