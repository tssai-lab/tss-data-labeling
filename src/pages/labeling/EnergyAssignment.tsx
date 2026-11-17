import { history, useParams } from '@umijs/max';
import { Alert, App, Button, Card, Select, Space, Spin, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  describeLabelingError,
  LabelingError,
  operationKey,
} from '@/services/labeling';
import type { EnergyInput } from '@/services/labelingEnergy.mjs';
import { validateEnergyInput } from '@/services/labelingEnergy.mjs';
import { ownerApi } from '@/services/labelingOwner';
import {
  type StructuredDraft,
  structured,
  verifyStructuredDraft,
} from '@/services/labelingStructured';
import { allWorkItems, tasks, type WorkPage } from '@/services/labelingTasks';
import { resolveTaskTemplate } from '@/services/labelingTemplates.mjs';
import { EnergyTaskEditor } from './TaskEditorHost';
import WorkspaceLayout from './WorkspaceLayout';

export default function EnergyAssignment() {
  const { assignmentId = '' } = useParams<{ assignmentId: string }>();
  const { modal, message } = App.useApp();
  const [work, setWork] = useState<WorkPage>(),
    [sampleId, setSampleId] = useState(''),
    [actor, setActor] = useState('');
  const [input, setInput] = useState<EnergyInput>(),
    [draft, setDraft] = useState<StructuredDraft>();
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(false),
    [refresh, setRefresh] = useState(0);
  const [dirty, setDirty] = useState(false),
    [saving, setSaving] = useState(false),
    [submitting, setSubmitting] = useState(false);
  const [pendingSubmit, setPendingSubmit] = useState(false);
  const version = useRef(0),
    lock = useRef(false),
    state = useRef({ dirty: false, saving: false });
  const pending = useRef<
    { key: string; items: { sampleId: string; revision: number }[] } | undefined
  >(undefined);
  const bookmark = `labeling:energy-submit:${actor}:${assignmentId}`;
  const ended = Boolean(
    work?.readOnly || !['CLAIMED', 'IN_PROGRESS'].includes(work?.status || ''),
  );
  const readOnly = ended || submitting || pendingSubmit;
  useEffect(() => {
    if (!actor) return;
    try {
      const raw = localStorage.getItem(bookmark);
      if (raw) {
        const p = JSON.parse(raw);
        if (
          typeof p.key !== 'string' ||
          !Array.isArray(p.items) ||
          !p.items.length ||
          p.items.some(
            (i: { sampleId: unknown; revision: unknown }) =>
              typeof i.sampleId !== 'string' || !Number.isInteger(i.revision),
          )
        )
          throw new Error('提交恢复记录损坏，请核对本人提交历史');
        pending.current = p;
        setPendingSubmit(true);
      }
    } catch (e) {
      setError(describeLabelingError(e));
    }
  }, [actor, bookmark]);
  useEffect(() => {
    const abort = new AbortController();
    setWork(undefined);
    setError('');
    void Promise.all([
      allWorkItems(assignmentId, abort.signal),
      tasks.me(abort.signal),
    ])
      .then(([value, me]) => {
        const template = resolveTaskTemplate(value.specification);
        if (template.id !== 'energy-station-day')
          throw new Error('任务模板与能耗编辑器不匹配');
        for (const item of value.items)
          resolveTaskTemplate({ ...value.specification, ...item });
        if (!abort.signal.aborted) {
          setWork(value);
          setActor(me.actorId);
          setSampleId((old) =>
            value.items.some((i) => i.sampleId === old)
              ? old
              : value.items[0]?.sampleId || '',
          );
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [assignmentId, refresh]);
  useEffect(() => {
    if (!sampleId || !work) return;
    const abort = new AbortController();
    const token = ++version.current;
    setInput(undefined);
    setDraft(undefined);
    setLoading(true);
    setError('');
    setDirty(false);
    state.current = { dirty: false, saving: false };
    void Promise.all([
      ownerApi.assignmentData(assignmentId, sampleId, abort.signal),
      structured.draft(assignmentId, sampleId, abort.signal),
    ])
      .then(([value, record]) => {
        if (value.sampleId !== sampleId) throw new Error('输入样本不匹配');
        validateEnergyInput(value);
        verifyStructuredDraft(
          record,
          assignmentId,
          value,
          work.specification.labels.map((l) => l.id),
        );
        if (token === version.current && !abort.signal.aborted) {
          setInput(value);
          setDraft(record);
        }
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => {
      ++version.current;
      abort.abort();
    };
  }, [assignmentId, sampleId, work, refresh]);
  useEffect(() => {
    const listener = (e: BeforeUnloadEvent) => {
      if (state.current.dirty || state.current.saving || lock.current) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', listener);
    return () => window.removeEventListener('beforeunload', listener);
  }, []);
  async function leave() {
    if (state.current.saving || lock.current) return false;
    if (!state.current.dirty) return true;
    return new Promise<boolean>((resolve) =>
      modal.confirm({
        title: '当前修改尚未保存，确认离开？',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      }),
    );
  }
  async function submit() {
    if (
      lock.current ||
      (ended && !pending.current) ||
      dirty ||
      saving ||
      !work ||
      !actor
    )
      return;
    lock.current = true;
    setSubmitting(true);
    setError('');
    try {
      let retry = pending.current;
      if (!retry) {
        const saved = localStorage.getItem(bookmark);
        if (saved) {
          const p = JSON.parse(saved);
          if (
            typeof p.key === 'string' &&
            Array.isArray(p.items) &&
            p.items.length &&
            p.items.every(
              (i: { sampleId: unknown; revision: unknown }) =>
                typeof i.sampleId === 'string' && Number.isInteger(i.revision),
            )
          )
            retry = p;
          else throw new Error('提交恢复记录损坏，请核对本人提交历史');
        }
      }
      if (!retry) {
        const current = await allWorkItems(assignmentId);
        if (
          current.readOnly ||
          !['CLAIMED', 'IN_PROGRESS'].includes(current.status) ||
          current.specification.specVersionId !==
            work.specification.specVersionId ||
          current.roundId !== work.roundId ||
          current.items.length !== work.items.length ||
          current.items.some(
            (i) => !work.items.some((old) => old.sampleId === i.sampleId),
          )
        )
          throw new Error('任务包已变化，请重新读取');
        const items: { sampleId: string; revision: number }[] = [];
        for (const sample of current.items) {
          const [value, record] = await Promise.all([
            ownerApi.assignmentData(assignmentId, sample.sampleId),
            structured.draft(assignmentId, sample.sampleId),
          ]);
          verifyStructuredDraft(
            record,
            assignmentId,
            value,
            current.specification.labels.map((l) => l.id),
          );
          if (!record.explicitDone || record.revision < 1)
            throw new Error(`样本 ${sample.sourcePath} 尚未完成并保存`);
          items.push({ sampleId: sample.sampleId, revision: record.revision });
        }
        if (!items.length) throw new Error('任务包为空');
        retry = { key: operationKey(), items };
        pending.current = retry;
        localStorage.setItem(bookmark, JSON.stringify(retry));
      }
      pending.current = retry;
      setPendingSubmit(true);
      const receipt = await tasks.submit(assignmentId, retry.items, retry.key);
      if (receipt.assignmentId !== assignmentId)
        throw new Error('提交回执归属不一致');
      localStorage.removeItem(bookmark);
      pending.current = undefined;
      setPendingSubmit(false);
      message.success('整包结果已交回，等待需求方审核');
      history.push(
        `/labeling/submissions/${encodeURIComponent(receipt.submissionId)}`,
      );
    } catch (e) {
      if (e instanceof LabelingError && [400, 409, 422].includes(e.status)) {
        pending.current = undefined;
        setPendingSubmit(false);
        localStorage.removeItem(bookmark);
      }
      setError(describeLabelingError(e));
    } finally {
      lock.current = false;
      setSubmitting(false);
    }
  }
  const sample = work?.items.find((i) => i.sampleId === sampleId);
  const reference = sample?.referenceResult?.result;
  return (
    <WorkspaceLayout beforeAccountAction={leave}>
      <h1>能耗标注任务</h1>
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          description="保存异常时保留编辑内容，重新读取核对。提交响应丢失时再次提交会复用原请求和幂等键，也可先查看本人提交历史。"
        />
      )}
      <Space wrap>
        <Button
          disabled={saving || submitting}
          onClick={async () => {
            if (await leave()) history.push('/labeling/assignments');
          }}
        >
          返回我的任务
        </Button>
        <Button
          disabled={saving || submitting}
          onClick={async () => {
            if (await leave()) setRefresh((n) => n + 1);
          }}
        >
          重新读取任务与草稿
        </Button>
        <Button
          type="primary"
          disabled={
            !work ||
            (ended && !pendingSubmit) ||
            dirty ||
            saving ||
            loading ||
            !actor ||
            submitting
          }
          loading={submitting}
          onClick={() =>
            modal.confirm({
              title: pendingSubmit
                ? '核对并重放原提交请求？'
                : `交回本包全部 ${work?.items.length || 0} 个样本？`,
              content:
                '必须每个样本均已保存并完成。交回后只读，需求方审核另行进行。重试使用原清单和原幂等键。',
              onOk: submit,
            })
          }
        >
          {pendingSubmit ? '重试原提交' : '提交完整任务包'}
        </Button>
      </Space>
      {work ? (
        <>
          <Card style={{ marginTop: 16 }}>
            <Tag>{readOnly ? '只读' : work.status}</Tag>
            <p>{work.specification.instructions}</p>
            <Select
              aria-label="能耗任务样本"
              value={sampleId || undefined}
              style={{ width: '100%' }}
              disabled={saving || submitting}
              options={work.items.map((i) => ({
                value: i.sampleId,
                label: `${i.explicitDone ? '已保存完成' : '待完成'} · ${i.sourcePath}`,
              }))}
              onChange={async (id) => {
                if (await leave()) setSampleId(id);
              }}
            />
          </Card>
          {loading && <Spin />}
          {input && draft && (
            <EnergyTaskEditor
              key={`${sampleId}:${draft.revision}:${refresh}`}
              input={input}
              labels={work.specification.labels}
              draft={draft}
              readOnly={readOnly}
              reference={
                reference?.schemaVersion && reference.annotations
                  ? {
                      schemaVersion: reference.schemaVersion,
                      annotations: reference.annotations,
                    }
                  : null
              }
              onStateChange={(v) => {
                state.current = v;
                setDirty(v.dirty);
                setSaving(v.saving);
              }}
              onSave={async (body) => {
                if (readOnly) throw new Error('任务只读');
                const saved = await structured.save(
                  assignmentId,
                  sampleId,
                  input.inputHash,
                  body,
                );
                verifyStructuredDraft(
                  saved,
                  assignmentId,
                  input,
                  work.specification.labels.map((l) => l.id),
                );
                setDraft(saved);
                setWork((old) =>
                  old
                    ? {
                        ...old,
                        items: old.items.map((i) =>
                          i.sampleId === sampleId
                            ? {
                                ...i,
                                draftRevision: saved.revision,
                                explicitDone: saved.explicitDone,
                                result: saved.result,
                              }
                            : i,
                        ),
                      }
                    : old,
                );
                return saved;
              }}
            />
          )}
        </>
      ) : (
        !error && <Spin />
      )}
    </WorkspaceLayout>
  );
}
