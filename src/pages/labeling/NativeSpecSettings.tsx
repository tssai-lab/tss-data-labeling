import { Alert, Card, Checkbox, Input, Select, Space, Typography } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import { labelingJson, operationKey, post } from '@/services/labeling';
import {
  categoryOf,
  type InputResource,
  type NativeProject,
  type NativeSample,
  type NativeSpec,
  nativeDefaults,
} from '@/services/nativeLabeling';
import LocalEditor from './LocalEditor';
import NativeEditor from './NativeEditor';

export default function NativeSpecSettings({
  project,
  samples,
  version,
  suggestion,
  refresh,
  onGuard,
  resources = [],
}: {
  resources?: InputResource[];
  project: NativeProject;
  samples: NativeSample[];
  version?: NativeSpec;
  suggestion: string;
  refresh: (specId?: string) => void;
  run: (action: () => Promise<void>) => Promise<void>;
  onGuard: (guard: () => boolean) => void;
}) {
  const instructionsId = useId();
  const [xml, setXml] = useState(''),
    [instructions, setInstructions] = useState(''),
    [allowEmpty, setAllowEmpty] = useState(false),
    [sampleId, setSampleId] = useState('');
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  const metadataDirty = useRef(false),
    editorGuard = useRef<() => boolean>(() => true);
  const installGuard = (fn: () => boolean) => {
    editorGuard.current = fn;
    onGuard(
      () =>
        editorGuard.current() &&
        (!metadataDirty.current ||
          window.confirm('标注说明或完成规则尚未保存，是否放弃并离开？')),
    );
  };
  useEffect(
    () => () => {
      metadataDirty.current = false;
      onGuard(() => true);
    },
    [project.projectId],
  );
  useEffect(() => {
    let active = true;
    const seed =
      suggestion ||
      version?.editorConfig.labelConfig ||
      sessionStorage.getItem(`native-config-draft:${project.projectId}`);
    const fallback =
      nativeDefaults[project.taskType as keyof typeof nativeDefaults] ||
      nativeDefaults.COMPUTER_VISION;
    if (seed || !samples.length || project.taskType !== 'TIME_SERIES')
      setXml(seed || fallback);
    else {
      setXml('');
      void labelingJson<any>(
        `/projects/${project.projectId}/samples/${samples[0].sampleId}/native-input`,
      )
        .then((context) => {
          const data = context.task.data;
          const entry = Object.entries(data).find(
            ([, value]) => value && typeof value === 'object',
          );
          const doc = new DOMParser().parseFromString(
              fallback,
              'application/xml',
            ),
            object = doc.querySelector('TimeSeries');
          if (entry && object) {
            const [field, value] = entry as [string, any],
              hint = context.task.meta?.mapping;
            const headers = Array.isArray(value)
              ? Object.keys(value[0] || {})
              : Object.keys(value).filter((key) => Array.isArray(value[key]));
            const time =
              hint?.timeColumn ||
              headers.find((key) =>
                ['time', 't', 'timestamp'].includes(key.toLowerCase()),
              ) ||
              headers[0];
            if (time) {
              object.setAttribute('value', `$${field}`);
              object.setAttribute('timeColumn', time);
              object.replaceChildren();
              if (hint?.timeType === 'timestamp') {
                object.setAttribute('timeFormat', '%Q');
                object.setAttribute('timeDisplayFormat', '%Y-%m-%d %H:%M:%S');
              }
              for (const column of hint?.channelColumns ||
                headers.filter((name) => name !== time)) {
                const channel = doc.createElement('Channel');
                channel.setAttribute('column', column);
                object.append(channel);
              }
            }
          }
          if (!entry && object) {
            const reference = context.resources?.find(
              (resource: any) => resource.mediaType === 'text/csv',
            );
            const profile = resources.find(
              (resource) => resource.resourceId === reference?.resourceId,
            );
            const headers = profile?.metadata.columns || [];
            if (reference && headers.length) {
              const time =
                headers.find((name) =>
                  ['time', 't', 'timestamp', 'date', 'datetime'].includes(
                    name.toLowerCase(),
                  ),
                ) || headers[0];
              object.setAttribute('value', `$${reference.field}`);
              object.setAttribute('valueType', 'url');
              object.setAttribute('timeColumn', time);
              object.setAttribute('sep', profile?.metadata.delimiter || ',');
              object.replaceChildren();
              for (const name of headers.filter((name) => name !== time)) {
                const channel = doc.createElement('Channel');
                channel.setAttribute('column', name);
                object.append(channel);
              }
            }
          }
          if (active) setXml(new XMLSerializer().serializeToString(doc));
        })
        .catch(() => {
          if (active) setXml(fallback);
        });
    }
    setInstructions(version?.instructions || '');
    setAllowEmpty(!!version?.editorConfig.completion?.allowEmpty);
    metadataDirty.current = false;
    return () => {
      active = false;
    };
  }, [
    project.projectId,
    version?.specVersionId,
    suggestion,
    samples[0]?.sampleId,
  ]);
  const save = async (config: string, templateId: string) => {
    // Keep the editor's promise rejected on failure so it retains the unsaved guard.
    const saved = await post<NativeSpec>(
      `/projects/${project.projectId}/spec-versions`,
      {
        taskType: project.taskType,
        schemaVersion: 'label-studio-v1',
        labels: [],
        instructions,
        editorConfig: {
          labelConfig: config,
          templateId,
          completion: { allowEmpty },
        },
      },
      operationKey(),
    );
    metadataDirty.current = false;
    sessionStorage.removeItem(`native-config-draft:${project.projectId}`);
    refresh(saved.specVersionId);
  };
  const selected =
    samples.find((sample) => sample.sampleId === sampleId) || samples[0];
  return (
    <Card title="标注设置">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {locked ? (
          <Alert message="当前项目只读，无法修改标注设置。" />
        ) : (
          <Typography.Paragraph>
            选择模板并配置标签。保存为新规范版本，已发布任务仍使用原版本。
          </Typography.Paragraph>
        )}
        <label htmlFor={instructionsId}>
          标注说明
          <Input.TextArea
            id={instructionsId}
            aria-label="标注说明"
            value={instructions}
            readOnly={locked}
            onChange={(e) => {
              metadataDirty.current = true;
              setInstructions(e.target.value);
            }}
          />
        </label>
        <Checkbox
          checked={allowEmpty}
          disabled={locked}
          onChange={(e) => {
            metadataDirty.current = true;
            setAllowEmpty(e.target.checked);
          }}
        >
          允许无标注结果（完成时必须填写原因）
        </Checkbox>
        {!!samples.length && (
          <Select
            aria-label="设置预览样本"
            style={{ width: '100%' }}
            value={selected?.sampleId}
            onChange={setSampleId}
            options={samples.map((sample) => ({
              value: sample.sampleId,
              label: sample.metadata.displayName || sample.sourcePath,
            }))}
          />
        )}
        {xml &&
          (locked ? (
            <Input.TextArea
              aria-label="已冻结的标注配置"
              value={xml}
              readOnly
              autoSize={{ minRows: 8, maxRows: 24 }}
            />
          ) : selected ? (
            <NativeEditor
              key={`${version?.specVersionId || 'draft'}:${suggestion}`}
              path={`/projects/${project.projectId}/samples/${selected.sampleId}/native-input`}
              sampleId={selected.sampleId}
              configProjectId={project.projectId}
              category={categoryOf(project)}
              config={xml}
              templateId={version?.editorConfig.templateId}
              onConfig={save}
              onGuard={installGuard}
            />
          ) : (
            <LocalEditor
              config={xml}
              configProjectId={project.projectId}
              category={categoryOf(project)}
              onConfig={save}
              onGuard={installGuard}
            />
          ))}
      </Space>
    </Card>
  );
}
