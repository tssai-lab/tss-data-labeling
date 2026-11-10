import { Alert, Button, Image, Radio, Space, Tag, Typography } from 'antd';
import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { initialClassification } from '@/services/classificationEditor.mjs';
import { describeLabelingError } from '@/services/labeling';
import type { Draft } from '@/services/labelingTasks';
import styles from './index.less';

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
  imageUrl: string;
  labels: ReadonlyArray<{ id: string; name: string }>;
  instructions: string;
  draft: ClassificationDraft;
  reference: {
    resultId: string;
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

/** Native platform controls implement the public C/D contract. D's cropped LS renderer remains pending. */
export default forwardRef<
  ClassificationEditorHandle,
  ClassificationEditorProps
>(function ClassificationEditorAdapter(props, ref) {
  const initial = initialClassification(
    props.draft,
    props.reference,
    props.labels,
  );
  const [labelId, setLabelId] = useState(initial.labelId);
  const [touched, setTouched] = useState(false);
  const [saving, setSaving] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState('');
  const generation = useRef(0);
  const lock = useRef(false);
  const notify = useRef(props.onStateChange);
  notify.current = props.onStateChange;
  const valid = Boolean(labelId && props.labels.some((l) => l.id === labelId));
  const state: EditorState = {
    assignmentId: props.assignmentId,
    sampleId: props.sampleId,
    specVersionId: props.specVersionId,
    dirty:
      touched &&
      (labelId !== (props.draft.result.labelId || '') ||
        Boolean(labelId) !== props.draft.explicitDone),
    saving,
    valid,
    explicitDone: touched ? valid : props.draft.explicitDone,
    revision: props.draft.revision,
  };
  const live = useRef(state);
  live.current = state;
  useImperativeHandle(ref, () => ({
    getState: () => ({
      ...live.current,
      saving: lock.current || live.current.saving,
    }),
  }));
  useEffect(() => {
    notify.current(state);
  }, [
    state.assignmentId,
    state.sampleId,
    state.specVersionId,
    state.dirty,
    state.saving,
    state.valid,
    state.explicitDone,
    state.revision,
  ]);
  useEffect(() => {
    ++generation.current;
    const next = initialClassification(
      props.draft,
      props.reference,
      props.labels,
    );
    setLabelId(next.labelId);
    setTouched(false);
    setUncertain(false);
    setError('');
    setSaving(false);
    lock.current = false;
    return () => {
      ++generation.current;
    };
  }, [props.assignmentId, props.sampleId, props.specVersionId, props.draft]);
  async function save() {
    if (
      props.readOnly ||
      props.disabled ||
      lock.current ||
      uncertain ||
      (labelId && !valid)
    )
      return;
    const current = generation.current;
    lock.current = true;
    setSaving(true);
    setError('');
    try {
      const input: SaveInput = {
        baseRevision: props.draft.revision,
        explicitDone: valid,
        resultFormat: 'image-classification-v1',
        result: valid ? { labelId } : {},
      };
      const result = await props.onSave(input);
      if (current !== generation.current) return;
      if (
        result.assignmentId !== props.assignmentId ||
        result.sampleId !== props.sampleId ||
        !Number.isSafeInteger(result.revision) ||
        result.revision <= input.baseRevision ||
        result.resultFormat !== 'image-classification-v1' ||
        result.result.labelId !== input.result.labelId ||
        result.explicitDone !== input.explicitDone
      )
        throw new Error('草稿回执不匹配，请重新读取');
      setTouched(false);
    } catch (e) {
      if (current === generation.current) {
        setUncertain(true);
        setError(describeLabelingError(e));
        props.onError({
          code: 'DRAFT_SAVE_UNCONFIRMED',
          message: describeLabelingError(e),
        });
      }
    } finally {
      if (current === generation.current) {
        lock.current = false;
        setSaving(false);
      }
    }
  }
  return (
    <div>
      <div className={styles.preview}>
        {props.imageUrl && (
          <Image
            src={props.imageUrl}
            alt="当前领取图片"
            style={{ maxWidth: '100%', maxHeight: 520, objectFit: 'contain' }}
          />
        )}
      </div>
      {props.instructions && (
        <Typography.Paragraph
          ellipsis={{ rows: 4, expandable: true, symbol: '展开说明' }}
          style={{ whiteSpace: 'pre-wrap', marginTop: 16 }}
        >
          {props.instructions}
        </Typography.Paragraph>
      )}
      {initial.referenceShown && !touched && (
        <Alert
          type="info"
          showIcon
          message={`${props.reference?.sourceKind || ''}参考结果 · 待人工确认`}
          description={`模型 ${props.reference?.modelVersion || '不适用'} · 置信度 ${props.reference?.confidence ?? '未知'}。仅作参考，不自动计为本轮完成。`}
        />
      )}
      {error && (
        <Alert
          type="error"
          message={error}
          description="保留当前选择，请通过宿主重新读取平台草稿核对。"
        />
      )}
      <div className={styles.classification}>
        <Space wrap>
          <Typography.Text strong>当前分类</Typography.Text>
          <Tag
            color={props.readOnly ? 'default' : state.dirty ? 'orange' : 'blue'}
          >
            {props.readOnly
              ? '只读 · 已交回不等于已审核'
              : saving
                ? '保存并回读中'
                : state.dirty
                  ? '未保存'
                  : `平台 revision ${props.draft.revision}`}
          </Tag>
        </Space>
        <Radio.Group
          aria-label="图片分类"
          value={labelId}
          disabled={props.readOnly || props.disabled || saving || uncertain}
          onChange={(e) => {
            setLabelId(e.target.value);
            setTouched(true);
          }}
          options={props.labels.map((l) => ({ value: l.id, label: l.name }))}
          optionType="button"
        />
        {!props.readOnly && (
          <Space wrap>
            <Button
              type="primary"
              loading={saving}
              disabled={props.disabled || uncertain}
              onClick={() => void save()}
            >
              {initial.referenceShown && !touched
                ? '确认参考并保存草稿'
                : '保存当前草稿'}
            </Button>
            <Button
              disabled={props.disabled || saving || uncertain || !labelId}
              onClick={() => {
                setLabelId('');
                setTouched(true);
              }}
            >
              清空选择
            </Button>
          </Space>
        )}
        <Typography.Text type="secondary">
          空选择可保存为未完成草稿；本包全部图片保存有效分类后才可交回。交回后仍需需求方核查。
        </Typography.Text>
      </div>
    </div>
  );
});
