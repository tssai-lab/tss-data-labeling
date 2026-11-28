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
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { operationKey, post } from '@/services/labeling';
import { type LiveAgent, ownerApi } from '@/services/labelingOwner';
import type { Plan } from '@/services/labelingTasks';
import type {
  NativeProject,
  NativeSample,
  NativeSpec,
} from '@/services/nativeLabeling';
import ui from './workspaceUi.less';
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
  onAiPublished,
  mode,
  onModeChange,
}: {
  mode: 'HUMAN' | 'AI' | 'MIXED';
  onModeChange: (mode: 'HUMAN' | 'AI' | 'MIXED') => void;
  initialSelection?: string[];
  onAiPublished?: (samples: NativeSample[], version: NativeSpec, plan: Plan) => void;
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
    [plan, setPlan] = useState<Plan>(),
    [publishedPlan, setPublishedPlan] = useState<Plan>();
  const [agents, setAgents] = useState<LiveAgent[]>([]),
    [agentIds, setAgentIds] = useState<string[]>([]);
  const [reopen, setReopen] = useState(false);
  const planAttempt = useRef<{ signature: string; key: string } | undefined>(
    undefined,
  );
  const [search, setSearch] = useState('');
  const [pageSize, setPageSize] = useState(10);
  const [reviewFilter, setReviewFilter] = useState('ALL');
  const accepted = new Set(
    reviews
      .filter(
        (r) =>
          r.specVersionId === version?.specVersionId && r.status === 'ACCEPTED',
      )
      .map((r) => r.sampleId),
  );
  const reviewStatus = (sample: NativeSample) =>
    accepted.has(sample.sampleId)
      ? 'ACCEPTED'
      : reviews.some(
            (r) =>
              r.specVersionId === version?.specVersionId &&
              r.sampleId === sample.sampleId &&
              r.status === 'NEEDS_REVISION',
          )
        ? 'NEEDS_REVISION'
        : 'PENDING';
  const visible = samples.filter(
    (sample) =>
      (sample.metadata.displayName || sample.sourcePath)
        .toLowerCase()
        .includes(search.toLowerCase()) &&
      (reviewFilter === 'ALL' || reviewStatus(sample) === reviewFilter),
  );
  const selectableIds = visible
    .filter((sample) => reopen || !accepted.has(sample.sampleId))
    .map((sample) => sample.sampleId);
  const selectionWithVisible = [
    ...new Set([...selected.map(String), ...selectableIds]),
  ];
  const allVisibleSelected =
    selectableIds.length > 0 &&
    selectableIds.every((id) => selected.includes(id));
  function selectVisible(checked: boolean): void {
    const visibleKeys = new Set(selectableIds);
    setSelected(
      checked
        ? selectionWithVisible
        : selected.filter((id) => !visibleKeys.has(String(id))),
    );
    setPlan(undefined);
  }
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
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  const canConfigureAi =
    !locked &&
    Boolean(version) &&
    selected.length > 0 &&
    selected.length <= 10000 &&
    (mode === 'HUMAN' || people > 0) &&
    (mode === 'HUMAN' || agentIds.length > 0) &&
    !busy;
  const aiMode = mode === 'AI' ? 'AI_AGENT' : mode === 'MIXED' ? 'MIXED' : 'HUMAN_POOL';
  useEffect(() => {
    setSelected(rework?.ids || initialSelection || []);
    setPlan(undefined);
    setPublishedPlan(undefined);
    setAgentIds([]);
  }, [project.projectId, rework, version?.specVersionId, initialSelection]);
  useEffect(() => {
    const abort = new AbortController();
    void ownerApi.agents(abort.signal, project.taskType).then((value) => {
      if (!abort.signal.aborted) setAgents(value.filter((agent) => agent.enabled));
    }).catch(() => {
      if (!abort.signal.aborted) setAgents([]);
    });
    return () => abort.abort();
  }, [project.projectId, project.taskType]);
  return (
    <Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {!version && <Alert type="warning" message="请先保存标注设置。" />}
        {publishedPlan && (
          <Alert
            type="success"
            showIcon
            message={`已发布${publishedPlan.executionMode === 'AI_AGENT' ? '纯 AI' : publishedPlan.executionMode === 'MIXED' ? '人工 + AI' : '纯人工'}任务：${publishedPlan.packages.length} 个轮次，共 ${publishedPlan.sampleCount} 个样本`}
            description={`${publishedPlan.executionMode !== 'AI_AGENT' ? `每样本 ${publishedPlan.annotatorsPerSample ?? 1} 名人工作业；` : ''}${publishedPlan.aiExecutions?.length ? `已创建 ${publishedPlan.aiExecutions.length} 个 AI 执行单元；` : ''}${publishedPlan.packages
              .map(
                (pkg: { sequenceNo: number; sampleCount: number }) =>
                  `第 ${pkg.sequenceNo} 轮：${pkg.sampleCount} 个样本`,
              )
              .join('；')}`}
          />
        )}
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
        <Radio.Group
          value={mode}
          onChange={(event) => {
            onModeChange(event.target.value);
            setPlan(undefined);
            setPublishedPlan(undefined);
            if (event.target.value === 'HUMAN') setAgentIds([]);
          }}
          disabled={locked || !!plan}
        >
          <Radio value="HUMAN">人工标注</Radio>
          <Radio value="AI">AI 标注</Radio>
          <Radio value="MIXED">人工 + AI</Radio>
        </Radio.Group>
        {(mode === 'AI' || mode === 'MIXED') && (
          <Alert
            type="info"
            showIcon
            message="AI 标注员将在发布时加入本轮"
            description="选择 AI 标注员后再生成发布预览；人工与 AI 会共用本轮样本范围。"
          />
        )}
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
        <Select
          aria-label="筛选审核状态"
          value={reviewFilter}
          onChange={setReviewFilter}
          options={[
            { value: 'ALL', label: '全部审核状态' },
            { value: 'PENDING', label: '待处理' },
            { value: 'NEEDS_REVISION', label: '需重标' },
            { value: 'ACCEPTED', label: '已确认' },
          ]}
        />
        {selected.length > 10000 && (
          <Alert
            type="warning"
            showIcon
            message="本次最多发布 10000 个样本，请减少选择后分批发布。"
          />
        )}
        <Table
          rowKey="sampleId"
          size="small"
          dataSource={visible}
          scroll={{ x: 700 }}
          pagination={{
            pageSize,
            showSizeChanger: true,
            onShowSizeChange: (_page, size) => setPageSize(size),
            showTotal: (total) =>
              `已选 ${selected.length} 个 · 共 ${total} 个样本 · 共 ${Math.ceil(total / pageSize)} 页`,
          }}
          rowSelection={{
            hideSelectAll: true,
            columnWidth: 64,
            columnTitle: (
              <Checkbox
                aria-label="全选筛选结果"
                checked={allVisibleSelected}
                indeterminate={
                  !allVisibleSelected &&
                  selectableIds.some((id) => selected.includes(id))
                }
                disabled={locked || !selectableIds.length || busy || !!plan}
                onChange={(event) => selectVisible(event.target.checked)}
              />
            ),
            preserveSelectedRowKeys: true,
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
                reviewStatus(sample) === 'ACCEPTED'
                  ? '已确认'
                  : reviewStatus(sample) === 'NEEDS_REVISION'
                    ? '需重标'
                    : '待处理',
            },
          ]}
        />
        {
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
            {(mode === 'HUMAN' || mode === 'MIXED') && <label htmlFor={peopleId}>
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
            </label>}
            {(mode === 'AI' || mode === 'MIXED') && (
              <Select
                mode="multiple"
                maxCount={10}
                style={{ minWidth: 300 }}
                aria-label="选择本轮 AI 标注员"
                value={agentIds}
                disabled={!!plan || busy}
                placeholder="选择 AI 标注员（最多 10 个）"
                onChange={setAgentIds}
                options={agents.filter((agent) => agent.taskType === project.taskType).map((agent) => ({
                  value: agent.agentId,
                  label: `${agent.displayName} · ${agent.modelVersion}`,
                }))}
              />
            )}
            <Button
              disabled={!canConfigureAi || !!plan}
              loading={busy}
              onClick={() =>
                void run(async () => {
                  if (!version) return;
                  const body = {
                    specVersionId: version.specVersionId,
                    packageSize: size,
                    annotatorsPerSample: mode === 'AI' ? 0 : people,
                    executionMode: aiMode,
                    aiAgentIds: mode === 'HUMAN' ? [] : agentIds,
                    annotatorRoster: [
                      ...(mode !== 'AI'
                        ? [{ providerId: 'human-pool', providerVersion: '1.0.0', count: people, agentIds: [] }]
                        : []),
                      ...(mode !== 'HUMAN'
                        ? [{ providerId: 'ai-agent-worker', providerVersion: '1.0.0', count: agentIds.length, agentIds }]
                        : []),
                    ],
                    reopenAccepted: reopen,
                    samples: selected.map((id) => ({
                      sampleId: id,
                      ...(rework?.references[String(id)]
                        ? { referenceResultId: rework.references[String(id)] }
                        : {}),
                    })),
                  };
                  const signature = JSON.stringify([project.projectId, body]);
                  if (planAttempt.current?.signature !== signature) {
                    planAttempt.current = { signature, key: operationKey() };
                  }
                  const result = await post<Plan>(
                    `/projects/${project.projectId}/package-plans`,
                    body,
                    planAttempt.current.key,
                  );
                  setPlan(result);
                })
              }
            >
              生成发布计划
            </Button>
          </Space>
        }
        {selected.length > 0 && (
          <Typography.Text type="secondary">
            预计生成 {Math.ceil(selected.length / size)} 个任务包，每包最多{' '}
            {size} 个样本
            {selected.length % size > 0
              ? `，最后一包 ${selected.length % size} 个样本。剩余样本会单独成包。`
              : '。'}
          </Typography.Text>
        )}
        {plan && (
          <Card size="small" title="确认发布范围">
            <Alert
              type="warning"
              showIcon
              message="请在发布前核对本轮内容"
              description={`${plan.sampleCount} 个样本，${plan.packageSampleCounts.length} 个任务包，共 ${plan.totalWorkload} 份标注工作。确认后将按当前样本清单和规范版本发布。`}
              style={{ marginBottom: 16 }}
            />
            <Typography.Paragraph>
              分包明细：
              {plan.packageSampleCounts
                .map(
                  (count: number, index: number) =>
                    `第 ${index + 1} 包 ${count} 个样本`,
                )
                .join('；')}
            </Typography.Paragraph>
            <Typography.Title level={5}>本轮样本范围</Typography.Title>
            <Typography.Paragraph type="secondary">
              当前可核对全部选中样本及上方各包数量；服务尚未提供预览中每包的样本明细。
            </Typography.Paragraph>
            <Table
              size="small"
              rowKey="sampleId"
              dataSource={selectedSamples}
              pagination={{
                pageSize: 5,
                showTotal: (total) => `共 ${total} 个样本`,
              }}
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
                第 {version?.versionNo} 版 · 模板{' '}
                {version?.editorConfig.templateId}（
                {version?.editorConfig.templateVersion}）
              </Typography.Text>
              {version?.instructions && (
                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
                  {version.instructions}
                </Typography.Paragraph>
              )}
              {specLabels.length > 0 && (
                <Typography.Text>标签：{specLabels.join('、')}</Typography.Text>
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
                    const published = await post<Plan>(
                      `/package-plans/${plan.planId}/publish`,
                      { selectionHash: plan.selectionHash },
                      `publish-${plan.planId}`,
                    );
                    setPublishedPlan(published);
                    if (published.aiExecutions?.length && version)
                      onAiPublished?.(selectedSamples, version, published);
                    setPlan(undefined);
                    planAttempt.current = undefined;
                    refresh();
                  })
                }
              >
                确认发布到任务广场
              </Button>
              <Button
                onClick={() => {
                  planAttempt.current = undefined;
                  setPlan(undefined);
                }}
              >
                重新选择
              </Button>
            </Space>
          </Card>
        )}
      </Space>
    </Card>
  );
}
