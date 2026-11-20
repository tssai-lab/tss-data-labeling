import { Alert, Spin } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  classificationLabelConfig,
  classificationSaveInput,
  classificationToChoices,
} from '@/services/classificationEditor.mjs';
import {
  describeLabelingError,
  labelingJson,
  sampleBlob,
} from '@/services/labeling';
import type { EnergyInput, EnergyResult } from '@/services/labelingEnergy.mjs';
import {
  categoryOf,
  type NativeProject,
  type NativeSpec,
} from '@/services/nativeLabeling';
import { isLegacyProjectSpec } from '@/services/projectCompatibility.mjs';
import LocalEditor from './LocalEditor';
import NativeEditor from './NativeEditor';
import { EnergyTaskEditor } from './TaskEditorHost';

export default function ProjectSampleEditor({
  project,
  version,
  sampleId,
  result,
  onSave,
  onGuard,
}: {
  project: NativeProject;
  version: NativeSpec;
  sampleId: string;
  result?: any;
  onSave?: (result: any) => Promise<void>;
  onGuard?: (guard: () => boolean) => void;
}) {
  const legacy = isLegacyProjectSpec(version, project.taskType);
  const [image, setImage] = useState('');
  const [energy, setEnergy] = useState<EnergyInput>();
  const [error, setError] = useState('');
  const state = useRef({ dirty: false, saving: false });
  const initial = useMemo(() => {
    if (!legacy || project.taskType !== 'IMAGE_CLASSIFICATION')
      return undefined;
    try {
      return {
        config: classificationLabelConfig(version.labels),
        result: classificationToChoices(result?.labelId || '', version.labels),
      };
    } catch (reason) {
      return { error: describeLabelingError(reason) };
    }
  }, [legacy, project.taskType, version, result]);
  const imageTask = useMemo(
    () => ({ id: sampleId, data: { image } }),
    [sampleId, image],
  );
  useEffect(() => {
    setImage('');
    setEnergy(undefined);
    setError('');
    if (!legacy) return;
    const controller = new AbortController();
    let url = '';
    void (async () => {
      if (project.taskType === 'IMAGE_CLASSIFICATION') {
        const blob = await sampleBlob(
          project.projectId,
          sampleId,
          controller.signal,
        );
        if (controller.signal.aborted) return;
        url = URL.createObjectURL(blob);
        setImage(url);
      } else {
        const input = await labelingJson<EnergyInput>(
          `/projects/${project.projectId}/samples/${sampleId}/data`,
          { signal: controller.signal },
        );
        if (!controller.signal.aborted) setEnergy(input);
      }
    })().catch((reason) => {
      if (!controller.signal.aborted) setError(describeLabelingError(reason));
    });
    return () => {
      controller.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [legacy, project.projectId, project.taskType, sampleId]);
  useEffect(() => {
    if (!legacy || project.taskType !== 'TIMESERIES_ANNOTATION') return;
    onGuard?.(
      () =>
        !state.current.saving &&
        (!state.current.dirty ||
          window.confirm('当前修订尚未保存，是否放弃并离开？')),
    );
    const warn = (event: BeforeUnloadEvent): void => {
      if (state.current.dirty || state.current.saving) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => {
      window.removeEventListener('beforeunload', warn);
      onGuard?.(() => true);
    };
  }, [legacy, project.taskType, sampleId]);
  if (!legacy)
    return (
      <NativeEditor
        compact
        path={`/projects/${project.projectId}/samples/${sampleId}/task?specVersionId=${version.specVersionId}`}
        sampleId={sampleId}
        category={categoryOf(project)}
        result={result?.result || []}
        emptyReason={result?.emptyReason}
        onGuard={onGuard}
        reviewMode
        onSave={
          onSave
            ? async (values, done, emptyReason) => {
                if (!done) throw new Error('请完成修订后保存');
                await onSave({
                  result: values,
                  ...(emptyReason ? { emptyReason } : {}),
                });
              }
            : undefined
        }
      />
    );
  if (error || initial?.error)
    return <Alert type="error" message={error || initial?.error} />;
  if (project.taskType === 'IMAGE_CLASSIFICATION')
    return image && initial?.config ? (
      <LocalEditor
        compact
        category="Computer Vision"
        config={initial.config}
        nativeTask={imageTask}
        sample={{ id: sampleId, name: sampleId, input: undefined }}
        result={initial.result}
        instructions={version.instructions}
        reviewMode
        onGuard={onGuard}
        onSave={
          onSave
            ? async (raw, done) => {
                if (!done) throw new Error('请完成修订后保存');
                await onSave(
                  classificationSaveInput(raw, version.labels, 0, true).result,
                );
              }
            : undefined
        }
      />
    ) : (
      <Spin />
    );
  return energy ? (
    <EnergyTaskEditor
      binding={version}
      input={energy}
      labels={version.labels}
      draft={{
        revision: 1,
        explicitDone: true,
        resultFormat: 'annotation-record-v1',
        result:
          result ||
          ({ schemaVersion: '1.0.0', annotations: [] } as EnergyResult),
      }}
      readOnly={!onSave}
      allowIncomplete={false}
      onStateChange={(next) => {
        state.current = next;
      }}
      onSave={async (value) => {
        if (!onSave) throw new Error('当前结果只读');
        await onSave(value.result);
        state.current = { dirty: false, saving: false };
        return {
          assignmentId: '',
          sampleId,
          revision: value.baseRevision + 1,
          explicitDone: true,
          resultFormat: 'annotation-record-v1',
          result: value.result,
        };
      }}
    />
  ) : (
    <Spin />
  );
}
