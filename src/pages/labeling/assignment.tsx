import { LeftOutlined, RightOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { history, Link, useParams } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Drawer,
  Input,
  Image,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { type ComponentType, type ReactNode, useEffect, useRef, useState } from 'react';
import {
  assignmentSampleBlob,
  LabelingError,
  labelingJson,
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
import { assertClassificationWork, resolveTaskTemplate } from '@/services/labelingTemplates.mjs';
import type {
  ClassificationEditorHandle,
  EditorState,
  SaveInput,
} from './ClassificationEditorAdapter';
import EnergyAssignment from './EnergyAssignment';
import styles from './index.less';
import workspace from './annotationWorkspace.less';
import { ClassificationTaskEditor } from './TaskEditorHost';
import WorkspaceLayout from './WorkspaceLayout';

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

function ClassificationAssignment() {
  const { assignmentId = '' } = useParams<{ assignmentId: string }>();
  const { message, modal } = App.useApp();
  const editorRef = useRef<ClassificationEditorHandle>(null);
  const [editorState, setEditorState] = useState<EditorState>();
  const [listOpen, setListOpen] = useState(false);
  const [instructionsOpen, setInstructionsOpen] = useState(false);
  const [sampleSearch, setSampleSearch] = useState('');
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
    work?.readOnly ||
    ['SUBMITTED', 'RELEASED', 'REVOKED'].includes(work?.status || '') ||
    Boolean(receiptId);
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
        assertClassificationWork(value);
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
          input.explicitDone,
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
      assertClassificationWork(fresh);
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
  const selectedIndex = work?.items.findIndex((item) => item.sampleId === selectedId) ?? -1;
  const navigationDisabled = saving || submitting || preparing || Boolean(editorState?.saving);
  async function changeSample(id: string): Promise<void> {
    if (id === selectedId) { setListOpen(false); return; }
    if (await canLeave()) { setSelectedId(id); setListOpen(false); }
  }
  const submissionAction = (!readOnly || pending) && (
    <Button
      loading={submitting || preparing}
      disabled={saving || preparing || loading || dirty || needsReadback || needsRefresh || recoveryError || (!pending && (!complete || Boolean(draftError)))}
      onClick={() => void submit()}
    >{pending ? '确认本次提交' : '提交整个任务包'}</Button>
  );
  return (
    <WorkspaceLayout workbench focusMode beforeAccountAction={canLeave}>
      <div className={workspace.workspace}>
        <div className={workspace.toolbar}>
          <div className={workspace.toolbarGroup}>
            <Link to="/labeling/assignments">返回我的任务</Link>
            <Button icon={<UnorderedListOutlined />} disabled={!work || navigationDisabled} onClick={() => setListOpen(true)}>任务包样本</Button>
            <Button icon={<LeftOutlined />} aria-label="上一条样本" disabled={navigationDisabled || selectedIndex <= 0} onClick={() => { if (work) void changeSample(work.items[selectedIndex - 1].sampleId); }} />
            <Select
              className={workspace.sampleSelect}
              aria-label="当前标注样本"
              showSearch
              optionFilterProp="label"
              value={selectedId || undefined}
              disabled={!work || navigationDisabled}
              onChange={(id) => void changeSample(id)}
              options={work?.items.map((item) => ({ value: item.sampleId, label: item.sourcePath })) || []}
            />
            <Button icon={<RightOutlined />} aria-label="下一条样本" disabled={navigationDisabled || !work || selectedIndex >= work.items.length - 1} onClick={() => { if (work) void changeSample(work.items[selectedIndex + 1].sampleId); }} />
            {work && <span className={workspace.position}>{selectedIndex + 1} / {work.items.length}</span>}
          </div>
          <div className={workspace.toolbarGroup}>
            {work && <Tag>{completed} / {work.items.length} 个样本已完成</Tag>}
            {readOnly && <Tag>{work?.status === 'RELEASED' || work?.status === 'REVOKED' ? '领取已结束 · 只读' : '已交回 · 待需求方审核 · 只读'}</Tag>}
            <Button disabled={!work || navigationDisabled} onClick={() => setInstructionsOpen(true)}>规范与说明</Button>
            <Button disabled={navigationDisabled} onClick={() => { void canLeave().then((ok) => { if (ok) setRefresh((value) => value + 1); }); }}>重新读取任务</Button>
            <Button disabled={navigationDisabled || draftLoading || !selected} onClick={() => { if (!editorRef.current?.getState().dirty || window.confirm('重新读取将替换未保存选择，是否继续？')) setDraftRetry((value) => value + 1); }}>重新读取草稿</Button>
            {receiptId && <Link to={`/labeling/submissions/${encodeURIComponent(receiptId)}`}>查看已交回包</Link>}
          </div>
        </div>
        {error && <Alert className={workspace.message} type="error" showIcon message={error} />}
        {draftError && <Alert className={workspace.message} type="error" showIcon message={draftError} action={<Button disabled={draftLoading} onClick={() => setDraftRetry((value) => value + 1)}>重新读取草稿</Button>} />}
        {pending && !receiptId && <Alert className={workspace.message} type="warning" showIcon message="上一次提交结果待确认，编辑暂时锁定" description="可重放原版本清单与幂等键，确认服务器是否已经接受。" action={submissionAction} />}
        {(loading || draftLoading) && <Spin tip={loading ? '正在读取任务包全部图片' : '正在读取当前图片的平台草稿'}><div style={{ height: 180 }} /></Spin>}
        {!loading && !work && <Button onClick={() => setRefresh((value) => value + 1)}>重新加载任务</Button>}
        {work && !work.items.length && <Alert type="info" message="当前任务包没有样本。" />}
        {work && selected && draft?.sampleId === selected.sampleId && !draftLoading && (
          <WorkImage
            key={`${assignmentId}:${selected.sampleId}`}
            assignmentId={assignmentId}
            sample={selected}
            render={(url) => (
              <ClassificationTaskEditor
                binding={work.specification}
                key={`${assignmentId}:${selected.sampleId}:${work.specification.specVersionId}`}
                ref={editorRef}
                assignmentId={assignmentId}
                sampleId={selected.sampleId}
                specVersionId={work.specification.specVersionId}
                imageUrl={url}
                labels={work.specification.labels}
                instructions={work.specification.instructions}
                draft={draft}
                reference={selected.referenceResult ? { result: selected.referenceResult.result, sourceKind: selected.referenceResult.sourceKind } : null}
                readOnly={Boolean(readOnly)}
                disabled={editingDisabled}
                onStateChange={setEditorState}
                onSave={save}
                footerActions={!pending ? submissionAction : undefined}
                onError={(value) => { setNeedsReadback(true); setDraftError(value.message); }}
              />
            )}
          />
        )}
        <Drawer title="任务包样本" placement="left" width={360} open={listOpen} onClose={() => setListOpen(false)}>
          <Input.Search aria-label="搜索任务包样本" placeholder="搜索样本" allowClear value={sampleSearch} onChange={(event) => setSampleSearch(event.target.value)} />
          <ul className={workspace.sampleList}>
            {work?.items.filter((item) => item.sourcePath.toLowerCase().includes(sampleSearch.toLowerCase())).map((item) => (
              <li key={item.sampleId}><button className={workspace.sampleButton} type="button" disabled={navigationDisabled} aria-current={item.sampleId === selectedId ? 'true' : undefined} onClick={() => void changeSample(item.sampleId)}><span title={item.sourcePath}>{item.sourcePath}</span><Tag>{item.explicitDone ? '已完成' : item.draftRevision ? '草稿' : '待标注'}</Tag></button></li>
            ))}
          </ul>
        </Drawer>
        <Drawer title="规范与说明" width={480} open={instructionsOpen} onClose={() => setInstructionsOpen(false)}>
          <Typography.Title level={5}>本任务绑定的分类规范</Typography.Title>
          <Space wrap>{work?.specification.labels.map((label) => <Tag key={label.id}>{label.name}</Tag>)}</Space>
          <Typography.Paragraph className={workspace.instructions} style={{ marginTop: 16 }}>{work?.specification.instructions || '暂无补充说明'}</Typography.Paragraph>
          <Typography.Text type="secondary">本包全部图片保存有效分类后才可提交，提交后仍需需求方核查。</Typography.Text>
        </Drawer>
      </div>
    </WorkspaceLayout>
  );
}

export default function AssignmentEntry() {
  const { assignmentId = '' } = useParams<{ assignmentId: string }>();
  const [kind, setKind] = useState(''),
    [error, setError] = useState('');
  useEffect(() => {
    const abort = new AbortController();
    setKind('');
    setError('');
    void labelingJson<WorkPage>(
      `/assignments/${encodeURIComponent(assignmentId)}/items?limit=20`,
      { signal: abort.signal },
    )
      .then((work) => {
        if (work.assignmentId !== assignmentId)
          throw new Error('任务归属不匹配');
        if (!abort.signal.aborted)
          setKind(resolveTaskTemplate(work.specification).editorKey);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describe(e));
      });
    return () => abort.abort();
  }, [assignmentId]);
  if (error)
    return (
      <WorkspaceLayout>
        <Alert type="error" message={error} />
        <Button onClick={() => history.push('/labeling/assignments')}>
          返回我的任务
        </Button>
      </WorkspaceLayout>
    );
  const registeredWorkspaces: Record<string, ComponentType> = {
    'image-classification-editor': ClassificationAssignment,
    'energy-station-day-editor': EnergyAssignment,
  };
  const Workspace = registeredWorkspaces[kind];
  if (Workspace) return <Workspace key={assignmentId} />;
  return (
    <WorkspaceLayout>
      {kind ? <Alert type="error" message="任务类型尚未支持" /> : <Spin />}
    </WorkspaceLayout>
  );
}
