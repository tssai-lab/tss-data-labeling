import { Alert, Button, Card, Input, Select, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { operationKey, post, type Label } from '@/services/labeling';
import type {
  NativeProject,
  NativeSample,
  NativeSpec,
} from '@/services/nativeLabeling';
import { legacyProjectSpecRequest } from '@/services/projectCompatibility.mjs';
import ProjectSampleEditor from './ProjectSampleEditor';
import styles from './annotationWorkspace.less';

export default function LegacyProjectSettings({
  project,
  samples,
  version,
  specVersions,
  onVersionChange,
  refresh,
  onGuard,
}: {
  project: NativeProject;
  samples: NativeSample[];
  version?: NativeSpec;
  specVersions: NativeSpec[];
  onVersionChange: (id: string) => void;
  refresh: (id?: string) => void;
  onGuard: (guard: () => boolean) => void;
}) {
  const [labels, setLabels] = useState<Label[]>([]);
  const [instructions, setInstructions] = useState('');
  const [sampleId, setSampleId] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const dirty = useRef(false);
  const savingLock = useRef(false);
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  useEffect(() => {
    setLabels((version?.labels || []).map((label) => ({ ...label })));
    setInstructions(version?.instructions || '');
    setError('');
    dirty.current = false;
  }, [project.projectId, version?.specVersionId]);
  useEffect(() => {
    onGuard(
      () =>
        !savingLock.current &&
        (!dirty.current ||
          window.confirm('标注设置尚未保存，是否放弃并离开？')),
    );
    const warn = (event: BeforeUnloadEvent): void => {
      if (dirty.current || savingLock.current) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => {
      window.removeEventListener('beforeunload', warn);
      onGuard(() => true);
    };
  }, [project.projectId]);
  const valid =
    labels.length > 0 &&
    labels.every((label) => label.name.trim()) &&
    new Set(labels.map((label) => label.name.trim())).size === labels.length;
  async function save(): Promise<void> {
    if (locked || !valid || savingLock.current) return;
    savingLock.current = true;
    setSaving(true);
    setError('');
    try {
      const saved = await post<NativeSpec>(
        `/projects/${project.projectId}/spec-versions`,
        legacyProjectSpecRequest(
          version,
          project.taskType,
          labels,
          instructions,
        ),
        operationKey(),
      );
      dirty.current = false;
      refresh(saved.specVersionId);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '保存失败');
    } finally {
      savingLock.current = false;
      setSaving(false);
    }
  }
  const selectedSample =
    samples.find((sample) => sample.sampleId === sampleId) || samples[0];
  return (
    <Card title="标注设置" className={styles.specSettings}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <div className={styles.specLead}>
          <Typography.Text strong>配置本项目的标注规范</Typography.Text>
          <Typography.Text type="secondary">
            保存后会生成新的规范版本，已发布任务继续使用原版本。
          </Typography.Text>
          <Select
            aria-label="选择要编辑的规范版本"
            value={version?.specVersionId}
            placeholder="尚未保存规范"
            onChange={onVersionChange}
            options={specVersions.map((spec) => ({
              value: spec.specVersionId,
              label: `第 ${spec.versionNo} 版`,
            }))}
          />
        </div>
        {error && <Alert type="error" showIcon message={error} />}
        <div className={styles.specField}>
          <Typography.Title level={5}>标签设置</Typography.Title>
          <Space direction="vertical" style={{ width: '100%' }}>
            {labels.map((label, index) => (
              <Space key={label.id}>
                <Input
                  aria-label={`标签 ${index + 1}`}
                  value={label.name}
                  disabled={locked || saving}
                  onChange={(event) => {
                    dirty.current = true;
                    setLabels((current) =>
                      current.map((item) =>
                        item.id === label.id
                          ? { ...item, name: event.target.value }
                          : item,
                      ),
                    );
                  }}
                />
                <Button
                  disabled={locked || saving}
                  onClick={() => {
                    dirty.current = true;
                    setLabels((current) =>
                      current.filter((item) => item.id !== label.id),
                    );
                  }}
                >
                  删除
                </Button>
              </Space>
            ))}
          </Space>
          <Button
            style={{ marginTop: 12 }}
            disabled={locked || saving}
            onClick={() => {
              dirty.current = true;
              setLabels((current) => [
                ...current,
                { id: `label_${operationKey()}`, name: '' },
              ]);
            }}
          >
            添加标签
          </Button>
        </div>
        <div className={styles.specField}>
          <Typography.Title level={5}>标注说明</Typography.Title>
          <Input.TextArea
            aria-label="标注说明"
            value={instructions}
            rows={4}
            disabled={locked || saving}
            onChange={(event) => {
              dirty.current = true;
              setInstructions(event.target.value);
            }}
          />
        </div>
        <Button
          type="primary"
          disabled={locked || !valid}
          loading={saving}
          onClick={() => void save()}
        >
          保存为新规范版本
        </Button>
        <section className={styles.specEditorSection}>
          <div className={styles.specSectionHeader}>
            <Typography.Text strong>标注界面预览</Typography.Text>
            <Select
              aria-label="选择预览样本"
              value={selectedSample?.sampleId}
              placeholder="暂无样本"
              onChange={setSampleId}
              options={samples.map((sample) => ({
                value: sample.sampleId,
                label: sample.metadata.displayName || sample.sourcePath,
              }))}
            />
          </div>
          {version && selectedSample && valid ? (
            <ProjectSampleEditor
              project={project}
              version={{ ...version, labels, instructions }}
              sampleId={selectedSample.sampleId}
            />
          ) : (
            <Typography.Text type="secondary">
              添加标签和样本后可预览标注界面。
            </Typography.Text>
          )}
        </section>
      </Space>
    </Card>
  );
}
