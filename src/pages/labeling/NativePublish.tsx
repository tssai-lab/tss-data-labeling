import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useId, useMemo, useState } from 'react';
import { operationKey, post } from '@/services/labeling';
import type {
  NativeProject,
  NativeSample,
  NativeSpec,
} from '@/services/nativeLabeling';
export type ReworkScope = {
  ids: string[];
  references: Record<string, string>;
  specVersionId: string;
};

export default function NativePublish({
  project,
  samples,
  version,
  specVersions,
  onVersionChange,
  reviews,
  rework,
  run,
  refresh,
  busy,
  initialSelection,
}: {
  initialSelection?: string[];
  project: NativeProject;
  samples: NativeSample[];
  version?: NativeSpec;
  specVersions: NativeSpec[];
  onVersionChange: (specVersionId: string) => void;
  reviews: any[];
  rework?: ReworkScope;
  run: (action: () => Promise<void>) => Promise<void>;
  refresh: () => void;
  busy: boolean;
}) {
  const sizeId = useId(),
    peopleId = useId();
  const [selected, setSelected] = useState<React.Key[]>([]),
    [size, setSize] = useState(10),
    [people, setPeople] = useState(1),
    [plan, setPlan] = useState<any>();
  const [reopen, setReopen] = useState(false);
  const [search, setSearch] = useState('');
  const visible = samples.filter((sample) =>
    (sample.metadata.displayName || sample.sourcePath)
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  const accepted = new Set(
    reviews
      .filter(
        (r) =>
          r.specVersionId === version?.specVersionId && r.status === 'ACCEPTED',
      )
      .map((r) => r.sampleId),
  );
  const selectedSamples = useMemo(
    () =>
      selected.map((id) => {
        const sampleId = String(id);
        return (
          samples.find((sample) => sample.sampleId === sampleId) || {
            sampleId,
            batchId: '',
            sourcePath: '样本详情暂未加载',
            mediaType: '',
            contentSha256: '',
            metadata: { displayName: undefined },
          }
        );
      }),
    [selected, samples],
  );
  const specLabels = version?.labels.map((label) => label.name) || [];
  useEffect(() => {
    setSelected(rework?.ids || initialSelection || []);
    setPlan(undefined);
  }, [project.projectId, rework, version?.specVersionId, initialSelection]);
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  return (
    <Card title="选择样本并发布">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {!version && <Alert type="warning" message="请先保存标注设置。" />}
        {rework && (
          <Alert message="重新标注：上一轮结果作为参考，仍需明确完成并提交。" />
        )}
        <Space wrap>
          <Typography.Text>本轮使用规范</Typography.Text>
          <Select
            aria-label="选择本轮使用的规范版本"
            style={{ minWidth: 160 }}
            value={version?.specVersionId}
            placeholder="尚未保存规范"
            disabled={!specVersions.length || !!rework}
            onChange={onVersionChange}
            options={specVersions.map((item) => ({
              value: item.specVersionId,
              label: `第 ${item.versionNo} 版`,
            }))}
          />
        </Space>
        <Radio.Group value="HUMAN">
          <Radio value="HUMAN">人工标注</Radio>
          <Radio value="AI" disabled>
            AI 标注（暂未开放）
          </Radio>
        </Radio.Group>
        <Checkbox
          checked={reopen}
          disabled={locked || !!plan}
          onChange={(e) => {
            setReopen(e.target.checked);
            setSelected((ids) => ids.filter((id) => !accepted.has(String(id))));
          }}
        >
          允许重新处理已确认样本（发布后取消这些样本的确认）
        </Checkbox>
        <Input.Search
          aria-label="筛选发布样本"
          placeholder="搜索样本名称或来源"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <Space wrap>
          <Button
            disabled={locked || visible.length > 10000}
            onClick={() => {
              setSelected(
                visible
                  .filter((sample) => reopen || !accepted.has(sample.sampleId))
                  .map((sample) => sample.sampleId),
              );
              setPlan(undefined);
            }}
          >
            选择筛选结果
          </Button>
          <Button
            onClick={() => {
              setSelected([]);
              setPlan(undefined);
            }}
          >
            清空选择
          </Button>
          <span>
            已选择 {selected.length} 个样本；每次发布最多 10000 个，可分批发布。
          </span>
        </Space>
        <Table
          rowKey="sampleId"
          size="small"
          dataSource={visible}
          scroll={{ x: 700 }}
          rowSelection={{
            selectedRowKeys: selected,
            onChange: (ids) => {
              setSelected(ids);
              setPlan(undefined);
            },
            getCheckboxProps: (sample) => ({
              disabled: locked || (!reopen && accepted.has(sample.sampleId)),
            }),
          }}
          columns={[
            {
              title: '样本',
              render: (_, sample) =>
                sample.metadata.displayName || sample.sourcePath,
            },
            {
              title: '审核状态',
              render: (_, sample) =>
                accepted.has(sample.sampleId)
                  ? '已确认'
                  : reviews.some(
                        (r) =>
                          r.sampleId === sample.sampleId &&
                          r.status === 'NEEDS_REVISION',
                      )
                    ? '需重标'
                    : '待处理',
            },
          ]}
        />
        <Space wrap>
          <label htmlFor={sizeId}>
            每包样本数{' '}
            <InputNumber
              id={sizeId}
              aria-label="每包样本数"
              min={1}
              max={1000}
              value={size}
              disabled={!!plan}
              onChange={(v) => setSize(v || 1)}
            />
          </label>
          <label htmlFor={peopleId}>
            每个样本标注人数{' '}
            <InputNumber
              id={peopleId}
              aria-label="每个样本标注人数"
              min={1}
              max={10}
              value={people}
              disabled={!!plan}
              onChange={(v) => setPeople(v || 1)}
            />
          </label>
        </Space>
        <Button
          disabled={
            locked ||
            !version ||
            !selected.length ||
            selected.length > 10000 ||
            !!plan
          }
          loading={busy}
          onClick={() =>
            void run(async () => {
              const result = await post(
                `/projects/${project.projectId}/package-plans`,
                {
                  specVersionId: version!.specVersionId,
                  packageSize: size,
                  annotatorsPerSample: people,
                  samples: selected.map((id) => ({
                    sampleId: id,
                    ...(rework?.references[String(id)]
                      ? { referenceResultId: rework.references[String(id)] }
                      : {}),
                  })),
                },
                operationKey(),
              );
              setPlan(result);
            })
          }
        >
          生成发布计划
        </Button>
        {plan && (
          <Card size="small" title="确认发布范围">
            <Alert
              type="warning"
              showIcon
              message="请在发布前核对本轮内容"
              description={`${plan.sampleCount} 个样本，${plan.packageSampleCounts.length} 个任务包，共 ${plan.totalWorkload} 份标注工作。确认后将按当前样本清单和规范版本发布。`}
              style={{ marginBottom: 16 }}
            />
            <Typography.Title level={5}>本轮样本范围</Typography.Title>
            <Table
              size="small"
              rowKey="sampleId"
              dataSource={selectedSamples}
              pagination={{ pageSize: 5, showTotal: (total) => `共 ${total} 个样本` }}
              columns={[
                {
                  title: '样本名称',
                  render: (_, sample) =>
                    sample.metadata.displayName || sample.sourcePath,
                },
                {
                  title: '样本 ID',
                  render: (_, sample) => (
                    <Typography.Text
                      copyable={{ text: sample.sampleId }}
                      title={sample.sampleId}
                    >
                      {sample.sampleId.slice(0, 12)}
                    </Typography.Text>
                  ),
                },
              ]}
            />
            <Typography.Title level={5} style={{ marginTop: 20 }}>
              本轮标注规范
            </Typography.Title>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Typography.Text>
                第 {version?.versionNo} 版 · 模板 {version?.editorConfig.templateId}（
                {version?.editorConfig.templateVersion}）
              </Typography.Text>
              {version?.instructions && (
                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
                  {version.instructions}
                </Typography.Paragraph>
              )}
              {specLabels.length > 0 && (
                <Typography.Text>
                  标签：{specLabels.join('、')}
                </Typography.Text>
              )}
              <details>
                <summary>查看完整标注配置</summary>
                <pre
                  style={{
                    maxHeight: 260,
                    overflow: 'auto',
                    whiteSpace: 'pre-wrap',
                    overflowWrap: 'anywhere',
                    padding: 12,
                    background: '#f5f7fa',
                    borderRadius: 6,
                  }}
                >
                  {version?.editorConfig.labelConfig || '暂无配置'}
                </pre>
              </details>
            </Space>
            <Space>
              <Button
                type="primary"
                loading={busy}
                disabled={locked}
                onClick={() =>
                  void run(async () => {
                    await post(
                      `/package-plans/${plan.planId}/publish`,
                      { selectionHash: plan.selectionHash },
                      `publish-${plan.planId}`,
                    );
                    setPlan(undefined);
                    refresh();
                  })
                }
              >
                确认发布到任务广场
              </Button>
              <Button onClick={() => setPlan(undefined)}>重新选择</Button>
            </Space>
          </Card>
        )}
      </Space>
    </Card>
  );
}
