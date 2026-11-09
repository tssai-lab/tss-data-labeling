import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useRef, useState } from 'react';
import {
  describeLabelingError,
  type Label,
  labelingJson,
  operationKey,
  type Project,
  post,
  type SpecVersion,
} from '@/services/labeling';

export default function SpecVersions({
  project,
  writable,
  selectedId,
  onSelect,
  onUpdated,
}: {
  project: Project;
  writable: boolean;
  selectedId?: string;
  onSelect: (id: string) => void;
  onUpdated: (p: Project) => void;
}) {
  const { message } = App.useApp();
  const specs = project.specVersions || [];
  const selected =
    specs.find((s) => s.specVersionId === selectedId) || specs[0];
  const [editing, setEditing] = useState(false);
  const [labels, setLabels] = useState<Label[]>([]);
  const [instructions, setInstructions] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const attempt = useRef<{ payload: string; key: string } | undefined>(
    undefined,
  );
  function begin(spec?: SpecVersion) {
    setLabels(
      spec
        ? spec.labels.map((l) => ({ ...l }))
        : [{ id: `label-${operationKey()}`, name: '' }],
    );
    setInstructions(spec?.instructions || '');
    setEditing(true);
    setError('');
  }
  async function save() {
    if (busy) return;
    const names = labels.map((l) => l.name.trim());
    if (
      !names.length ||
      names.some((n) => !n) ||
      new Set(names).size !== names.length
    ) {
      setError('请填写至少一个分类，名称不能为空或重复');
      return;
    }
    const body = {
      taskType: 'IMAGE_CLASSIFICATION',
      labels: labels.map((l) => ({ id: l.id, name: l.name.trim() })),
      instructions,
      editorConfig: {},
      schemaVersion: '1.0',
    };
    const payload = JSON.stringify(body);
    if (attempt.current?.payload !== payload)
      attempt.current = { payload, key: operationKey() };
    setBusy(true);
    setError('');
    try {
      const created = await post<SpecVersion>(
        `/projects/${encodeURIComponent(project.projectId)}/spec-versions`,
        body,
        attempt.current.key,
      );
      const next = await labelingJson<Project>(
        `/projects/${encodeURIComponent(project.projectId)}`,
      );
      const readback = next.specVersions?.find(
        (s) => s.specVersionId === created.specVersionId,
      );
      if (
        !readback ||
        JSON.stringify(readback.labels) !== JSON.stringify(body.labels) ||
        readback.instructions !== instructions
      )
        throw new Error('规范读回不一致，请刷新项目确认');
      onUpdated(next);
      onSelect(created.specVersionId);
      setEditing(false);
      attempt.current = undefined;
      message.success('新规范已保存并读回，已有任务包仍使用冻结版本');
    } catch (e) {
      setError(describeLabelingError(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card title="1. 分类规范与历史版本">
      <Typography.Paragraph type="secondary">
        数据类型：图片 ·
        任务形式：单标签分类。空选择表示未完成；“无目标”如有业务含义，须建为合法分类。
      </Typography.Paragraph>
      {error && <Alert type="error" showIcon message={error} />}
      {specs.length > 0 && (
        <>
          <Select
            aria-label="选择规范版本"
            style={{ width: '100%', marginBottom: 16 }}
            value={selected?.specVersionId}
            disabled={busy || editing}
            onChange={onSelect}
            options={specs.map((s) => ({
              value: s.specVersionId,
              label: `版本 ${s.versionNo} · ${s.specVersionId}`,
            }))}
          />
          <Space wrap>
            {selected?.labels.map((l) => (
              <Tag key={l.id} title={l.id}>
                {l.name}
              </Tag>
            ))}
          </Space>
          <Typography.Paragraph
            ellipsis={{ rows: 4, expandable: true, symbol: '展开说明' }}
            style={{ whiteSpace: 'pre-wrap', marginTop: 16 }}
          >
            {selected?.instructions || '暂无标注说明'}
          </Typography.Paragraph>
          <Typography.Paragraph type="secondary">
            打包将绑定所选版本；修改标签名称、顺序时保留稳定 ID。
          </Typography.Paragraph>
        </>
      )}
      {!editing && (
        <Button disabled={!writable} onClick={() => begin(selected)}>
          {selected ? '从此规范创建新版本' : '创建首版规范'}
        </Button>
      )}
      {editing && (
        <Form
          layout="vertical"
          onFinish={() => void save()}
          disabled={busy || !writable}
        >
          {labels.map((label, i) => (
            <div key={label.id} style={{ marginBottom: 16 }}>
              <Input
                aria-label={`分类 ${i + 1} 名称`}
                value={label.name}
                maxLength={200}
                onChange={(e) =>
                  setLabels((old) =>
                    old.map((l) =>
                      l.id === label.id ? { ...l, name: e.target.value } : l,
                    ),
                  )
                }
              />
              <Typography.Text
                type="secondary"
                style={{ overflowWrap: 'anywhere' }}
              >
                {label.id}
              </Typography.Text>
              <Space wrap>
                <Button
                  size="small"
                  disabled={i === 0 || busy}
                  onClick={() =>
                    setLabels((old) => {
                      const next = [...old];
                      [next[i - 1], next[i]] = [next[i], next[i - 1]];
                      return next;
                    })
                  }
                >
                  上移
                </Button>
                <Button
                  size="small"
                  disabled={busy}
                  onClick={() =>
                    setLabels((old) => old.filter((l) => l.id !== label.id))
                  }
                >
                  移除
                </Button>
              </Space>
            </div>
          ))}
          <Button
            onClick={() =>
              setLabels((old) => [
                ...old,
                { id: `label-${operationKey()}`, name: '' },
              ])
            }
          >
            新增分类
          </Button>
          <Form.Item label="标注说明" style={{ marginTop: 16 }}>
            <Input.TextArea
              rows={4}
              maxLength={10000}
              value={instructions}
              onChange={(e) => setInstructions(e.target.value)}
            />
          </Form.Item>
          <Space wrap>
            <Button type="primary" htmlType="submit" loading={busy}>
              保存新版本
            </Button>
            <Button onClick={() => setEditing(false)}>取消</Button>
          </Space>
        </Form>
      )}
    </Card>
  );
}
