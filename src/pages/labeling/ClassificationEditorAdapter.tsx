import { Alert } from 'antd';
import { type ReactNode, forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { classificationFromChoices, classificationLabelConfig, classificationSaveInput, classificationToChoices, initialClassification } from '@/services/classificationEditor.mjs';
import { describeLabelingError } from '@/services/labeling';
import type { Draft } from '@/services/labelingTasks';
import LocalEditor, { type LocalEditorHandle, type LocalEditorState } from './LocalEditor';

export type EditorIdentity = {
  assignmentId: string;
  sampleId: string;
  specVersionId: string;
};
export type ClassificationDraft = {
  revision: number;
  explicitDone: boolean;
  resultFormat: 'image-classification-v1';
  result: { labelId?: string };
};
export type SaveInput = {
  baseRevision: number;
  explicitDone: boolean;
  resultFormat: 'image-classification-v1';
  result: { labelId?: string };
};
export type EditorState = EditorIdentity & {
  dirty: boolean;
  saving: boolean;
  valid: boolean;
  explicitDone: boolean;
  revision: number;
};
export type ClassificationEditorProps = EditorIdentity & {
  footerActions?: ReactNode;
  imageUrl: string;
  labels: ReadonlyArray<{ id: string; name: string }>;
  instructions: string;
  draft: ClassificationDraft;
  reference: {
    resultId?: string;
    result: { labelId?: string };
    sourceKind: 'HUMAN' | 'AI' | 'OWNER' | 'AGGREGATION';
    modelVersion?: string;
    confidence?: number | null;
  } | null;
  readOnly: boolean;
  disabled?: boolean;
  onStateChange: (state: EditorState) => void;
  onSave: (input: SaveInput) => Promise<Draft>;
  onError: (error: { code: string; message: string }) => void;
};
export type ClassificationEditorHandle = { getState: () => EditorState };

// 分类任务复用原生标注编辑器，读写时转换为历史任务的稳定标签编号。
export default forwardRef<ClassificationEditorHandle, ClassificationEditorProps>(function ClassificationEditorAdapter(props, ref) {
  const local = useRef<LocalEditorHandle>(null);
  const lock = useRef(false);
  const [uncertain, setUncertain] = useState(false);
  const [editorStatus, setEditorStatus] = useState<LocalEditorState>();
  const initial = initialClassification(props.draft, props.reference, props.labels);
  const prepared = useMemo(() => {
    try {
      const first = initialClassification(props.draft, props.reference, props.labels);
      return { config: classificationLabelConfig(props.labels), result: classificationToChoices(first.labelId, props.labels), error: '' };
    } catch (error) {
      return { config: '', result: [], error: describeLabelingError(error) };
    }
  }, [props.labels, props.draft, props.reference]);
  const task = useMemo(() => ({ id: props.sampleId, data: { image: props.imageUrl } }), [props.sampleId, props.imageUrl]);
  function getState(): EditorState {
    const current = local.current?.getState();
    let valid = Boolean(initial.labelId);
    if (current?.ready) {
      try { classificationFromChoices(current.result, props.labels); valid = true; }
      catch { valid = false; }
    }
    return {
      assignmentId: props.assignmentId,
      sampleId: props.sampleId,
      specVersionId: props.specVersionId,
      dirty: current?.dirty || false,
      saving: lock.current || !!current?.saving,
      valid,
      explicitDone: current?.dirty ? false : props.draft.explicitDone,
      revision: props.draft.revision,
    };
  }
  useImperativeHandle(ref, () => ({ getState }));
  useEffect(() => { props.onStateChange(getState()); }, [editorStatus, props.draft, uncertain]);
  useEffect(() => { setUncertain(false); }, [props.draft]);

  async function save(raw: unknown[], done: boolean): Promise<void> {
    if (props.readOnly || props.disabled || lock.current || uncertain) throw new Error('当前任务不能保存，请先核对草稿状态');
    const input = classificationSaveInput(raw, props.labels, props.draft.revision, done);
    lock.current = true;
    try {
      const result = await props.onSave(input);
      if (result.assignmentId !== props.assignmentId || result.sampleId !== props.sampleId ||
          !Number.isSafeInteger(result.revision) || result.revision <= input.baseRevision ||
          result.resultFormat !== input.resultFormat || result.result.labelId !== input.result.labelId ||
          result.explicitDone !== input.explicitDone) throw new Error('草稿回执不匹配，请重新读取');
    } catch (error) {
      setUncertain(true);
      props.onError({ code: 'DRAFT_SAVE_UNCONFIRMED', message: describeLabelingError(error) });
      throw error;
    } finally { lock.current = false; }
  }
  if (prepared.error) return <Alert type="error" message={prepared.error} />;
  return (
    <>
      {initial.referenceShown && !props.draft.explicitDone && <Alert type="info" showIcon message="上一轮参考结果 · 仍需明确完成并保存" />}
      <LocalEditor
        ref={local}
        compact
        nativeTask={task}
        sample={{ id: props.sampleId, name: props.sampleId, input: undefined }}
        category="Computer Vision"
        config={prepared.config}
        instructions={props.instructions}
        result={prepared.result}
        refreshKey={`${props.draft.revision}:${props.draft.result.labelId || ''}:${props.draft.explicitDone}`}
        disabled={props.disabled || uncertain}
        onSave={props.readOnly ? undefined : save}
        onStateChange={setEditorStatus}
        footerActions={props.footerActions}
      />
    </>
  );
});
