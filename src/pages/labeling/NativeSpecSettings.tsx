import { Alert, Button, Card, Checkbox, Input, Select, Space, Typography } from 'antd';
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
import LegacyProjectSettings from './LegacyProjectSettings';
import { isLegacyProjectSpec } from '@/services/projectCompatibility.mjs';
import NativeEditor from './NativeEditor';
import styles from './annotationWorkspace.less';

function NativeConfigurationSettings({
  project,
  samples,
  version,
  specVersions,
  onVersionChange,
  suggestion,
  refresh,
  onGuard,
  resources = [],
}: {
  resources?: InputResource[];
  project: NativeProject;
  samples: NativeSample[];
  version?: NativeSpec;
  specVersions: NativeSpec[];
  onVersionChange: (specVersionId: string) => void;
  suggestion: string;
  refresh: (specId?: string) => void;
  run: (action: () => Promise<void>) => Promise<void>;
  onGuard: (guard: () => boolean) => void;
}) {
  const instructionsId = useId();
  const [xml, setXml] = useState(''),
    [instructions, setInstructions] = useState(''),
    [allowEmpty, setAllowEmpty] = useState(false),
    [sampleId, setSampleId] = useState(''),
    // 模板配置器首次打开时默认就是模板列表，避免初始状态事件竞态导致返回按钮缺失。
    [templateBrowserOpen, setTemplateBrowserOpen] = useState(true);
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  const isTimeSeries = ['TIME_SERIES', 'TIMESERIES_ANNOTATION'].includes(
    project.taskType,
  );
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
      (isTimeSeries
        ? nativeDefaults.TIME_SERIES
        : nativeDefaults.COMPUTER_VISION);
    if (seed || !samples.length || isTimeSeries)
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
    project.taskType,
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
    <Card title="标注设置" className={styles.specSettings}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {locked ? (
          <Alert
            showIcon
            message="当前项目只读"
            description="标注规范仅供查看，不能修改。"
          />
        ) : (
          <div className={styles.specLead}>
            <Typography.Text strong>配置本项目的标注规范</Typography.Text>
            <Typography.Text type="secondary">
              选择模板并配置标签。保存后会生成新的规范版本，已发布任务继续使用原版本。
            </Typography.Text>
            <Space wrap>
              <Typography.Text>正在编辑</Typography.Text>
              <Select
                aria-label="选择要编辑的规范版本"
                style={{ minWidth: 140 }}
                value={version?.specVersionId}
                placeholder="尚未保存规范"
                disabled={!specVersions.length}
                onChange={onVersionChange}
                options={specVersions.map((item) => ({
                  value: item.specVersionId,
                  label: `第 ${item.versionNo} 版`,
                }))}
              />
            </Space>
          </div>
        )}

        <div className={styles.specMetaGrid}>
          <section className={styles.specField}>
            <div className={styles.specFieldHeading}>
              <label htmlFor={instructionsId}>标注说明</label>
              <span>向标注员说明判断标准和特殊情况</span>
            </div>
            <Input.TextArea
              id={instructionsId}
              aria-label="标注说明"
              value={instructions}
              readOnly={locked}
              autoSize={{ minRows: 3, maxRows: 6 }}
              placeholder="例如：发现表面裂纹时选择“缺陷”；无法判断时请备注原因。"
              onChange={(e) => {
                metadataDirty.current = true;
                setInstructions(e.target.value);
              }}
            />
          </section>

          <section className={styles.specCompletion}>
            <div className={styles.specFieldHeading}>
              <span>完成规则</span>
              <span>决定标注员何时可以提交</span>
            </div>
            <Checkbox
              checked={allowEmpty}
              disabled={locked}
              onChange={(e) => {
                metadataDirty.current = true;
                setAllowEmpty(e.target.checked);
              }}
            >
              允许提交无标注结果
            </Checkbox>
            <Typography.Text type="secondary">
              开启后，提交时必须填写无目标原因。
            </Typography.Text>
          </section>
        </div>

        <section className={styles.specEditorSection}>
          <div className={styles.specSectionHeader}>
            <div>
              <Typography.Text strong>标签模板与预览</Typography.Text>
              <Typography.Text type="secondary">
                在下方编辑模板、设置标签，并检查标注界面效果。
              </Typography.Text>
            </div>
            {!!samples.length && !isTimeSeries && (
              <div className={styles.specPreviewSelect}>
                <label htmlFor={`${instructionsId}-sample`}>预览样本</label>
                <Select
                  id={`${instructionsId}-sample`}
                  aria-label="设置预览样本"
                  value={selected?.sampleId}
                  onChange={setSampleId}
                  options={samples.map((sample) => ({
                    value: sample.sampleId,
                    label: sample.metadata.displayName || sample.sourcePath,
                  }))}
                />
              </div>
            )}
          </div>
          {!locked && templateBrowserOpen && (
            <div className={styles.templateBrowserBackRow}>
              <Button
                onClick={() =>
                  window.dispatchEvent(
                    new Event('labeling-template-browser-back'),
                  )
                }
              >
                ← 返回标注设置
              </Button>
            </div>
          )}
          {xml &&
            (locked ? (
              <Input.TextArea
                aria-label="已冻结的标注配置"
                value={xml}
                readOnly
                autoSize={{ minRows: 8, maxRows: 24 }}
              />
            ) : selected && !isTimeSeries ? (
              <NativeEditor
                key={`${version?.specVersionId || 'draft'}:${suggestion}`}
                path={`/projects/${project.projectId}/samples/${selected.sampleId}/native-input`}
                sampleId={selected.sampleId}
                configProjectId={project.projectId}
                category={categoryOf(project)}
                config={xml}
                templateId={version?.editorConfig.templateId}
                onConfig={save}
                onTemplateBrowserMode={setTemplateBrowserOpen}
                onGuard={installGuard}
              />
            ) : (
              <LocalEditor
                config={xml}
                configProjectId={project.projectId}
                category={categoryOf(project)}
                onConfig={save}
                onTemplateBrowserMode={setTemplateBrowserOpen}
                onGuard={installGuard}
              />
            ))}
        </section>
      </Space>
    </Card>
  );
}

export default function NativeSpecSettings(props: Parameters<typeof NativeConfigurationSettings>[0]) {
  return isLegacyProjectSpec(props.version, props.project.taskType) || (!props.version && props.project.taskType === 'IMAGE_CLASSIFICATION')
    ? <LegacyProjectSettings {...props} /> : <NativeConfigurationSettings {...props} />;
}
